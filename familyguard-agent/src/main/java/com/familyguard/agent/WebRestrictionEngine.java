package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import java.time.*;
import java.util.*;

/**
 * Calcula qué dominios deben bloquearse y mantiene el bloque de FamilyGuard en {@code hosts}.
 * <ul>
 *   <li>Solo intenta escribir cuando el conjunto deseado cambia o vence un reintento controlado
 *       (30 s, 1, 2, 4, 8 y como máximo 15 minutos).</li>
 *   <li>Registra un evento por regla únicamente cuando cambia su estado (aplicado, pendiente,
 *       error o retirado), nunca en cada ciclo.</li>
 *   <li>Declara «aplicado» solo cuando el contenido real de {@code hosts} coincide con lo deseado.</li>
 * </ul>
 */
final class WebRestrictionEngine {
    enum State { SIN_REGLAS, APLICADO, PENDIENTE_PERMISOS, ERROR, NO_VERIFICADO }

    /** Cada cuánto se vuelve a comprobar en Windows un bloqueo ya verificado. */
    static final Duration REVERIFY = Duration.ofMinutes(5);
    /** Subdominios habituales que se bloquean cuando la regla incluye subdominios ({@code hosts} no admite comodines). */
    static final List<String> COMMON_SUBDOMAINS = List.of("www", "m", "mobile");
    /** Subdominios propios de algunos servicios conocidos (enlaces cortos y versiones regionales). */
    static final Map<String, List<String>> KNOWN_SUBDOMAINS = Map.of(
            "tiktok.com", List.of("vm", "vt", "us", "t"),
            "youtube.com", List.of("music"),
            "facebook.com", List.of("web", "touch"),
            "instagram.com", List.of("l"));

    static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    static final Duration MAX_RETRY = Duration.ofMinutes(15);
    static final String TYPE_APPLIED = "FILTRO_APLICADO";
    static final String TYPE_PENDING = "FILTRO_PENDIENTE_PERMISOS";
    static final String TYPE_ERROR = "FILTRO_ERROR";
    static final String TYPE_REMOVED = "FILTRO_RETIRADO";
    /** Regla activa que ahora no debe bloquear (fuera de horario o antes de su inicio); Windows sin la entrada. */
    static final String TYPE_INACTIVE = "FILTRO_INACTIVO";

    private final WebRestrictionStore store;
    private final HostsFileFilter filter;
    private final WindowsDnsVerifier verifier;
    private Instant lastVerification;
    private Set<String> verified;            // conjunto confirmado en hosts; nulo hasta la primera comprobación
    private Set<String> lastDesired;
    private State state = State.SIN_REGLAS;
    private String status = "Control web sin reglas";
    private int failures;
    private Instant nextAttempt = Instant.EPOCH;
    private long attempts;
    private final Map<Long, String> reported = new HashMap<>();
    private final Map<Long, WebRule> known = new HashMap<>();
    /** Reloj para evaluar horarios: el del Agente es resistente a cambios manuales de la hora de Windows. */
    private volatile java.util.function.Supplier<Instant> clock = Instant::now;
    void useClock(java.util.function.Supplier<Instant> value) { clock = value == null ? Instant::now : value; }
    Instant clockNow() { return clock.get(); }
    /** Permisos temporales «Permitir sitio» vigentes (entrega final). */
    private volatile java.util.function.LongPredicate siteAllowed = id -> false;
    /** Pausa autorizada por el padre: ningún sitio queda bloqueado mientras dure. */
    private volatile boolean suspended;

    WebRestrictionEngine(WebRestrictionStore store) { this(store, new HostsFileFilter(), WindowsDnsVerifier.SYSTEM); }
    /** Pruebas con un archivo hosts temporal: no se consulta el DNS real del equipo. */
    WebRestrictionEngine(WebRestrictionStore store, HostsFileFilter filter) { this(store, filter, WindowsDnsVerifier.TRUST_HOSTS_FILE); }
    WebRestrictionEngine(WebRestrictionStore store, HostsFileFilter filter, WindowsDnsVerifier verifier) {
        this.store = store; this.filter = filter; this.verifier = verifier;
        store.snapshot().rules().forEach(rule -> known.put(rule.id(), rule));
    }

    synchronized void replace(String zone, List<WebRule> rules) {
        // Las reglas anteriores siguen «conocidas» hasta confirmar su retirada del equipo.
        store.snapshot().rules().forEach(rule -> known.put(rule.id(), rule));
        store.rules(zone, rules);
        apply(clock.get());
    }

