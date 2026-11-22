package com.familyguard.agent;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsNativeDetectionTests {
    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    @Test
    void leeAplicacionesInstaladasDelRegistroReal() {
        assumeTrue(windows());
        var applications = new InstalledApplicationDetector().detect();
        assertFalse(applications.isEmpty());
        assertTrue(applications.stream().allMatch(app -> app.name() != null && !app.name().isBlank()));
    }

    @Test
    void enumeraProcesosRealesEIncluyeEsteProcesoJava() {
        assumeTrue(windows());
        var processes = new ProcessDetector().detect();
        assertFalse(processes.isEmpty());
        assertTrue(processes.stream().anyMatch(process -> process.pid() == ProcessHandle.current().pid()));
    }

    @Test
    void consultaVentanaActivaConLaApiNativaSinFallar() {
        assumeTrue(windows());
        var pid = new ForegroundWindowDetector().foregroundPid();
        assertTrue(pid.isEmpty() || pid.getAsLong() > 0);
    }

    @Test
    void identificaLaAplicacionActivaRealSinLeerContenido() {
        assumeTrue(windows());
        var observation = new WindowsActivityDetector().observe();
        assertNotNull(observation.observedAt());
        if (!observation.sessionLocked() && observation.application() != null) {
            assertTrue(observation.application().pid() > 0);
            assertFalse(observation.application().name().isBlank());
            System.out.println("Aplicacion activa comprobada: " + observation.application().name());
        }
    }

    @Test
    void instantaneaNoDuplicaPidsNiRutasConocidas() {
        assumeTrue(windows());
        var snapshot = new WindowsDetector().detect();
        assertFalse(snapshot.observedAt().isAfter(Instant.now()));
        assertEquals(snapshot.processes().size(), snapshot.processes().stream()
                .map(DetectionModels.RunningProcess::pid).distinct().count());
        var paths = snapshot.applications().stream().filter(app -> app.path() != null)
                .map(app -> app.path().toLowerCase(Locale.ROOT)).toList();
        assertEquals(paths.size(), paths.stream().distinct().count());
    }

    @Test
    void clienteReportaCaidaYSeRecuperaCuandoVuelveElBackend() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/agent/inventory", exchange -> {
            boolean first = calls.getAndIncrement() == 0;
            byte[] response = (first ? "{}" : "{\"estado\":\"CONECTADO\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(first ? 503 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var config = new AgentConfigStore.Config("http://127.0.0.1:" + server.getAddress().getPort(),
                    1, "test", "token", 15);
            var snapshot = new DetectionModels.DetectionSnapshot(List.of(), List.of(), Instant.now());
            org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                    () -> new AgentApiClient().sync(config, snapshot).join());
            new AgentApiClient().sync(config, snapshot).join();
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void impideDosInstanciasSimultaneasYLiberaElBloqueoAlCerrar(@TempDir java.nio.file.Path directory) throws Exception {
        // Bloqueo en una carpeta temporal: la prueba no depende de que FamilyGuard Agente esté abierto en este equipo
        // (el Agente real usa %LOCALAPPDATA%\FamilyGuard\Agent\agent.lock y lo mantiene mientras se ejecuta).
        java.nio.file.Path lockFile = directory.resolve("agent.lock");
        try (var first = SingleInstanceLock.acquire(lockFile)) {
            assertThrows(java.io.IOException.class, () -> SingleInstanceLock.acquire(lockFile));
        }
        try (var acquiredAgain = SingleInstanceLock.acquire(lockFile)) {
            assertNotNull(acquiredAgain);
        }
    }

    @Test
    void conservaLaUltimaInstantaneaParaOperacionSinConexion(@TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("inventory-cache.json");
        var snapshot = new DetectionModels.DetectionSnapshot(
                List.of(new DetectionModels.InstalledApplication("Editor", "1", "Proveedor",
                        "C:\\Apps\\editor.exe", "REGISTRY")),
                List.of(new DetectionModels.RunningProcess(42, "Editor", "C:\\Apps\\editor.exe",
                        true, Instant.now())), Instant.now());
        new LocalInventoryStore(file).save(snapshot);
        String json = Files.readString(file);
        assertTrue(json.contains("Editor"));
        assertTrue(json.contains("processes"));
    }

    @Test
    void cifraYRecuperaElDiarioDeUsoLocal(@TempDir java.nio.file.Path directory) throws Exception {
        assumeTrue(windows());
        var file = directory.resolve("usage-journal.bin");
        var journal = new UsageJournal(file);
        var tracker = new UsageTracker(journal, java.time.ZoneId.systemDefault());
        Instant start = Instant.now().minusSeconds(10);
        var app = new DetectionModels.ActiveApplication(ProcessHandle.current().pid(), "java.exe",
                ProcessHandle.current().info().command().orElse(null));
        tracker.observe(new DetectionModels.ActivityObservation(app, false, start));
        tracker.observe(new DetectionModels.ActivityObservation(null, true, start.plusSeconds(5)));
        assertFalse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("java.exe"));
        assertEquals(1, new UsageJournal(file).snapshot().pending().size());
    }
    @Test void credencialDispositivoSeProtegeConDpapi(@TempDir java.nio.file.Path directory)throws Exception{
        assumeTrue(windows());var file=directory.resolve("config.json");var store=new AgentConfigStore(file);var config=new AgentConfigStore.Config("https://familyguard.example",42,"device","token-muy-secreto",30);store.save(config);assertFalse(Files.readString(file).contains("token-muy-secreto"));assertEquals("token-muy-secreto",new AgentConfigStore(file).load().orElseThrow().agentToken());
    }
    @Test void rechazaHttpParaServidorNoLocal(){var config=new AgentConfigStore.Config("http://familyguard.example",1,"d","t",30);var snapshot=new DetectionModels.DetectionSnapshot(List.of(),List.of(),Instant.now());assertThrows(CompletionException.class,()->new AgentApiClient().sync(config,snapshot).join());}
}
