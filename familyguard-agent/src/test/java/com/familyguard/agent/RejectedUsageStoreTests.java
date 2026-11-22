package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.familyguard.agent.DetectionModels.UsageEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Usa DPAPI real, por eso solo se ejecuta en Windows. */
@EnabledOnOs(OS.WINDOWS)
class RejectedUsageStoreTests {
    @TempDir Path temp;

    @Test void conservaRechazadosCifradosYSinDuplicados() throws Exception {
        Path file = temp.resolve("usage-rejected.bin");
        UsageEvent event = event();
        var rejection = new AgentApiClient.RejectedUsage(event.eventId(), "INTERVALO_FUTURO", "Revise el reloj");
        var store = new RejectedUsageStore(file);
        store.record(List.of(event), List.of(rejection), Instant.now());
        store.record(List.of(event), List.of(rejection), Instant.now());

        var reloaded = new RejectedUsageStore(file).snapshot();
        assertEquals(1, reloaded.size());
        assertEquals("INTERVALO_FUTURO", reloaded.get(0).code());
        assertEquals(event, reloaded.get(0).event());
        assertFalse(Files.readString(file, java.nio.charset.StandardCharsets.ISO_8859_1).contains("chrome.exe"),
                "el archivo debe estar cifrado con DPAPI");
    }

    @Test void limitaElTamanoConservandoLosMasRecientes() {
        var store = new RejectedUsageStore(temp.resolve("usage-rejected.bin"));
        List<AgentApiClient.RejectedUsage> rejected = new ArrayList<>();
        for (int i = 0; i < RejectedUsageStore.MAX_RECORDS + 20; i++)
            rejected.add(new AgentApiClient.RejectedUsage(UUID.randomUUID(), "PID_INVALIDO", "x"));
        store.record(List.of(), rejected, Instant.now());
        var saved = store.snapshot();
        assertEquals(RejectedUsageStore.MAX_RECORDS, saved.size());
        assertEquals(rejected.get(rejected.size() - 1).eventId(), saved.get(saved.size() - 1).eventId());
    }

    @Test void unArchivoIlegibleNoImpideIniciar() throws Exception {
        Path file = temp.resolve("usage-rejected.bin");
        Files.write(file, new byte[] {1, 2, 3});
        assertTrue(new RejectedUsageStore(file).snapshot().isEmpty());
        assertTrue(Files.exists(temp.resolve("usage-rejected.bin.ilegible")));
    }

    private static UsageEvent event() {
        Instant end = Instant.parse("2026-11-18T15:00:00Z");
        return new UsageEvent(UUID.randomUUID(), "chrome.exe", "C:\\Chrome\\chrome.exe", 10,
                end.minusSeconds(30), end, 30);
    }
}
