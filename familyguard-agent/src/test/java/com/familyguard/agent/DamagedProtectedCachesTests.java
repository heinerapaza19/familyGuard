package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Problema N.º 5 con DPAPI real (solo Windows): los cachés cifrados dañados ya no impiden crear el Agente.
 * Cada uno arranca vacío, conserva la copia dañada, informa el aviso y vuelve a guardar y leer con normalidad.
 */
@EnabledOnOs(OS.WINDOWS)
class DamagedProtectedCachesTests {
    @TempDir Path temp;
    private static final byte[] BASURA = "no es un archivo DPAPI válido".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private long copiasDanadas(String nombre) throws Exception {
        try (Stream<Path> files = Files.list(temp)) {
            return files.filter(p -> p.getFileName().toString().startsWith(nombre + ".danado-")).count();
        }
    }

    @Test void cacheDeReglasDeAplicacionesDanado() throws Exception {
        Path file = temp.resolve("restrictions.bin");
        Files.write(file, BASURA);
        RestrictionStore store = assertDoesNotThrow(() -> new RestrictionStore(file));
        assertNotNull(store.recoveryNotice());
        assertTrue(store.snapshot().rules().isEmpty());
        assertEquals(1, copiasDanadas("restrictions.bin"));
        assertNull(new RestrictionStore(file).recoveryNotice(), "sin archivo dañado no hay aviso");
    }

    @Test void cacheDeReglasWebDanadoSeRecuperaYVuelveAGuardar() throws Exception {
        Path file = temp.resolve("web-rules.bin");
        Files.write(file, BASURA);
        WebRestrictionStore store = assertDoesNotThrow(() -> new WebRestrictionStore(file));
        assertNotNull(store.recoveryNotice());
        assertTrue(store.snapshot().rules().isEmpty());
        assertEquals(1, copiasDanadas("web-rules.bin"));
        assertTrue(store.rules("America/Lima", List.of(new DetectionModels.WebRule(1, "tiktok.com", true, "BLOQUEAR",
                true, null, null, 2, List.of()))));
        WebRestrictionStore reabierto = new WebRestrictionStore(file);
        assertNull(reabierto.recoveryNotice());
        assertEquals(1, reabierto.snapshot().rules().size());
    }

    @Test void diarioDeUsoDanadoNoImpideArrancar() throws Exception {
        Path file = temp.resolve("usage-journal.bin");
        Files.write(file, BASURA);
        UsageJournal journal = assertDoesNotThrow(() -> new UsageJournal(file));
        assertNotNull(journal.recoveryNotice());
        assertTrue(journal.snapshot().pending().isEmpty());
        journal.persist(null, List.of(new DetectionModels.UsageEvent(java.util.UUID.randomUUID(), "chrome.exe",
                "C:\\Chrome\\chrome.exe", 10, Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), 30)));
        assertEquals(1, new UsageJournal(file).snapshot().pending().size());
        assertEquals(1, copiasDanadas("usage-journal.bin"));
    }
}
