package com.familyguard.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Permisos temporales vigentes en un instante. Inmutable y sin efectos: lo usan las reglas de aplicaciones y
 * de sitios web. Un permiso solo está vigente si el reloj es de confianza, la hora está dentro de su ventana
 * absoluta y le queda presupuesto monotónico (un reinicio no lo renueva).
 */
final class TemporaryPermissions {
    static final String EXTRA = "TIEMPO_EXTRA", OUTSIDE = "FUERA_HORARIO", UNBLOCK = "DESBLOQUEO_TEMPORAL", SITE = "PERMITIR_SITIO";
    static final int MAX_EXTRA_MINUTES = 240;
    static final TemporaryPermissions NONE = new TemporaryPermissions(List.of(), Instant.EPOCH);

    /** Permiso tal como lo envía el servidor para este equipo. */
    record Grant(long rowId, long permissionId, String type, Long applicationId, String path, Long siteId,
                 String domain, Integer extraMinutes, Instant startsAt, Instant expiresAt) { }

    private final List<Grant> active;
    private final Instant now;

    private TemporaryPermissions(List<Grant> active, Instant now) { this.active = List.copyOf(active); this.now = now; }

    static TemporaryPermissions evaluate(List<Grant> grants, Map<Long, Long> budgetMillis, Instant now, boolean trusted) {
        if (!trusted || grants == null || grants.isEmpty()) return new TemporaryPermissions(List.of(), now);
        List<Grant> active = new ArrayList<>();
        for (Grant g : grants) {
            if (g == null || g.type() == null || g.startsAt() == null || g.expiresAt() == null) continue;
            if (now.isBefore(g.startsAt()) || !now.isBefore(g.expiresAt())) continue;
            Long budget = budgetMillis == null ? null : budgetMillis.get(g.rowId());
            if (budget != null && budget <= 0) continue;
            active.add(g);
        }
        return new TemporaryPermissions(active, now);
    }

    List<Grant> active() { return active; }
    boolean isEmpty() { return active.isEmpty(); }

    /** Minutos extra de hoy para la aplicación, como máximo 4 horas aunque haya varios permisos. */
    long extraSeconds(long applicationId) {
        long minutes = active.stream().filter(g -> EXTRA.equals(g.type()) && sameApp(g, applicationId))
                .mapToLong(g -> g.extraMinutes() == null ? 0 : Math.max(0, g.extraMinutes())).sum();
        return Math.min(minutes, MAX_EXTRA_MINUTES) * 60L;
    }

    boolean outsideSchedule(long applicationId) { return has(OUTSIDE, applicationId) || has(UNBLOCK, applicationId); }
    boolean unblocked(long applicationId) { return has(UNBLOCK, applicationId); }
    boolean siteAllowed(long siteId) {
        return active.stream().anyMatch(g -> SITE.equals(g.type()) && g.siteId() != null && g.siteId() == siteId);
    }

    /** Fin del permiso que hoy deja usar la aplicación fuera de su horario o bloqueo (el más tardío). */
    Optional<Instant> endOfAccess(long applicationId) {
        return active.stream().filter(g -> (OUTSIDE.equals(g.type()) || UNBLOCK.equals(g.type())) && sameApp(g, applicationId))
                .map(Grant::expiresAt).max(Instant::compareTo);
    }

    long secondsUntil(Instant end) { return Math.max(0, Duration.between(now, end).getSeconds()); }

    private boolean has(String type, long applicationId) {
        return active.stream().anyMatch(g -> type.equals(g.type()) && sameApp(g, applicationId));
    }
    private static boolean sameApp(Grant g, long applicationId) { return g.applicationId() != null && g.applicationId() == applicationId; }
}
