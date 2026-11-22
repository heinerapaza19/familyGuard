package com.familyguard.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Caché DPAPI de permisos temporales: lista descargada, presupuesto restante de cada permiso, última hora de
 * confianza y eventos pendientes de informar. Si el archivo está dañado se aparta y se empieza vacío: sin
 * permisos, es decir, con las restricciones normales.
 */
final class TemporaryPermissionStore {
    record PermissionEvent(UUID localId, long rowId, String state, String detail, Instant occurredAt) { }
    record State(List<TemporaryPermissions.Grant> grants, Map<Long, Long> budgets, Instant highWater,
                 Map<Long, String> reported, List<PermissionEvent> events) {
        State {
            grants = grants == null ? List.of() : List.copyOf(grants);
            budgets = budgets == null ? Map.of() : Map.copyOf(budgets);
            reported = reported == null ? Map.of() : Map.copyOf(reported);
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtectedFile file;
    private State state;
    private String recovery;

    TemporaryPermissionStore() { this(new ProtectedFile(AgentPaths.userData("permissions.bin"), ProtectedFile.DPAPI)); }
    TemporaryPermissionStore(ProtectedFile file) { this.file = file; this.state = read(); }

    synchronized State snapshot() { return state; }
    synchronized void save(State next) { state = next; file.write(mapper.writeValueAsBytes(state)); }
    /** Actualiza el estado sin escribir el archivo (se guarda en la siguiente escritura). */
    synchronized void saveInMemory(State next) { state = next; }

    synchronized void acknowledge(Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        Set<UUID> done = Set.copyOf(ids);
        save(new State(state.grants(), state.budgets(), state.highWater(), state.reported(),
                state.events().stream().filter(e -> !done.contains(e.localId())).toList()));
    }

    String recoveryNotice() { return recovery; }

    private State read() {
        try {
            if (!file.exists()) return new State(List.of(), Map.of(), null, Map.of(), List.of());
            return mapper.readValue(file.read(), State.class);
        } catch (Exception e) {
            recovery = DamagedFiles.quarantine(file.path(), "El caché de permisos temporales");
            return new State(List.of(), Map.of(), null, Map.of(), List.of());
        }
    }

    static Map<Long, Long> mutable(Map<Long, Long> m) { return new HashMap<>(m); }
    static <T> List<T> mutable(List<T> l) { return new ArrayList<>(l); }
}
