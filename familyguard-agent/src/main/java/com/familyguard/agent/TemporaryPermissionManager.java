package com.familyguard.agent;

import static com.familyguard.agent.TemporaryPermissionStore.PermissionEvent;
import static com.familyguard.agent.TemporaryPermissionStore.State;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mantiene los permisos temporales del equipo.
 *
 * <ul>
 *   <li>La lista del servidor manda: lo que deja de venir (cancelado o vencido) se retira al instante.</li>
 *   <li>Sin conexión se usa la última lista, pero cada permiso vence a su hora y además consume un presupuesto
 *   medido con tiempo monotónico, guardado en disco: reiniciar o desconectarse nunca lo prolonga.</li>
 *   <li>Si el reloj no es de confianza, ningún permiso está vigente (restricciones normales).</li>
 *   <li>Informa al servidor APLICADO, FINALIZADO o ERROR una sola vez por cambio.</li>
 * </ul>
 */
final class TemporaryPermissionManager {
    static final String APPLIED = "APLICADO", FINISHED = "FINALIZADO", ERROR = "ERROR";
    private static final long SAVE_EVERY_NANOS = Duration.ofSeconds(30).toNanos();

    private final TemporaryPermissionStore store;
    private final TrustedClock clock;
    private long lastTickNanos, lastSaveNanos;
    private volatile TemporaryPermissions current = TemporaryPermissions.NONE;

    TemporaryPermissionManager(TemporaryPermissionStore store, TrustedClock.Source source) {
        this.store = store;
        this.clock = new TrustedClock(source, store.snapshot().highWater());
        this.lastTickNanos = source.nanos();
        this.lastSaveNanos = lastTickNanos;
    }

    TrustedClock clock() { return clock; }
    TemporaryPermissions current() { return current; }

    /** Hora del servidor recibida en un heartbeat. */
    void anchor(Instant serverNow) { clock.anchor(serverNow); }

    /** Descarga autenticada del servidor: reemplaza la lista completa. */
    synchronized TemporaryPermissions replace(Instant serverNow, List<TemporaryPermissions.Grant> grants) {
        clock.anchor(serverNow);
        Instant now = clock.now();
        State old = store.snapshot();
        Map<Long, Long> budgets = new HashMap<>();
        for (TemporaryPermissions.Grant g : grants) {
            long remaining = Math.max(0, Duration.between(now.isAfter(g.startsAt()) ? now : g.startsAt(), g.expiresAt()).toMillis());
            Long previous = old.budgets().get(g.rowId());
            budgets.put(g.rowId(), previous == null ? remaining : Math.min(previous, remaining));
        }
        // Lo que estaba aplicado y ya no viene del servidor se informa como finalizado.
        Set<Long> nuevos = new HashSet<>();
        grants.forEach(g -> nuevos.add(g.rowId()));
        Map<Long, String> reported = new HashMap<>(old.reported());
        List<PermissionEvent> events = new ArrayList<>(old.events());
        for (TemporaryPermissions.Grant g : old.grants()) {
            if (nuevos.contains(g.rowId())) continue;
            if (APPLIED.equals(reported.get(g.rowId()))) events.add(event(g.rowId(), FINISHED, "Retirado del equipo", now));
            reported.remove(g.rowId());
        }
        store.save(new State(grants, budgets, clock.highWater(), reported, events));
        lastSaveNanos = clock.monotonicNanos();
        return evaluate(true);
    }

    /** Se llama cada pocos segundos: descuenta presupuesto, recalcula y registra cambios de estado. */
    synchronized TemporaryPermissions tick() { return evaluate(false); }

    private TemporaryPermissions evaluate(boolean forceSave) {
        long nanos = clock.monotonicNanos();
        long elapsedMillis = Math.max(0, nanos - lastTickNanos) / 1_000_000;
        lastTickNanos = nanos;
        Instant now = clock.now();
        boolean trusted = clock.trusted();
        State s = store.snapshot();
        Map<Long, Long> budgets = new HashMap<>(s.budgets());
        boolean changed = forceSave;
        for (TemporaryPermissions.Grant g : s.grants()) {
            Long budget = budgets.get(g.rowId());
            if (budget == null) { budgets.put(g.rowId(), Math.max(0, Duration.between(now, g.expiresAt()).toMillis())); changed = true; continue; }
            if (elapsedMillis > 0 && !now.isBefore(g.startsAt()) && budget > 0) budgets.put(g.rowId(), Math.max(0, budget - elapsedMillis));
        }
        TemporaryPermissions view = TemporaryPermissions.evaluate(s.grants(), budgets, now, trusted);
        Set<Long> active = new HashSet<>();
        view.active().forEach(g -> active.add(g.rowId()));
        Map<Long, String> reported = new HashMap<>(s.reported());
        List<PermissionEvent> events = new ArrayList<>(s.events());
        for (TemporaryPermissions.Grant g : s.grants()) {
            String previous = reported.get(g.rowId());
            if (FINISHED.equals(previous)) continue;
            String next = null, detail = null;
            if (active.contains(g.rowId())) { next = APPLIED; detail = "Permiso activo en el equipo"; }
            else if (!now.isBefore(g.expiresAt()) || budgets.getOrDefault(g.rowId(), 1L) <= 0) { next = FINISHED; detail = "El permiso terminó"; }
            else if (!trusted && !now.isBefore(g.startsAt())) { next = ERROR; detail = "Reloj de Windows atrasado: el permiso queda en pausa hasta conectar con el servidor"; }
            if (next != null && !next.equals(previous)) {
                reported.put(g.rowId(), next);
                events.add(event(g.rowId(), next, detail, now));
                changed = true;
            }
        }
        if (changed || nanos - lastSaveNanos >= SAVE_EVERY_NANOS) {
            store.save(new State(s.grants(), budgets, clock.highWater(), reported, events));
            lastSaveNanos = nanos;
        } else {
            // Sin escribir en disco en cada ciclo: el presupuesto en memoria sigue descontando.
            store.saveInMemory(new State(s.grants(), budgets, s.highWater(), reported, events));
        }
        current = view;
        return view;
    }

    List<PermissionEvent> pendingEvents() { return store.snapshot().events(); }
    void acknowledge(Collection<UUID> ids) { store.acknowledge(ids); }

    private static PermissionEvent event(long row, String state, String detail, Instant at) {
        return new PermissionEvent(UUID.randomUUID(), row, state, detail, at);
    }
}