    void allowSites(java.util.function.LongPredicate allowed) { this.siteAllowed = allowed == null ? id -> false : allowed; }
    void suspend(boolean value) { this.suspended = value; }

    private boolean blocks(WebRule rule, ZonedDateTime now) {
        return !suspended && !siteAllowed.test(rule.id()) && active(rule, now);
    }

    /** Si esta regla debe restringir el sitio en {@code instant} (misma lógica que el bloqueo; para la tarjeta «Bloqueados»). */
    synchronized boolean blockingNow(WebRule rule, Instant instant) { return blocks(rule, now(instant)); }
    synchronized ZoneId zone() { return now(Instant.EPOCH).getZone(); }
    synchronized String status() { return status; }
    synchronized State state() { return state; }
    synchronized boolean pendingPermissions() { return state == State.PENDIENTE_PERMISOS; }
    synchronized long writeAttempts() { return attempts; }
    synchronized Instant nextAttempt() { return nextAttempt; }

    /** Dominios que deberían estar bloqueados en {@code instant}. */
    synchronized Set<String> desired(Instant instant) {
        Set<String> blocked = new TreeSet<>();
        for (WebRule rule : store.snapshot().rules()) if (blocks(rule, now(instant))) addDomains(blocked, rule);
        return blocked;
    }

    synchronized void apply(Instant instant) {
        var snapshot = store.snapshot();
        ZonedDateTime now = now(instant);
        Map<Long, Boolean> activity = new LinkedHashMap<>();
        Set<String> desired = new TreeSet<>();
        Set<String> targets = new TreeSet<>();   // un dominio por regla activa para comprobar en Windows
        for (WebRule rule : snapshot.rules()) {
            boolean isActive = blocks(rule, now);
            activity.put(rule.id(), isActive);
            if (isActive) { addDomains(desired, rule); targets.add(rule.domain()); }
        }
        boolean changed = !desired.equals(lastDesired);
        lastDesired = desired;
        boolean reverify = desired.equals(verified) && !desired.isEmpty() && lastVerification != null
                && !instant.isBefore(lastVerification.plus(REVERIFY));
        if ((!desired.equals(verified) && (changed || !instant.isBefore(nextAttempt))) || reverify)
            attempt(desired, targets, instant, snapshot.rules().isEmpty());
        else if (desired.equals(verified)) settle(snapshot.rules().isEmpty(), status);
        report(snapshot.rules(), activity, instant);
    }

    /** Vuelve a comprobar de inmediato, p. ej. tras una autorización administrativa. */
    synchronized void recheck(Instant instant) {
        verified = null;
        nextAttempt = Instant.EPOCH;
        apply(instant);
    }

    /** Al detener la supervisión intenta retirar el bloque; sin permisos lo deja intacto y lo informa. */
    synchronized void disable() {
        HostsFileFilter.Result result = filter.apply(Set.of());
        if (result.successful()) { verified = Set.of(); status = "Control web detenido"; }
        else status = result.detail();
    }

    private void attempt(Set<String> desired, Set<String> targets, Instant instant, boolean noRules) {
        attempts++;
        HostsFileFilter.Result result = filter.apply(desired);
        if (result.successful()) {
            // Escribir hosts no basta: solo se informa «aplicado» si Windows ya resuelve los dominios a 0.0.0.0.
            WindowsDnsVerifier.Result check = targets.isEmpty() ? new WindowsDnsVerifier.Result(true, "") : verifier.verify(targets);
            if (!check.ok()) {
                verified = null;
                failures = Math.min(failures + 1, 20);
                nextAttempt = instant.plus(backoff(failures));
                state = State.NO_VERIFICADO;
                status = check.detail();
                return;
            }
            verified = Set.copyOf(desired);
            lastVerification = instant;
            settle(noRules, check.detail().isEmpty() ? result.detail() : result.detail() + " · " + check.detail());
            return;
        }
        failures = Math.min(failures + 1, 20);
        nextAttempt = instant.plus(backoff(failures));
        state = result.outcome() == HostsFileFilter.Outcome.PERMISSION_DENIED ? State.PENDIENTE_PERMISOS : State.ERROR;
        status = result.detail();
    }

