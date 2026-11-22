package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Cola local cifrada con DPAPI y reemplazo atomico. */
final class UsageJournal implements UsagePersistence {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    private UsageState data;
    private String recovery;

    UsageJournal() {
        this(AgentPaths.userData("usage-journal.bin"));
    }
    UsageJournal(Path file) {
        this.file = file;
        this.data = read();
    }

    public synchronized UsageState snapshot() {
        return new UsageState(data.current(), List.copyOf(data.pending()));
    }
    public synchronized void persist(OpenUsage current, List<UsageEvent> pending) {
        data = new UsageState(current, List.copyOf(pending));
        write(data);
    }

    private UsageState read() {
        try {
            if (!Files.exists(file)) return new UsageState(null, List.of());
            byte[] json = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file));
            UsageState loaded = mapper.readValue(json, UsageState.class);
            return new UsageState(loaded.current(), loaded.pending() == null ? List.of() : loaded.pending());
        } catch (Exception exception) {
            // Problema N.º 5: un diario ilegible ya no impide arrancar; los registros dañados no se pueden recuperar.
            recovery = DamagedFiles.quarantine(file, "El diario de tiempo de uso");
            return new UsageState(null, List.of());
        }
    }

    /** Aviso si el diario estaba dañado al iniciar; {@code null} si se leyó bien. */
    String recoveryNotice() { return recovery; }
    private void write(UsageState value) {
        try {
            Files.createDirectories(file.getParent());
            byte[] encrypted = Crypt32Util.cryptProtectData(mapper.writeValueAsBytes(value));
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, encrypted, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo guardar el diario local protegido", exception);
        }
    }
}
