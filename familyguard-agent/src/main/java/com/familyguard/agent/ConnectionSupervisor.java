package com.familyguard.agent;

import java.time.Duration;
import java.time.Instant;

/**
 * Cuándo intentar comunicarse con el servidor FamilyGuard. Reintentos controlados (espera creciente de hasta 5 min);
 * al recuperar la red o cambiar de Wi-Fi se intenta de inmediato, como mucho una vez cada 10 s.
 * Sin Internet no se envían solicitudes a un servidor remoto (sí al servidor local de desarrollo).
 * Una falta de red nunca se trata como revocación: la revocación solo la indica una respuesta del servidor.
 */
final class ConnectionSupervisor {
    static final Duration AUTHORIZATION_WAIT = Duration.ofMinutes(15);
    static final Duration MIN_FORCED_GAP = Duration.ofSeconds(10);

    enum Skip { NONE, WAITING_RETRY, NO_INTERNET, AUTHORIZATION }

    private final RetryBackoff backoff;
    private int failures;
    private long totalRetries;
    private Instant nextAttempt = Instant.EPOCH;
    private Instant lastAttempt, lastSuccess, lastForced = Instant.EPOCH;
    private boolean authorizationRequired;
    private boolean everConnected;

    ConnectionSupervisor() { this(new RetryBackoff()); }
    ConnectionSupervisor(RetryBackoff backoff) { this.backoff = backoff; }

    synchronized Skip check(Instant now, NetworkMonitor.Snapshot network, boolean localServer) {
        if (authorizationRequired && now.isBefore(nextAttempt)) return Skip.AUTHORIZATION;
        if (!localServer && network != null && network.internet() == NetworkMonitor.Internet.OFFLINE) return Skip.NO_INTERNET;
        if (now.isBefore(nextAttempt)) return Skip.WAITING_RETRY;
        lastAttempt = now;
        return Skip.NONE;
    }

    synchronized void success(Instant now) {
        failures = 0; nextAttempt = Instant.EPOCH; authorizationRequired = false; lastSuccess = now; everConnected = true;
    }

    synchronized void failure(Instant now, boolean authorization) {
        failures = Math.min(20, failures + 1); totalRetries++;
        authorizationRequired = authorization;
        nextAttempt = now.plus(authorization ? AUTHORIZATION_WAIT : backoff.delay(failures));
    }

    /**
     * Cambio de red (recuperó Internet, otra Wi-Fi, otro cable): si hay red utilizable, se adelanta el siguiente intento.
     * @return {@code true} si conviene sincronizar ahora.
     */
    synchronized boolean networkChanged(Instant now, NetworkMonitor.Snapshot before, NetworkMonitor.Snapshot after) {
        if (after == null || !after.usable() || authorizationRequired) return false;
        boolean recovered = before == null || !before.usable() || before.internet() == NetworkMonitor.Internet.CHECKING;
        boolean otherNetwork = before != null && !before.fingerprint().equals(after.fingerprint());
        boolean betterReach = before != null && after.internet().ordinal() < before.internet().ordinal();
        if (!recovered && !otherNetwork && !betterReach) return false;
        if (Duration.between(lastForced, now).compareTo(MIN_FORCED_GAP) < 0) return false;
        lastForced = now; nextAttempt = Instant.EPOCH;
        return true;
    }

    /** Aviso del servidor (tiempo real): adelanta el siguiente intento salvo que falte una nueva vinculación. */
    synchronized void wakeUp() { if (!authorizationRequired) nextAttempt = Instant.EPOCH; }

    /** Restaura la última sincronización correcta guardada (por ejemplo, tras reiniciar el equipo). */
    synchronized void restoreLastSuccess(Instant value) { if (value != null && (lastSuccess == null || value.isAfter(lastSuccess))) lastSuccess = value; }

    synchronized View view() { return new View(failures, totalRetries, nextAttempt.equals(Instant.EPOCH) ? null : nextAttempt, lastAttempt, lastSuccess, authorizationRequired, everConnected); }

    /** Estado para la pantalla «Conexión». */
    record View(int consecutiveFailures, long totalRetries, Instant nextAttempt, Instant lastAttempt, Instant lastSuccess,
                boolean authorizationRequired, boolean connectedThisSession) { }
}
