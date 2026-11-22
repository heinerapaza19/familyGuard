package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Bloqueo web «Pendiente» que nunca se confirmaba: inventario rechazado que cortaba la descarga de reglas,
 * confirmación sin comprobar Windows y subdominios sin cubrir.
 */
class WebBlockingVerificationTests {
    private static final Instant T0 = Instant.parse("2026-11-19T20:00:00Z");

    @Test
    void elInventarioRespetaLosLimitesDelServidor() {
        String largo = "A".repeat(400);
        var apps = InventoryPayload.applications(List.of(
                new InstalledApplication(largo, "v".repeat(120), "e".repeat(300), "C:\\" + "x".repeat(900), "REGISTRY"),
                new InstalledApplication("  ", null, null, null, "REGISTRY")));
        assertEquals(1, apps.size(), "sin nombre no se envía");
        assertEquals(180, ((String) apps.get(0).get("nombre")).length());
        assertEquals(80, ((String) apps.get(0).get("version")).length());
        assertEquals(160, ((String) apps.get(0).get("editor")).length());
        assertEquals(700, ((String) apps.get(0).get("rutaEjecutable")).length());
        var processes = InventoryPayload.processes(List.of(
                new RunningProcess(0, "Idle", null, false, T0),
                new RunningProcess(4, "System", null, false, T0),
                new RunningProcess(99, "Ventana " + largo, null, true, T0)));
        assertEquals(2, processes.size(), "el PID 0 de Windows («Idle») no es válido para el servidor");
        assertEquals(4L, processes.get(0).get("pid"));
        assertEquals(180, ((String) processes.get(1).get("nombre")).length());
    }

    @Test
    void soloSeConfirmaCuandoWindowsResuelveA0000() throws Exception {
        WindowsDnsVerifier.Resolver bloqueado = name -> List.of(InetAddress.getByAddress(name, new byte[4]));
        WindowsDnsVerifier.Resolver sinBloquear = name -> List.of(InetAddress.getByAddress(name, new byte[] {(byte) 157, (byte) 240, 1, 1}));
        WindowsDnsVerifier.Resolver sinRespuesta = name -> { throw new UnknownHostException(name); };
        assertTrue(WindowsDnsVerifier.check(List.of("tiktok.com"), bloqueado).ok());
        var fallo = WindowsDnsVerifier.check(List.of("tiktok.com"), sinBloquear);
        assertFalse(fallo.ok());
        assertTrue(fallo.detail().contains("tiktok.com → 157.240.1.1"), fallo.detail());
        assertFalse(WindowsDnsVerifier.check(List.of("tiktok.com"), sinRespuesta).ok());
    }

    @Test
    void sinVerificacionDeWindowsLaReglaQuedaEnErrorYNoComoAplicada() throws Exception {
        Path dir = Files.createTempDirectory("fg-web");
        Path hosts = dir.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\r\n");
        var store = new WebRestrictionStore(dir.resolve("web.bin"));
        var filter = new HostsFileFilter(hosts, (p, c) -> Files.write(p, c), () -> { });
        boolean[] windowsOk = {false};
        WindowsDnsVerifier verifier = domains -> new WindowsDnsVerifier.Result(windowsOk[0], windowsOk[0] ? "ok" : "Windows no aplica el bloqueo");
        var engine = new WebRestrictionEngine(store, filter, verifier);
        engine.replace("UTC", List.of(rule(true)));
        assertTrue(Files.readString(hosts).contains("0.0.0.0 tiktok.com"), "el archivo hosts sí se escribió");
        assertEquals(WebRestrictionEngine.State.NO_VERIFICADO, engine.state());
        var eventos = store.snapshot().events();
        assertEquals(WebRestrictionEngine.TYPE_ERROR, eventos.get(eventos.size() - 1).type());
        assertFalse(eventos.get(eventos.size() - 1).successful());
        // Cuando Windows ya resuelve el dominio bloqueado, se confirma (tras el reintento programado).
        windowsOk[0] = true;
        engine.apply(engine.nextAttempt());
        assertEquals(WebRestrictionEngine.State.APLICADO, engine.state());
        var ultimo = store.snapshot().events().get(store.snapshot().events().size() - 1);
        assertEquals(WebRestrictionEngine.TYPE_APPLIED, ultimo.type());
        assertTrue(ultimo.successful());
        assertTrue(ultimo.detail().contains("verificado en Windows"));
    }

    @Test
    void conSubdominiosSeBloqueanLosHabitualesYLosDelServicio() {
        Set<String> dominios = new TreeSet<>();
        WebRestrictionEngine.addDomains(dominios, rule(true));
        assertTrue(dominios.containsAll(Set.of("tiktok.com", "www.tiktok.com", "m.tiktok.com", "vm.tiktok.com", "vt.tiktok.com")), dominios.toString());
        Set<String> solo = new TreeSet<>();
        WebRestrictionEngine.addDomains(solo, rule(false));
        assertEquals(Set.of("tiktok.com"), solo);
    }

    private static WebRule rule(boolean subdomains) {
        return new WebRule(7, "tiktok.com", subdomains, "BLOQUEAR", true, null, null, 1, List.of());
    }
}
