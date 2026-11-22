package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Archivo local, protegido con DPAPI, con los registros de uso que el backend rechazó.
 * Sirve solo para diagnóstico: los registros ya no forman parte de la cola de envío.
 * Conserva como máximo {@value #MAX_RECORDS} entradas (las más recientes) y no se reenvía.
 */
final class RejectedUsageStore {
    static final int MAX_RECORDS = 500;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    private List<Entry> entries;

    RejectedUsageStore() { this(AgentPaths.userData("usage-rejected.bin")); }
    RejectedUsageStore(Path file) { this.file = file; this.entries = read(); }

    synchronized List<Entry> snapshot() { return List.copyOf(entries); }

    synchronized void record(List<UsageEvent> sent, List<AgentApiClient.RejectedUsage> rejected, Instant at) {
        if (rejected.isEmpty()) return;
        Map<UUID, UsageEvent> byId = new HashMap<>();
        sent.forEach(event -> byId.put(event.eventId(), event));
        Set<UUID> known = new HashSet<>();
        entries.forEach(entry -> known.add(entry.eventId()));
        List<Entry> updated = new ArrayList<>(entries);
        for (AgentApiClient.RejectedUsage item : rejected) {
            if (!known.add(item.eventId())) continue;
            updated.add(new Entry(item.eventId(), item.code(), item.reason(), at, byId.get(item.eventId())));
        }
        if (updated.size() > MAX_RECORDS) updated = updated.subList(updated.size() - MAX_RECORDS, updated.size());
        entries = List.copyOf(updated);
        write();
    }

    private List<Entry> read() {
        try {
            if (!Files.exists(file)) return List.of();
            Entry[] stored = mapper.readValue(Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)), Entry[].class);
            return stored == null ? List.of() : List.of(stored);
        } catch (Exception unreadable) {
            // Un archivo de diagnóstico ilegible no debe impedir que el agente funcione.
            try { Files.move(file, file.resolveSibling(file.getFileName() + ".ilegible"), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception ignored) { }
            return List.of();
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            byte[] encrypted = Crypt32Util.cryptProtectData(mapper.writeValueAsBytes(entries));
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, encrypted, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo guardar el registro de usos rechazados", exception);
        }
    }

    /** {@code event} puede ser nulo si el backend rechazó un UUID que ya no estaba en el lote local. */
    record Entry(UUID eventId, String code, String reason, Instant rejectedAt, UsageEvent event) { }
}