    private void settle(boolean noRules, String detail) {
        failures = 0;
        nextAttempt = Instant.EPOCH;
        state = noRules ? State.SIN_REGLAS : State.APLICADO;
        status = noRules ? "Control web sin reglas" : detail;
    }

    static Duration backoff(int failures) {
        long seconds = FIRST_RETRY.getSeconds() << Math.min(Math.max(failures - 1, 0), 10);
        return Duration.ofSeconds(Math.min(seconds, MAX_RETRY.getSeconds()));
    }

    private void report(List<WebRule> rules, Map<Long, Boolean> activity, Instant at) {
        boolean ok = state == State.APLICADO || state == State.SIN_REGLAS;
        Set<Long> current = new HashSet<>();
        for (WebRule rule : rules) {
            current.add(rule.id());
            known.put(rule.id(), rule);
            boolean isActive = activity.getOrDefault(rule.id(), false);
            String key = state + "|" + (isActive ? "BLOQUEO" : "INACTIVA") + "|" + rule.revision();
            if (key.equals(reported.get(rule.id()))) continue;
            reported.put(rule.id(), key);
            if (ok && isActive) event(rule, TYPE_APPLIED, "Sitio bloqueado y verificado en Windows (" + rule.domain() + " → 0.0.0.0)", true, at);
            else if (ok) event(rule, TYPE_INACTIVE, "Fuera del horario o vigencia: sin bloqueo activo", true, at);
            else if (state == State.PENDIENTE_PERMISOS) event(rule, TYPE_PENDING, HostsFileFilter.PERMISSION_MESSAGE, false, at);
            else event(rule, TYPE_ERROR, status, false, at);
        }
        for (Iterator<Map.Entry<Long, WebRule>> it = known.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, WebRule> entry = it.next();
            if (current.contains(entry.getKey())) continue;
            String key = "RETIRO|" + state;
            if (!key.equals(reported.get(entry.getKey()))) {
                reported.put(entry.getKey(), key);
                event(entry.getValue(), TYPE_REMOVED, ok ? "Regla retirada del equipo" : status, ok, at);
            }
            if (ok) { it.remove(); reported.remove(entry.getKey()); }
        }
    }

    private void event(WebRule rule, String type, String detail, boolean successful, Instant at) {
        store.event(new WebEvent(UUID.randomUUID(), rule.id(), rule.domain(), type, detail, at, successful, rule.revision()));
    }

    private ZonedDateTime now(Instant instant) {
        ZoneId zone;
        try { zone = ZoneId.of(store.snapshot().zoneId()); } catch (Exception e) { zone = ZoneId.systemDefault(); }
        return instant.atZone(zone);
    }
    /** Dominio principal y, si la regla incluye subdominios, los habituales y los propios del servicio. */
    static void addDomains(Set<String> blocked, WebRule rule) {
        blocked.add(rule.domain());
        if (!rule.subdomains()) return;
        for (String prefix : COMMON_SUBDOMAINS) blocked.add(prefix + "." + rule.domain());
        for (String prefix : KNOWN_SUBDOMAINS.getOrDefault(rule.domain(), List.of())) blocked.add(prefix + "." + rule.domain());
    }
    private static boolean active(WebRule r, ZonedDateTime now) {
        Instant i = now.toInstant();
        if (!r.permanent() && ((r.from() != null && i.isBefore(r.from())) || (r.until() != null && !i.isBefore(r.until())))) return false;
        if (r.schedules().isEmpty()) return "BLOQUEAR".equals(r.mode());
        boolean permit = r.schedules().stream().anyMatch(s -> "PERMITIR".equals(s.type()));
        boolean block = r.schedules().stream().anyMatch(s -> "BLOQUEAR".equals(s.type()) && contains(s, now));
        if (block) return true;
        if (permit) return r.schedules().stream().filter(s -> "PERMITIR".equals(s.type())).noneMatch(s -> contains(s, now));
        return false;
    }
    private static boolean contains(WebSchedule s, ZonedDateTime n) {
        if (!s.crossesMidnight()) return n.getDayOfWeek() == s.day() && !n.toLocalTime().isBefore(s.start()) && n.toLocalTime().isBefore(s.end());
        return (n.getDayOfWeek() == s.day() && !n.toLocalTime().isBefore(s.start()))
                || (n.getDayOfWeek() == s.day().plus(1) && n.toLocalTime().isBefore(s.end()));
    }
}
