package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ejecución automática de reglas web programadas, con un reloj controlado (sin esperar horas reales).
 * 2026-11-20 es viernes; 2026-11-21 sábado; 2026-11-23 lunes. Zona de la familia: America/Lima.
 */
class WebScheduleExecutionTests {
    static final String ZONA = "America/Lima";
    static Instant at(String local) { return LocalDateTime.parse(local).atZone(ZoneId.of(ZONA)).toInstant(); }

    record Equipo(Path dir, Path hosts, WebRestrictionStore store, WebRestrictionEngine engine) {
        boolean bloquea(String dominio) throws Exception { return Files.readString(hosts).contains("0.0.0.0 " + dominio + "\r\n"); }
        String ultimoEvento() { var e = store.snapshot().events(); return e.isEmpty() ? null : e.get(e.size() - 1).type(); }
    }

    static Equipo equipo(Path dir) throws Exception {
        Path hosts = dir.resolve("hosts");
        if (!Files.exists(hosts)) Files.writeString(hosts, "127.0.0.1 localhost\r\n");
        var store = new WebRestrictionStore(dir.resolve("web-rules.bin"));
        var engine = new WebRestrictionEngine(store, new HostsFileFilter(hosts, (p, c) -> Files.write(p, c), () -> { }));
        return new Equipo(dir, hosts, store, engine);
    }

