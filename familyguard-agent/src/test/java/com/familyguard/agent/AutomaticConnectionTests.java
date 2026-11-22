package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Conexión automática: detección de red, reintentos controlados, reconexión y protección offline. */
class AutomaticConnectionTests {
    private static final Instant T0 = Instant.parse("2026-11-20T03:00:00Z");
    private static final String WIFI_ONLINE = "Wi-Fi|Internet|NoTraffic|Native 802.11\n";
    private static final String CABLE_ONLINE = "Ethernet|Internet|Internet|802.3\n";
    private static final String WIFI_LOCAL = "Wi-Fi|LocalNetwork|NoTraffic|Native 802.11\n";
    private static final String REMOTE = "https://familyguard.example.org";   // solo para la prueba; no se contacta

    private static NetworkMonitor.Snapshot snap(String windows, String interfaces) {
        return NetworkMonitor.evaluate(windows, interfaces, T0);
    }

    @Test
    void interpretaLaConectividadQueWindowsYaEvaluo() {
        var wifi = snap(WIFI_ONLINE, "wlan0=192.168.1.20");
        assertEquals(NetworkMonitor.Internet.ONLINE, wifi.internet());
        assertEquals("Wi-Fi", wifi.medium());
        assertEquals("Ethernet", snap(CABLE_ONLINE, "eth0=10.0.0.5").medium());
        assertEquals(NetworkMonitor.Internet.LOCAL_ONLY, snap(WIFI_LOCAL, "wlan0=192.168.1.20").internet());
        assertEquals(NetworkMonitor.Internet.OFFLINE, snap("", "").internet());
        // Sin PowerShell (u otro sistema): se sabe que hay red, pero no si hay Internet.
        assertEquals(NetworkMonitor.Internet.UNKNOWN, snap(null, "eth0=10.0.0.5").internet());
        assertEquals(NetworkMonitor.Internet.OFFLINE, snap(null, "").internet());
        // Con dos perfiles, manda el que tiene Internet.
        assertEquals(NetworkMonitor.Internet.ONLINE, snap(WIFI_LOCAL + CABLE_ONLINE, "x").internet());
    }

    @Test
    void elMonitorSoloConsultaAWindowsCuandoCambiaLaRedOCadaMinuto() {
        AtomicReference<String> interfaces = new AtomicReference<>("wlan0=192.168.1.20");
        AtomicReference<String> windows = new AtomicReference<>(WIFI_ONLINE);
        int[] probes = {0};
        NetworkMonitor monitor = new NetworkMonitor(interfaces::get, () -> { probes[0]++; return windows.get(); });
        assertNotNull(monitor.poll(T0));                                   // primera evaluación
        assertNull(monitor.poll(T0.plusSeconds(5)));                       // nada cambió: sin consulta
        assertEquals(1, probes[0]);
        // Pérdida de Internet (se cae la Wi-Fi).
        interfaces.set(""); windows.set("");
        assertEquals(NetworkMonitor.Internet.OFFLINE, monitor.poll(T0.plusSeconds(10)).internet());
        // Otra red Wi-Fi: nueva dirección local.
        interfaces.set("wlan0=10.20.30.40"); windows.set(WIFI_ONLINE);
        var other = monitor.poll(T0.plusSeconds(15));
        assertEquals(NetworkMonitor.Internet.ONLINE, other.internet());
        assertEquals(3, probes[0]);
        assertNull(monitor.poll(T0.plusSeconds(40)));
        monitor.poll(T0.plusSeconds(80));                                  // repaso cada 60 s
        assertEquals(4, probes[0]);
    }

    @Test
    void backendInaccesibleUsaReintentosControlados() {
        ConnectionSupervisor s = new ConnectionSupervisor();
        var online = snap(WIFI_ONLINE, "a");
        assertEquals(ConnectionSupervisor.Skip.NONE, s.check(T0, online, false));
        s.failure(T0, false);
        assertEquals(ConnectionSupervisor.Skip.WAITING_RETRY, s.check(T0.plusMillis(500), online, false));
        Instant t = T0;
        for (int i = 0; i < 12; i++) s.failure(t, false);
        // La espera crece pero nunca supera 5 minutos: no hay bucles de solicitudes.
        assertTrue(Duration.between(t, s.view().nextAttempt()).compareTo(Duration.ofMinutes(5)) <= 0);
        assertTrue(Duration.between(t, s.view().nextAttempt()).compareTo(Duration.ofMinutes(4)) >= 0);
        assertEquals(13, s.view().consecutiveFailures());
        assertFalse(s.view().authorizationRequired());   // un servidor caído nunca es una revocación
    }

    @Test
    void sinInternetNoSeEnvianSolicitudesAlServidorRemotoPeroSiAlLocal() {
        ConnectionSupervisor s = new ConnectionSupervisor();
        var offline = snap("", "");
        assertEquals(ConnectionSupervisor.Skip.NO_INTERNET, s.check(T0, offline, false));
        assertEquals(ConnectionSupervisor.Skip.NONE, s.check(T0, offline, true));   // localhost no necesita Internet
        assertTrue(AgentRuntime.localServer("http://localhost:8080"));
        assertFalse(AgentRuntime.localServer(REMOTE));
    }

