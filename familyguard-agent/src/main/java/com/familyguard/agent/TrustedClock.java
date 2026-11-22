package com.familyguard.agent;

import java.time.Duration;
import java.time.Instant;

/**
 * Hora de confianza para decidir la vigencia de los permisos temporales.
 *
 * <p>{@code now()} es el máximo entre: el reloj de Windows, la hora de inicio del Agente más el tiempo monotónico
 * transcurrido (no le afecta cambiar la hora de Windows), la hora del servidor recibida más el tiempo monotónico
 * desde entonces y la última hora conocida guardada en disco. Atrasar el reloj no prolonga ningún permiso;
 * adelantarlo solo los acorta.</p>
 *
 * <p>Si al iniciar el reloj de Windows está más de 2 minutos por detrás de la última hora guardada (alguien lo
 * atrasó con el Agente cerrado), el reloj deja de ser de confianza hasta recibir la hora del servidor: mientras
 * tanto ningún permiso temporal se considera vigente y se mantienen las restricciones normales.</p>
 */
final class TrustedClock {
    static final Duration TOLERANCE = Duration.ofMinutes(2);

    interface Source { Instant system(); long nanos(); }
    static final Source SYSTEM = new Source() {
        public Instant system() { return Instant.now(); }
        public long nanos() { return System.nanoTime(); }
    };

    private final Source source;
    private final Instant startSystem;
    private final long startNanos;
    private final boolean startTrusted;
    private Instant highWater;
    private Instant serverAnchor;
    private long serverNanos;

    TrustedClock(Source source, Instant persistedHighWater) {
        this.source = source;
        this.startSystem = source.system();
        this.startNanos = source.nanos();
        this.startTrusted = persistedHighWater == null || !startSystem.isBefore(persistedHighWater.minus(TOLERANCE));
        this.highWater = persistedHighWater == null || persistedHighWater.isBefore(startSystem) ? startSystem : persistedHighWater;
    }

    /** Hora recibida del servidor (heartbeat o descarga de permisos). */
    synchronized void anchor(Instant serverNow) {
        if (serverNow == null) return;
        serverAnchor = serverNow;
        serverNanos = source.nanos();
        if (serverNow.isAfter(highWater)) highWater = serverNow;
    }

    synchronized Instant now() {
        long nanos = source.nanos();
        Instant best = source.system();
        Instant local = startSystem.plusNanos(nanos - startNanos);
        if (local.isAfter(best)) best = local;
        if (serverAnchor != null) {
            Instant server = serverAnchor.plusNanos(nanos - serverNanos);
            if (server.isAfter(best)) best = server;
        }
        if (highWater.isAfter(best)) best = highWater;
        highWater = best;
        return best;
    }

    /** Antigüedad máxima de la hora del servidor para usarla en los horarios (cubre suspensiones del equipo). */
    static final java.time.Duration SERVER_ANCHOR_VALIDITY = java.time.Duration.ofMinutes(10);

    /**
     * Hora para evaluar horarios de bloqueo. Con hora del servidor reciente se usa esa (más el tiempo monotónico):
     * cambiar la hora de Windows no adelanta ni retrasa el fin de un bloqueo. Sin servidor reciente (sin Internet
     * o tras suspender el equipo) se usa el reloj de Windows, nunca anterior a la última hora conocida.
     */
    synchronized Instant scheduleNow() {
        long nanos = source.nanos();
        if (serverAnchor != null && nanos - serverNanos <= SERVER_ANCHOR_VALIDITY.toNanos()) {
            Instant server = serverAnchor.plusNanos(nanos - serverNanos);
            if (server.isAfter(highWater)) highWater = server;
            return server;
        }
        Instant system = source.system();
        Instant best = system.isAfter(highWater) ? system : highWater;
        highWater = best;
        return best;
    }

    /** {@code false} si el reloj se atrasó con el Agente cerrado y aún no llegó la hora del servidor. */
    synchronized boolean trusted() { return startTrusted || serverAnchor != null; }

    synchronized boolean serverAnchored() { return serverAnchor != null; }

    /** Hora a guardar en disco para detectar retrocesos en el próximo inicio. */
    synchronized Instant highWater() { return highWater; }

    long monotonicNanos() { return source.nanos(); }
}
