package com.familyguard.agent;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import tools.jackson.databind.ObjectMapper;

/**
 * PIN parental en el equipo del hijo: solo se guarda el verificador PBKDF2 que envía el servidor (nunca el PIN).
 *
 * <ul>
 *   <li>Protege las acciones locales sensibles: pausar la supervisión, cambiar el servidor, quitar el inicio
 *   automático, cerrar el Agente y desinstalarlo.</li>
 *   <li>Tras 5 PIN incorrectos se bloquea 5 minutos; cada bloqueo siguiente dura el doble (máximo 1 hora). El
 *   bloqueo se guarda cifrado y usa la hora de confianza, así que reiniciar o atrasar el reloj no lo levanta.</li>
 *   <li>Las pausas tienen una hora de fin fija; reiniciar no las alarga.</li>
 * </ul>
 */
final class ParentalProtection {
    static final int MAX_FAILURES = 5;
    static final Duration FIRST_LOCK = Duration.ofMinutes(5), MAX_LOCK = Duration.ofHours(1);

    enum Outcome { OK, WRONG, LOCKED, NOT_CONFIGURED }
    record Result(Outcome outcome, int remainingAttempts, Instant lockedUntil) {
        boolean ok() { return outcome == Outcome.OK; }
        String message() {
            return switch (outcome) {
                case OK -> "PIN correcto";
                case NOT_CONFIGURED -> "El padre todavía no configuró el PIN parental en FamilyGuard Administrador.";
                case LOCKED -> "Demasiados intentos. Vuelve a intentarlo más tarde.";
                default -> remainingAttempts == 1 ? "PIN incorrecto. Queda 1 intento." : "PIN incorrecto. Quedan " + remainingAttempts + " intentos.";
            };
        }
    }
    record LocalEvent(UUID id, String type, String detail, Instant occurredAt) { }
    record State(int version, String verifier, int failures, int locks, Instant lockedUntil, Instant pausedUntil,
                 String pauseSource, List<LocalEvent> events) {
        State { events = events == null ? List.of() : List.copyOf(events); }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtectedFile file;
    private State state;
    private String recovery;

    ParentalProtection() { this(new ProtectedFile(AgentPaths.userData("protection.bin"), ProtectedFile.DPAPI)); }
    ParentalProtection(ProtectedFile file) { this.file = file; this.state = read(); }

    synchronized boolean configured() { return state.verifier() != null; }
    synchronized int version() { return state.version(); }

    /** Verificador recibido del servidor; un cambio de PIN reinicia los intentos. */
    synchronized void updateVerifier(boolean configured, int version, String verifier) {
        if (!configured) { if (state.verifier() == null) return; verifier = null; version = 0; }
        if (version == state.version() && java.util.Objects.equals(verifier, state.verifier())) return;
        save(new State(version, verifier, 0, 0, null, state.pausedUntil(), state.pauseSource(), state.events()));
    }

    synchronized Result verify(String pin, Instant now) {
        if (state.verifier() == null) return new Result(Outcome.NOT_CONFIGURED, 0, null);
        if (state.lockedUntil() != null && now.isBefore(state.lockedUntil()))
            return new Result(Outcome.LOCKED, 0, state.lockedUntil());
        if (pin != null && matches(pin.strip(), state.verifier())) {
            save(new State(state.version(), state.verifier(), 0, state.locks(), null, state.pausedUntil(), state.pauseSource(), state.events()));
            return new Result(Outcome.OK, MAX_FAILURES, null);
        }
        int failures = state.failures() + 1;
        List<LocalEvent> events = new ArrayList<>(state.events());
        if (failures >= MAX_FAILURES) {
            int locks = state.locks() + 1;
            Duration lock = FIRST_LOCK.multipliedBy(1L << Math.min(locks - 1, 4));
            if (lock.compareTo(MAX_LOCK) > 0) lock = MAX_LOCK;
            Instant until = now.plus(lock);
            events.add(new LocalEvent(UUID.randomUUID(), "PIN_BLOQUEADO", "Bloqueado " + lock.toMinutes() + " minutos", now));
            save(new State(state.version(), state.verifier(), 0, locks, until, state.pausedUntil(), state.pauseSource(), events));
            return new Result(Outcome.LOCKED, 0, until);
        }
        events.add(new LocalEvent(UUID.randomUUID(), "PIN_INCORRECTO", null, now));
        save(new State(state.version(), state.verifier(), failures, state.locks(), null, state.pausedUntil(), state.pauseSource(), events));
        return new Result(Outcome.WRONG, MAX_FAILURES - failures, null);
    }

    /** Pausa con hora de fin fija. {@code source}: PIN o AUTORIZACION. */
    synchronized void pause(Instant now, Duration duration, String source) {
        List<LocalEvent> events = new ArrayList<>(state.events());
        if ("PIN".equals(source)) events.add(new LocalEvent(UUID.randomUUID(), "PAUSA_LOCAL", duration.toMinutes() + " minutos", now));
        save(new State(state.version(), state.verifier(), state.failures(), state.locks(), state.lockedUntil(),
                now.plus(duration), source, events));
    }

    synchronized void resume(Instant now) {
        if (state.pausedUntil() == null) return;
        List<LocalEvent> events = new ArrayList<>(state.events());
        events.add(new LocalEvent(UUID.randomUUID(), "PAUSA_FINALIZADA", null, now));
        save(new State(state.version(), state.verifier(), state.failures(), state.locks(), state.lockedUntil(), null, null, events));
    }

    /** {@code true} mientras dure la pausa; al terminar la cierra e informa una sola vez. */
    synchronized boolean paused(Instant now) {
        if (state.pausedUntil() == null) return false;
        if (now.isBefore(state.pausedUntil())) return true;
        resume(now);
        return false;
    }

    synchronized Instant pausedUntil() { return state.pausedUntil(); }

    synchronized void record(String type, String detail, Instant now) {
        List<LocalEvent> events = new ArrayList<>(state.events());
        events.add(new LocalEvent(UUID.randomUUID(), type, detail, now));
        save(new State(state.version(), state.verifier(), state.failures(), state.locks(), state.lockedUntil(),
                state.pausedUntil(), state.pauseSource(), events));
    }

    synchronized List<LocalEvent> pendingEvents() { return state.events(); }
    synchronized void acknowledge(Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        Set<UUID> done = Set.copyOf(ids);
        save(new State(state.version(), state.verifier(), state.failures(), state.locks(), state.lockedUntil(),
                state.pausedUntil(), state.pauseSource(), state.events().stream().filter(e -> !done.contains(e.id())).toList()));
    }

    String recoveryNotice() { return recovery; }

    /** Mismo formato que el servidor: pbkdf2-sha256$iteraciones$sal$hash (Base64). */
    static boolean matches(String pin, String verifier) {
        try {
            String[] p = verifier.split("\\$");
            if (p.length != 4 || !"pbkdf2-sha256".equals(p[0])) return false;
            byte[] expected = Base64.getDecoder().decode(p[3]);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(
                    new PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]), expected.length * 8)).getEncoded();
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private void save(State next) { state = next; file.write(mapper.writeValueAsBytes(state)); }

    private State read() {
        try {
            if (!file.exists()) return new State(0, null, 0, 0, null, null, null, List.of());
            return mapper.readValue(file.read(), State.class);
        } catch (Exception e) {
            // Archivo dañado: se aparta; el verificador vuelve a descargarse del servidor en la siguiente sincronización.
            recovery = DamagedFiles.quarantine(file.path(), "La protección con PIN");
            return new State(0, null, 0, 0, null, null, null, List.of());
        }
    }
}
