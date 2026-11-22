package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Entrega final: permisos temporales en el Agente. Un permiso vencido nunca está activo, reiniciar no lo renueva,
 * perder Internet no lo vuelve permanente y un reloj atrasado deja las restricciones normales.
 */
class TemporaryPermissionsTests {
    static final Instant T0 = Instant.parse("2026-11-19T15:00:00Z");   // jueves

    static final class FakeSource implements TrustedClock.Source {
        Instant system; long nanos = 1_000_000_000L;
        FakeSource(Instant system) { this.system = system; }
        public Instant system() { return system; }
        public long nanos() { return nanos; }
        void advance(Duration d) { system = system.plus(d); nanos += d.toNanos(); }
    }
    static final ProtectedFile.Protector PLAIN = new ProtectedFile.Protector() {
        public byte[] protect(byte[] data) { return data; }
        public byte[] unprotect(byte[] data) { return data; }
    };

    static TemporaryPermissions.Grant grant(long row, String type, Long app, Long site, Integer extra, Instant from, Instant until) {
        return new TemporaryPermissions.Grant(row, row, type, app, "C:\\Juegos\\minecraft.exe", site, site == null ? null : "youtube.com",
                extra, from, until);
    }
    static RestrictionRule rule(long limitSeconds, long consumed, boolean blocked, List<ScheduleRule> schedules) {
        return new RestrictionRule(1, 7, "Minecraft", "C:\\Juegos\\minecraft.exe", limitSeconds, 300, blocked, consumed, 1, schedules);
    }
    static Path tempFile(String name) throws Exception { return Files.createTempDirectory("fg-permisos").resolve(name); }

    @Test
    void permisoVencidoONoIniciadoNuncaEstaActivo() {
        var g = grant(1, TemporaryPermissions.OUTSIDE, 7L, null, null, T0, T0.plus(Duration.ofMinutes(30)));
        assertFalse(TemporaryPermissions.evaluate(List.of(g), Map.of(), T0.plus(Duration.ofMinutes(30)), true).outsideSchedule(7));
        assertFalse(TemporaryPermissions.evaluate(List.of(g), Map.of(), T0.minusSeconds(1), true).outsideSchedule(7));
        assertTrue(TemporaryPermissions.evaluate(List.of(g), Map.of(), T0.plus(Duration.ofMinutes(29)), true).outsideSchedule(7));
        // Sin presupuesto restante o con el reloj sin confianza: no vale aunque la hora diga que sí.
        assertFalse(TemporaryPermissions.evaluate(List.of(g), Map.of(1L, 0L), T0.plusSeconds(60), true).outsideSchedule(7));
        assertFalse(TemporaryPermissions.evaluate(List.of(g), Map.of(), T0.plusSeconds(60), false).outsideSchedule(7));
    }

    @Test
    void tiempoExtraSeSumaAlLimiteYNuncaPasaDeCuatroHoras() {
        var a = grant(1, TemporaryPermissions.EXTRA, 7L, null, 200, T0, T0.plus(Duration.ofHours(8)));
        var b = grant(2, TemporaryPermissions.EXTRA, 7L, null, 100, T0, T0.plus(Duration.ofHours(8)));
        var view = TemporaryPermissions.evaluate(List.of(a, b), Map.of(), T0.plusSeconds(10), true);
        assertEquals(240 * 60L, view.extraSeconds(7));
        assertEquals(0L, view.extraSeconds(8));
        ZonedDateTime now = T0.atZone(ZoneOffset.UTC);
        RuleEvaluator evaluator = new RuleEvaluator();
        var rule = rule(3600, 3600, false, List.of());
        assertTrue(evaluator.evaluate(rule, 0, now).blocked());
        var one = TemporaryPermissions.evaluate(List.of(grant(3, TemporaryPermissions.EXTRA, 7L, null, 30, T0, T0.plus(Duration.ofHours(8)))),
                Map.of(), T0.plusSeconds(10), true);
        var decision = evaluator.evaluate(rule, 0, now, one);
        assertFalse(decision.blocked());
        assertEquals(1800L, decision.remainingSeconds());
        assertTrue(evaluator.evaluate(rule, 1800, now, one).blocked());
    }

