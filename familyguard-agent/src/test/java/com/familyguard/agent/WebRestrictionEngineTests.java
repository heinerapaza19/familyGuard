package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regresión del problema N.º 3: reintentos, eventos, recuperación y retirada sin permisos. */
class WebRestrictionEngineTests {
    private static final Instant T0 = Instant.parse("2026-11-16T15:00:00Z");
    @TempDir Path temp;
    private boolean allowed;
    private final AtomicInteger writes = new AtomicInteger();

    @Test void sinPermisosNoReintentaCadaDosSegundosNiDuplicaEventos() throws Exception {
        Fixture f = fixture(false);
        f.store.rules("UTC", List.of(rule(1, "example.com", 1)));
        for (Instant t = T0; t.isBefore(T0.plus(Duration.ofMinutes(10))); t = t.plusSeconds(2)) f.engine.apply(t);
        // 300 ciclos de supervisión, pero solo intentos en 0 s, 30 s, 90 s, 210 s y 450 s.
        assertEquals(5, f.engine.writeAttempts());
        assertEquals(WebRestrictionEngine.State.PENDIENTE_PERMISOS, f.engine.state());
        assertEquals("Bloqueo web pendiente: requiere permisos de administrador", f.engine.status());
        List<WebEvent> events = f.store.snapshot().events();
        assertEquals(1, events.size(), "un único evento para un estado que no cambia");
        assertEquals(WebRestrictionEngine.TYPE_PENDING, events.get(0).type());
        assertFalse(events.get(0).successful());
        assertEquals(1L, events.get(0).revision());
        assertFalse(Files.readString(f.hosts).contains("example.com"), "no debe declararse ni escribirse un bloqueo fallido");
    }

    @Test void reintentoEsControladoYAcotado() {
        assertEquals(Duration.ofSeconds(30), WebRestrictionEngine.backoff(1));
        assertEquals(Duration.ofSeconds(60), WebRestrictionEngine.backoff(2));
        assertEquals(Duration.ofMinutes(15), WebRestrictionEngine.backoff(20));
    }

    @Test void recuperaAlObtenerPermisosEnElSiguienteReintento() throws Exception {
        Fixture f = fixture(false);
        f.store.rules("UTC", List.of(rule(1, "example.com", 1)));
        f.engine.apply(T0);
        allowed = true;
        f.engine.apply(T0.plusSeconds(10));
        assertEquals(WebRestrictionEngine.State.PENDIENTE_PERMISOS, f.engine.state(), "respeta el intervalo de reintento");
        f.engine.apply(T0.plusSeconds(30));
        assertEquals(WebRestrictionEngine.State.APLICADO, f.engine.state());
        assertTrue(Files.readString(f.hosts).contains("0.0.0.0 example.com"));
        assertEquals(List.of(WebRestrictionEngine.TYPE_PENDING, WebRestrictionEngine.TYPE_APPLIED), types(f));
        for (int i = 1; i < 100; i++) f.engine.apply(T0.plusSeconds(30 + i * 2L));
        assertEquals(2, f.store.snapshot().events().size());
        assertEquals(1, writes.get());
    }

    @Test void recuperaTrasAutorizacionAdministrativaVerificandoHostsSinEscribir() throws Exception {
        Fixture f = fixture(false);
        f.store.rules("UTC", List.of(rule(1, "example.com", 1)));
        f.engine.apply(T0);
        // Simula el proceso auxiliar elevado (UAC) escribiendo el bloque correcto.
        assertTrue(new HostsFileFilter(f.hosts, (p, c) -> Files.write(p, c), () -> { }).apply(f.engine.desired(T0)).successful());
        f.engine.recheck(T0.plusSeconds(1));
        assertEquals(WebRestrictionEngine.State.APLICADO, f.engine.state());
        assertEquals(0, writes.get(), "el agente sin permisos solo verifica");
        assertEquals(List.of(WebRestrictionEngine.TYPE_PENDING, WebRestrictionEngine.TYPE_APPLIED), types(f));
    }

    @Test void retiradaDeRestriccionesRestauraHostsEInformaLaRetirada() throws Exception {
        Fixture f = fixture(true);
        Files.writeString(f.hosts, "127.0.0.1 localhost\n");
        f.engine.replace("UTC", List.of(rule(1, "example.com", 1), rule(2, "example.net", 1)));
        assertTrue(Files.readString(f.hosts).contains("example.net"));
        f.engine.replace("UTC", List.of(rule(1, "example.com", 1)));
        String hosts = Files.readString(f.hosts);
        assertFalse(hosts.contains("example.net"));
        assertTrue(hosts.contains("example.com"));
        f.engine.replace("UTC", List.of());
        assertEquals("127.0.0.1 localhost\n", Files.readString(f.hosts), "solo se retira el bloque de FamilyGuard");
        assertEquals(WebRestrictionEngine.State.SIN_REGLAS, f.engine.state());
        long removed = f.store.snapshot().events().stream()
                .filter(e -> e.type().equals(WebRestrictionEngine.TYPE_REMOVED) && e.successful()).count();
        assertEquals(2, removed);
    }

