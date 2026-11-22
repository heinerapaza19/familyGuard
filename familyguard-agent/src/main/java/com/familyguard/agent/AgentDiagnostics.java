package com.familyguard.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Registro local limitado para recuperación; nunca incluye token ni contenido personal. */
final class AgentDiagnostics {
    private static final long MAX_BYTES = 512 * 1024;
    private final Path file = AgentPaths.userData("agent-errors.log");
    synchronized void record(String area, Throwable error) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_BYTES)
                Files.move(file, file.resolveSibling("agent-errors.previous.log"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            Files.writeString(file, Instant.now() + " [" + area + "] " + sanitize(message) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }
    private static String sanitize(String value) {
        return value.replaceAll("(?i)(token|authorization|password)\\s*[:=]\\s*[^\\s,;]+", "$1=[PROTEGIDO]")
                .replace('\r', ' ').replace('\n', ' ');
    }
}
