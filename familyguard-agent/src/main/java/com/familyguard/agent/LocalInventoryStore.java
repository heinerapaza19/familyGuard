package com.familyguard.agent;

import java.nio.file.*;
import tools.jackson.databind.ObjectMapper;

/** Conserva solo la ultima instantanea local para continuidad sin red. */
final class LocalInventoryStore {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    LocalInventoryStore() {
        this(AgentPaths.userData("inventory-cache.json"));
    }

    LocalInventoryStore(Path file) { this.file = file; }

    void save(DetectionModels.DetectionSnapshot snapshot) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, mapper.writeValueAsString(snapshot), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo conservar el inventario local", exception);
        }
    }
}