    @Test void retiradaSinPermisosQuedaPendienteHastaAutorizar() throws Exception {
        Fixture f = fixture(true);
        f.engine.replace("UTC", List.of(rule(1, "example.com", 1)));
        allowed = false;
        f.engine.replace("UTC", List.of());
        assertEquals(WebRestrictionEngine.State.PENDIENTE_PERMISOS, f.engine.state());
        assertTrue(Files.readString(f.hosts).contains("example.com"), "sin permisos el bloque permanece y así se informa");
        allowed = true;
        f.engine.recheck(Instant.now());
        assertFalse(Files.readString(f.hosts).contains(HostsFileFilter.BEGIN));
        List<WebEvent> removals = f.store.snapshot().events().stream()
                .filter(e -> e.type().equals(WebRestrictionEngine.TYPE_REMOVED)).toList();
        assertEquals(2, removals.size());
        assertFalse(removals.get(0).successful());
        assertTrue(removals.get(1).successful());
    }

    @Test void errorDistintoDePermisosSeInformaComoError() throws Exception {
        Path hosts = temp.resolve("hosts");
        Files.writeString(hosts, "");
        var store = new WebRestrictionStore(temp.resolve("web.bin"));
        var engine = new WebRestrictionEngine(store, new HostsFileFilter(hosts,
                (p, c) -> { throw new java.io.IOException("disco lleno"); }, () -> { }));
        store.rules("UTC", List.of(rule(1, "example.com", 1)));
        engine.apply(T0);
        assertEquals(WebRestrictionEngine.State.ERROR, engine.state());
        assertEquals(WebRestrictionEngine.TYPE_ERROR, store.snapshot().events().get(0).type());
    }

    @Test void cacheCifradaNoSeReescribeSiLasReglasNoCambian() {
        var store = new WebRestrictionStore(temp.resolve("web.bin"));
        assertTrue(store.rules("UTC", List.of(rule(1, "example.com", 1))));
        assertFalse(store.rules("UTC", List.of(rule(1, "example.com", 1))));
        assertTrue(store.rules("UTC", List.of(rule(1, "example.com", 2))));
    }

    @Test void nuevaRevisionDeLaReglaGeneraUnNuevoEventoDeEstado() throws Exception {
        Fixture f = fixture(true);
        f.engine.replace("UTC", List.of(rule(1, "example.com", 1)));
        f.engine.replace("UTC", List.of(rule(1, "example.com", 2)));
        List<WebEvent> events = f.store.snapshot().events();
        assertEquals(2, events.size());
        assertEquals(2L, events.get(1).revision());
    }

    @Test void politicasDeAplicacionesSeConfirmanAunqueElBloqueoWebEstePendiente() {
        String detail = AgentRuntime.confirmationDetail(HostsFileFilter.PERMISSION_MESSAGE);
        assertTrue(detail.startsWith("Reglas de aplicaciones aplicadas"));
        assertTrue(detail.contains("requiere permisos de administrador"));
        assertTrue(AgentRuntime.confirmationDetail("x".repeat(2000)).length() <= 450);
    }

    private List<String> types(Fixture f) { return f.store.snapshot().events().stream().map(WebEvent::type).toList(); }

    private Fixture fixture(boolean initiallyAllowed) throws Exception {
        allowed = initiallyAllowed;
        Path hosts = temp.resolve("hosts");
        if (!Files.exists(hosts)) Files.writeString(hosts, "");
        var store = new WebRestrictionStore(temp.resolve("web.bin"));
        var filter = new HostsFileFilter(hosts, (path, content) -> {
            if (!allowed) throw new AccessDeniedException(path.toString());
            writes.incrementAndGet();
            Files.write(path, content);
        }, () -> { });
        return new Fixture(hosts, store, new WebRestrictionEngine(store, filter));
    }
    private static WebRule rule(long id, String domain, long revision) {
        return new WebRule(id, domain, false, "BLOQUEAR", true, null, null, revision, List.of());
    }
    private record Fixture(Path hosts, WebRestrictionStore store, WebRestrictionEngine engine) { }
}