    @Test
    void alRecuperarInternetOCambiarDeWifiSeReconectaDeInmediato() {
        ConnectionSupervisor s = new ConnectionSupervisor();
        var wifiA = snap(WIFI_ONLINE, "wlan0=192.168.1.20");
        for (int i = 0; i < 6; i++) s.failure(T0, false);                  // el servidor no respondía
        assertEquals(ConnectionSupervisor.Skip.WAITING_RETRY, s.check(T0.plusSeconds(1), wifiA, false));
        var offline = snap("", "");
        assertFalse(s.networkChanged(T0.plusSeconds(2), wifiA, offline)); // perder la red no fuerza nada
        // Recuperación: se intenta ya, sin esperar el reintento de varios minutos.
        assertTrue(s.networkChanged(T0.plusSeconds(30), offline, wifiA));
        assertEquals(ConnectionSupervisor.Skip.NONE, s.check(T0.plusSeconds(30), wifiA, false));
        s.success(T0.plusSeconds(31));
        // Cambio de Wi-Fi (otra dirección local): nueva sincronización, sin pedir otra IP del servidor.
        var wifiB = snap(WIFI_ONLINE, "wlan0=10.20.30.40");
        assertTrue(s.networkChanged(T0.plusSeconds(60), wifiA, wifiB));
        // Como mucho una reconexión forzada cada 10 s.
        assertFalse(s.networkChanged(T0.plusSeconds(62), wifiB, wifiA));
        assertEquals(T0.plusSeconds(31), s.view().lastSuccess());
        assertEquals(0, s.view().consecutiveFailures());
    }

    @Test
    void revocacionConfirmadaNoSeConfundeConFaltaDeRed() {
        ConnectionSupervisor s = new ConnectionSupervisor();
        s.failure(T0, true);
        var online = snap(WIFI_ONLINE, "a");
        assertEquals(ConnectionSupervisor.Skip.AUTHORIZATION, s.check(T0.plusSeconds(5), online, false));
        assertFalse(s.networkChanged(T0.plusSeconds(20), snap("", ""), online));   // no insiste con credenciales revocadas
        assertEquals(ConnectionSupervisor.Skip.NONE, s.check(T0.plus(ConnectionSupervisor.AUTHORIZATION_WAIT), online, false));
    }

    @Test
    void textosNoMuestranConectadoSoloPorTenerWifi() {
        var online = snap(WIFI_ONLINE, "a");
        var sup = new ConnectionSupervisor();
        sup.check(T0, online, false); sup.failure(T0, false);
        var noServer = new AgentRuntime.ConnectionInfo(true, false, REMOTE, online, sup.view(), 3, 2, 4);
        assertEquals("Con Internet", ConnectionTexts.internet(online).text());
        assertEquals("El servidor no responde — Protección offline", ConnectionTexts.server(noServer, T0).text());
        assertEquals("Protección offline activa: se aplican 3 reglas de aplicaciones y 2 reglas web guardadas, con sus fechas y horarios",
                ConnectionTexts.offline(noServer).text());
        assertEquals("4 eventos se enviarán al reconectar", ConnectionTexts.pending(4));
        assertEquals("1 intento fallido · próximo intento en 2 s", ConnectionTexts.retries(sup.view(), T0));
        var offline = new AgentRuntime.ConnectionInfo(true, false, REMOTE, snap("", ""), sup.view(), 3, 2, 0);
        assertEquals("Sin conexión — Protección offline", ConnectionTexts.server(offline, T0).text());
        sup.success(T0);
        var ok = new AgentRuntime.ConnectionInfo(true, true, REMOTE, online, sup.view(), 3, 2, 0);
        assertEquals("Conectado y autorizado", ConnectionTexts.server(ok, T0).text());
        assertEquals("20/11/2026 03:00 (hace 5 min)", ConnectionTexts.lastSync(T0, ZoneId.of("UTC"), T0.plusSeconds(300)));
        assertEquals("Desarrollo local: solo funciona en este equipo", ConnectionTexts.serverKind("http://localhost:8080").text());
        assertEquals("Servidor remoto HTTPS (certificado validado)", ConnectionTexts.serverKind(REMOTE).text());
    }

    @Test
    void internetSinServidorRemotoConfiguradoUsaLocalSoloParaDesarrollo() {
        // Sin variable, propiedad ni server.properties: se usa el servidor local de desarrollo, nunca uno inventado.
        assertEquals("http://localhost:8080", AgentServerSettings.resolve(null, null, ""));
        assertEquals(REMOTE, AgentServerSettings.resolve(REMOTE, null, ""));
        // HTTP sin cifrar hacia Internet se rechaza: se ignora y se mantiene el servidor local.
        assertEquals("http://localhost:8080", AgentServerSettings.resolve("http://familyguard.example.org", null, null));
    }
}