    @Test
    void fueraDeHorarioIgnoraHorariosPeroNoElBloqueoTotalYElDesbloqueoPermiteTodo() {
        ZonedDateTime now = T0.atZone(ZoneOffset.UTC);   // jueves 15:00
        var bloqueado = List.of(new ScheduleRule(DayOfWeek.THURSDAY, LocalTime.of(14, 0), LocalTime.of(18, 0), false, "BLOQUEAR"));
        RuleEvaluator evaluator = new RuleEvaluator();
        var fuera = TemporaryPermissions.evaluate(List.of(grant(1, TemporaryPermissions.OUTSIDE, 7L, null, null, T0, T0.plus(Duration.ofMinutes(20)))),
                Map.of(), T0.plusSeconds(5), true);
        assertTrue(evaluator.evaluate(rule(86_400, 0, false, bloqueado), 0, now).blocked());
        var permitido = evaluator.evaluate(rule(86_400, 0, false, bloqueado), 0, now, fuera);
        assertFalse(permitido.blocked());
        assertEquals(T0.plus(Duration.ofMinutes(20)), permitido.accessEnd());
        assertTrue(evaluator.evaluate(rule(86_400, 0, true, List.of()), 0, now, fuera).blocked());
        assertTrue(evaluator.evaluate(rule(600, 600, false, bloqueado), 0, now, fuera).blocked());

        var desbloqueo = TemporaryPermissions.evaluate(List.of(grant(2, TemporaryPermissions.UNBLOCK, 7L, null, null, T0, T0.plus(Duration.ofMinutes(15)))),
                Map.of(), T0.plusSeconds(5), true);
        var libre = evaluator.evaluate(rule(600, 600, true, bloqueado), 0, now, desbloqueo);
        assertFalse(libre.blocked());
        assertEquals("PERMISO_TEMPORAL", libre.reason());
        // Otra aplicación no se beneficia del permiso.
        assertTrue(evaluator.evaluate(new RestrictionRule(2, 99, "Roblox", "C:\\roblox.exe", 600, 0, true, 0, 1, List.of()), 0, now, desbloqueo).blocked());
    }

    @Test
    void permitirSitioSoloAfectaALaReglaIndicada() {
        var view = TemporaryPermissions.evaluate(List.of(grant(1, TemporaryPermissions.SITE, null, 44L, null, T0, T0.plusSeconds(600))),
                Map.of(), T0.plusSeconds(1), true);
        assertTrue(view.siteAllowed(44));
        assertFalse(view.siteAllowed(45));
    }

    @Test
    void relojAtrasadoMientrasFuncionaNoDetieneElTiempoMonotonico() {
        FakeSource source = new FakeSource(T0);
        TrustedClock clock = new TrustedClock(source, null);
        source.advance(Duration.ofMinutes(10));
        source.system = source.system.minus(Duration.ofHours(2));     // el hijo atrasa la hora de Windows
        assertEquals(T0.plus(Duration.ofMinutes(10)), clock.now());
        assertTrue(clock.trusted());
        source.system = source.system.plus(Duration.ofHours(5));      // adelantarla solo acorta los permisos
        assertTrue(clock.now().isAfter(T0.plus(Duration.ofHours(2))));
    }

    @Test
    void relojAtrasadoConElAgenteCerradoSuspendeLosPermisosHastaLaHoraDelServidor() throws Exception {
        Path file = tempFile("permissions.bin");
        FakeSource source = new FakeSource(T0);
        var manager = new TemporaryPermissionManager(new TemporaryPermissionStore(new ProtectedFile(file, PLAIN)), source);
        manager.replace(T0, List.of(grant(1, TemporaryPermissions.OUTSIDE, 7L, null, null, T0, T0.plus(Duration.ofHours(1)))));
        assertTrue(manager.tick().outsideSchedule(7));
        source.advance(Duration.ofMinutes(50));
        manager.tick();
        // Reinicio con la hora de Windows atrasada 1 hora: sin hora del servidor, ningún permiso.
        FakeSource restarted = new FakeSource(T0.minus(Duration.ofMinutes(10)));
        var after = new TemporaryPermissionManager(new TemporaryPermissionStore(new ProtectedFile(file, PLAIN)), restarted);
        assertFalse(after.clock().trusted());
        assertTrue(after.tick().isEmpty());
        assertTrue(after.pendingEvents().stream().anyMatch(e -> "ERROR".equals(e.state())));
        // Llega la hora real del servidor: el permiso ya terminó (no se recuperan los minutos).
        after.anchor(T0.plus(Duration.ofMinutes(55)));
        assertTrue(after.clock().trusted());
        restarted.advance(Duration.ofMinutes(6));
        assertTrue(after.tick().isEmpty());
        assertTrue(after.pendingEvents().stream().anyMatch(e -> "FINALIZADO".equals(e.state())));
    }

    @Test
    void reiniciarNoRenuevaElPermisoYSinInternetVenceIgual() throws Exception {
        Path file = tempFile("permissions.bin");
        FakeSource source = new FakeSource(T0);
        var manager = new TemporaryPermissionManager(new TemporaryPermissionStore(new ProtectedFile(file, PLAIN)), source);
        manager.replace(T0, List.of(grant(1, TemporaryPermissions.UNBLOCK, 7L, null, null, T0, T0.plus(Duration.ofMinutes(10)))));
        for (int i = 0; i < 6; i++) { source.advance(Duration.ofMinutes(1)); assertTrue(manager.tick().unblocked(7)); }
        // Reinicio: el presupuesto guardado (4 min) sigue contando; la hora de Windows no cambió.
        FakeSource restarted = new FakeSource(source.system);
        var after = new TemporaryPermissionManager(new TemporaryPermissionStore(new ProtectedFile(file, PLAIN)), restarted);
        assertTrue(after.tick().unblocked(7));
        restarted.advance(Duration.ofMinutes(3));
        assertTrue(after.tick().unblocked(7));
        restarted.advance(Duration.ofMinutes(2));
        assertFalse(after.tick().unblocked(7), "vencido sin conexión con el servidor");
        assertTrue(after.pendingEvents().stream().anyMatch(e -> "FINALIZADO".equals(e.state())));
        // Aunque alguien atrase la hora de Windows después, sigue vencido.
        restarted.system = T0.plusSeconds(60);
        assertFalse(after.tick().unblocked(7));
    }

