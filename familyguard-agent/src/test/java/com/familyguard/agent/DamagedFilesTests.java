package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Problema N.º 5: un archivo dañado se aparta (nunca se borra) y se informa sin datos sensibles. */
class DamagedFilesTests {
    @TempDir Path temp;

    @Test void apartaElArchivoDanadoConservandoSuContenido() throws Exception {
        Path file = temp.resolve("restrictions.bin");
        Files.write(file, new byte[] {1, 2, 3, 4});
        String notice = DamagedFiles.quarantine(file, "El caché de reglas de aplicaciones");
        assertFalse(Files.exists(file));
        List<Path> copias;
        try (Stream<Path> files = Files.list(temp)) { copias = files.toList(); }
        assertEquals(1, copias.size());
        assertTrue(copias.get(0).getFileName().toString().startsWith("restrictions.bin.danado-"));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(copias.get(0)));
        assertTrue(notice.startsWith("El caché de reglas de aplicaciones estaba dañado y se reinició"));
        assertTrue(notice.contains(copias.get(0).getFileName().toString()));
    }

    @Test void siNoPuedeApartarloIgualmenteDevuelveUnAviso() {
        String notice = DamagedFiles.quarantine(temp.resolve("no-existe.bin"), "El diario de tiempo de uso");
        assertEquals("El diario de tiempo de uso estaba dañado y se reinició.", notice);
    }
}
