package com.familyguard.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Recuperación de archivos locales protegidos dañados (problema N.º 5). Un caché ilegible (corrupto, truncado o
 * cifrado con otra cuenta de Windows) ya no impide que el Agente arranque: se conserva aparte, nunca se borra, y
 * el Agente continúa con ese caché vacío hasta volver a descargar las reglas del servidor.
 */
final class DamagedFiles {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private DamagedFiles() { }

    /** Mueve el archivo a {@code <nombre>.danado-<fecha>} y devuelve un aviso breve y sin datos sensibles. */
    static String quarantine(Path file, String description) {
        Path target = file.resolveSibling(file.getFileName() + ".danado-" + STAMP.format(Instant.now()));
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception moveFailed) {
            // Si no se puede apartar, la próxima escritura atómica lo reemplazará igualmente.
            target = null;
        }
        return description + " estaba dañado y se reinició"
                + (target == null ? "." : "; copia conservada como " + target.getFileName() + ".");
    }
}