    static List<WebSchedule> lunesAViernes(String inicio, String fin) {
        List<WebSchedule> l = new ArrayList<>();
        LocalTime a = LocalTime.parse(inicio), b = LocalTime.parse(fin);
        for (DayOfWeek d : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
            l.add(new WebSchedule(d, a, b, !b.isAfter(a), "BLOQUEAR"));
        return l;
    }

    @Test
    void fechaYHorario_seActivaAlInicioYSeRetiraAlFinal() throws Exception {
        Equipo e = equipo(Files.createTempDirectory("fg-sched-a"));
        Instant[] reloj = {at("2026-11-20T07:59")};
        e.engine().useClock(() -> reloj[0]);
        e.engine().replace(ZONA, List.of(new WebRule(1, "youtube.com", false, "BLOQUEAR", false,
                at("2026-11-20T08:00"), at("2026-11-20T18:00"), 1, List.of())));
        assertFalse(e.bloquea("youtube.com"), "antes de las 08:00 se permite");
        assertEquals(WebRestrictionEngine.TYPE_INACTIVE, e.ultimoEvento(), "informa «fuera de horario», no «bloqueando»");
        e.engine().apply(at("2026-11-20T08:00"));
        assertTrue(e.bloquea("youtube.com"), "a las 08:00 empieza el bloqueo sin pulsar Actualizar");
        assertEquals(WebRestrictionEngine.TYPE_APPLIED, e.ultimoEvento());
        e.engine().apply(at("2026-11-20T17:59:59"));
        assertTrue(e.bloquea("youtube.com"));
        e.engine().apply(at("2026-11-20T18:00"));
        assertFalse(e.bloquea("youtube.com"), "a las 18:00 termina automáticamente");
        assertEquals(WebRestrictionEngine.TYPE_INACTIVE, e.ultimoEvento());
    }

    @Test
    void lunesAViernes_noBloqueaElFinDeSemanaYVuelveElLunes() throws Exception {
        Equipo e = equipo(Files.createTempDirectory("fg-sched-b"));
        e.engine().useClock(() -> at("2026-11-20T09:00"));
        e.engine().replace(ZONA, List.of(new WebRule(2, "tiktok.com", true, "BLOQUEAR", true, null, null, 1, lunesAViernes("08:00", "18:00"))));
        assertTrue(e.bloquea("tiktok.com") && e.bloquea("www.tiktok.com"), "viernes 09:00 bloqueado (con subdominios)");
        e.engine().apply(at("2026-11-21T10:00"));
        assertFalse(e.bloquea("tiktok.com"), "sábado permitido");
        e.engine().apply(at("2026-11-22T20:00"));
        assertFalse(e.bloquea("tiktok.com"), "domingo permitido");
        e.engine().apply(at("2026-11-23T08:00"));
        assertTrue(e.bloquea("tiktok.com"), "el lunes a las 08:00 vuelve a bloquearse");
    }

    @Test
    void franjaQueCruzaLaMedianoche() throws Exception {
        Equipo e = equipo(Files.createTempDirectory("fg-sched-c"));
        e.engine().useClock(() -> at("2026-11-20T21:59"));
        e.engine().replace(ZONA, List.of(new WebRule(3, "tiktok.com", false, "BLOQUEAR", true, null, null, 1,
                List.of(new WebSchedule(DayOfWeek.FRIDAY, LocalTime.of(22, 0), LocalTime.of(6, 0), true, "BLOQUEAR")))));
        assertFalse(e.bloquea("tiktok.com"));
        e.engine().apply(at("2026-11-20T22:00"));
        assertTrue(e.bloquea("tiktok.com"));
        e.engine().apply(at("2026-11-21T05:59"));
        assertTrue(e.bloquea("tiktok.com"), "sigue bloqueado el sábado de madrugada");
        e.engine().apply(at("2026-11-21T06:00"));
        assertFalse(e.bloquea("tiktok.com"));
    }

    @Test
    void conservaLasReglasTrasReiniciarYFuncionaSinInternet() throws Exception {
        Path dir = Files.createTempDirectory("fg-sched-d");
        Equipo antes = equipo(dir);
        antes.engine().useClock(() -> at("2026-11-20T07:00"));
        antes.engine().replace(ZONA, List.of(new WebRule(4, "tiktok.com", false, "BLOQUEAR", true, null, null, 1, lunesAViernes("08:00", "18:00"))));
        assertFalse(antes.bloquea("tiktok.com"));
        // Reinicio de Windows sin Internet: nuevo motor con las reglas guardadas (cifradas) y sin descargar nada.
        Equipo despues = equipo(dir);
        despues.engine().apply(at("2026-11-20T08:30"));
        assertTrue(despues.bloquea("tiktok.com"), "aplica la política guardada sin conexión");
        despues.engine().apply(at("2026-11-20T18:30"));
        assertFalse(despues.bloquea("tiktok.com"));
    }

    @Test
    void otraReglaVigenteMantieneElBloqueoDelMismoDominio() throws Exception {
        Equipo e = equipo(Files.createTempDirectory("fg-sched-e"));
        e.engine().useClock(() -> at("2026-11-21T10:00"));   // sábado: la regla semanal no bloquea
        WebRule semanal = new WebRule(5, "tiktok.com", false, "BLOQUEAR", true, null, null, 1, lunesAViernes("08:00", "18:00"));
        WebRule siempre = new WebRule(6, "tiktok.com", false, "BLOQUEAR", true, null, null, 1, List.of());
        e.engine().replace(ZONA, List.of(semanal, siempre));
        assertTrue(e.bloquea("tiktok.com"), "la regla permanente bloquea aunque la semanal esté fuera de horario");
        e.engine().replace(ZONA, List.of(siempre));
        assertTrue(e.bloquea("tiktok.com"), "eliminar la semanal no quita la entrada que necesita la otra");
        e.engine().replace(ZONA, List.of(semanal));
        assertFalse(e.bloquea("tiktok.com"), "sin la permanente, el sábado queda permitido");
        assertTrue(Files.readString(e.hosts()).contains("127.0.0.1 localhost"), "no toca entradas ajenas a FamilyGuard");
    }

    @Test
    void elRelojDeHorariosUsaLaHoraDelServidorAunqueCambienElRelojDeWindows() {
        TemporaryPermissionsTests.FakeSource source = new TemporaryPermissionsTests.FakeSource(at("2026-11-20T17:00"));
        TrustedClock clock = new TrustedClock(source, null);
        clock.anchor(at("2026-11-20T17:00"));
        source.advance(Duration.ofMinutes(5));
        source.system = at("2026-11-20T19:00");   // el hijo adelanta la hora de Windows para salir del horario
        assertEquals(at("2026-11-20T17:05"), clock.scheduleNow());
        source.system = at("2026-11-20T07:00");   // o la atrasa
        assertEquals(at("2026-11-20T17:05"), clock.scheduleNow());
        // Sin hora del servidor reciente (por ejemplo tras suspender el equipo), usa Windows sin retroceder.
        source.advance(Duration.ofMinutes(15));
        source.system = at("2026-11-20T17:30");
        assertEquals(at("2026-11-20T17:30"), clock.scheduleNow());
        source.system = at("2026-11-20T17:10");
        assertEquals(at("2026-11-20T17:30"), clock.scheduleNow(), "nunca retrocede");
    }
}