    @Test
    void laListaDelServidorMandaYUnPermisoCanceladoSeRetiraAlInstante() throws Exception {
        FakeSource source = new FakeSource(T0);
        var manager = new TemporaryPermissionManager(new TemporaryPermissionStore(new ProtectedFile(tempFile("p.bin"), PLAIN)), source);
        manager.replace(T0, List.of(grant(1, TemporaryPermissions.SITE, null, 44L, null, T0, T0.plus(Duration.ofHours(1)))));
        assertTrue(manager.tick().siteAllowed(44));
        source.advance(Duration.ofMinutes(5));
        manager.replace(source.system, List.of());
        assertFalse(manager.current().siteAllowed(44));
        var events = manager.pendingEvents();
        assertEquals("APLICADO", events.get(0).state());
        assertEquals("FINALIZADO", events.get(events.size() - 1).state());
        manager.acknowledge(events.stream().map(TemporaryPermissionStore.PermissionEvent::localId).toList());
        assertTrue(manager.pendingEvents().isEmpty());
    }

    @Test
    void cacheDanadoSeApartaYNoConcedeNingunPermiso() throws Exception {
        Path file = tempFile("permissions.bin");
        Files.writeString(file, "{esto no es json");
        var store = new TemporaryPermissionStore(new ProtectedFile(file, PLAIN));
        assertNotNull(store.recoveryNotice());
        var manager = new TemporaryPermissionManager(store, new FakeSource(T0));
        assertTrue(manager.tick().isEmpty());
    }

    @Test
    void avisosAlHijoA10_5Y1MinutosSinRepetir() {
        TimeWarnings warnings = new TimeWarnings();
        assertTrue(warnings.check("k", 900, "Minecraft", false).isEmpty());
        assertEquals("Te quedan 10 minutos de Minecraft por hoy.", warnings.check("k", 600, "Minecraft", false).orElseThrow());
        assertTrue(warnings.check("k", 590, "Minecraft", false).isEmpty());
        assertEquals("Te quedan 5 minutos de Minecraft por hoy.", warnings.check("k", 300, "Minecraft", false).orElseThrow());
        assertEquals("Tu permiso para Minecraft termina en 1 minuto.", warnings.check("p", 45, "Minecraft", true).orElseThrow());
        assertTrue(warnings.check("p", 30, "Minecraft", true).isEmpty());
    }

    @Test
    void margenDe30SegundosSoloSiLaAplicacionEstabaPermitida() {
        BlockGrace grace = new BlockGrace();
        assertEquals(BlockGrace.Action.TERMINATE, grace.decide("a", true, 0));
        assertEquals(BlockGrace.Action.ALLOW, grace.decide("b", false, 0));
        assertEquals(BlockGrace.Action.GRACE_STARTED, grace.decide("b", true, 1_000_000_000L));
        assertEquals(BlockGrace.Action.IN_GRACE, grace.decide("b", true, 20_000_000_000L));
        assertEquals(BlockGrace.Action.TERMINATE, grace.decide("b", true, 31_000_000_001L));
        assertEquals(BlockGrace.Action.TERMINATE, grace.decide("b", true, 32_000_000_000L));
    }

    @Test
    void respuestaDelServidorSeInterpretaYLosDatosIncompletosSeIgnoran() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper().readTree("""
                {"servidorEn":"2026-11-19T15:00:00Z","permisos":[
                  {"filaId":5,"permisoId":2,"tipo":"TIEMPO_EXTRA","aplicacionId":7,"rutaEjecutable":"C:\\\\m.exe","sitioId":null,
                   "dominio":null,"minutosExtra":30,"inicioEn":"2026-11-19T14:00:00Z","expiraEn":"2026-11-20T05:00:00Z"},
                  {"filaId":6,"tipo":"FUERA_HORARIO","aplicacionId":7,"inicioEn":"2026-11-19T14:00:00Z","expiraEn":"no es fecha"}]}""");
        var download = AgentApiClient.parsePermissions(json);
        assertEquals(T0, download.serverTime());
        assertEquals(1, download.grants().size());
        assertEquals(Integer.valueOf(30), download.grants().get(0).extraMinutes());
        assertEquals(Long.valueOf(7L), download.grants().get(0).applicationId());
    }
}
