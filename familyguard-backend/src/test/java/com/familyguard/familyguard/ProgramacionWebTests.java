package com.familyguard.familyguard;

import static org.junit.jupiter.api.Assertions.*;

import com.familyguard.familyguard.service.ProgramacionWeb;
import com.familyguard.familyguard.service.ProgramacionWeb.Estado;
import com.familyguard.familyguard.service.ProgramacionWeb.Franja;
import com.familyguard.familyguard.service.ProgramacionWeb.Regla;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Semántica de programación de reglas web con un reloj fijo: sin esperar horas reales. */
class ProgramacionWebTests {
    static final ZoneId LIMA = ZoneId.of("America/Lima");
    static Instant at(String local) { return LocalDateTime.parse(local).atZone(LIMA).toInstant(); }
    static List<Franja> lunesAViernes(String inicio, String fin) {
        List<Franja> f = new ArrayList<>();
        for (DayOfWeek d : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            LocalTime a = LocalTime.parse(inicio), b = LocalTime.parse(fin);
            f.add(new Franja(d, a, b, !b.isAfter(a), "BLOQUEAR"));
        }
        return f;
    }

    @Test
    void ejemploA_fechaYHorarioEspecificos() {
        Regla youtube = new Regla("BLOQUEAR", false, at("2026-11-20T08:00"), at("2026-11-20T18:00"), List.of());
        assertEquals(ProgramacionWeb.Tipo.FECHA, ProgramacionWeb.tipo(youtube));
        var antes = ProgramacionWeb.situacion(youtube, at("2026-11-20T07:59:59"), LIMA);
        assertEquals(Estado.PROGRAMADA, antes.estado());
        assertEquals(at("2026-11-20T08:00"), antes.proximoInicio());
        var inicio = ProgramacionWeb.situacion(youtube, at("2026-11-20T08:00"), LIMA);
        assertEquals(Estado.EN_HORARIO, inicio.estado(), "el inicio es inclusivo");
        assertEquals(at("2026-11-20T18:00"), inicio.finActual());
        assertEquals(Estado.FINALIZADA, ProgramacionWeb.situacion(youtube, at("2026-11-20T18:00"), LIMA).estado(), "el fin es exclusivo");
    }

    @Test
    void ejemploB_lunesAViernesDe8A18() {
        Regla tiktok = new Regla("BLOQUEAR", true, null, null, lunesAViernes("08:00", "18:00"));
        // 2026-11-20 es viernes; 21 sábado; 23 lunes.
        assertTrue(ProgramacionWeb.bloquea(tiktok, at("2026-11-20T08:00"), LIMA));
        assertFalse(ProgramacionWeb.bloquea(tiktok, at("2026-11-20T07:59"), LIMA));
        assertFalse(ProgramacionWeb.bloquea(tiktok, at("2026-11-20T18:00"), LIMA));
        assertFalse(ProgramacionWeb.bloquea(tiktok, at("2026-11-21T10:00"), LIMA), "sábado permitido");
        assertFalse(ProgramacionWeb.bloquea(tiktok, at("2026-11-22T10:00"), LIMA), "domingo permitido");
        var viernesNoche = ProgramacionWeb.situacion(tiktok, at("2026-11-20T19:00"), LIMA);
        assertEquals(Estado.FUERA_DE_HORARIO, viernesNoche.estado());
        assertEquals(at("2026-11-23T08:00"), viernesNoche.proximoInicio(), "el próximo bloqueo es el lunes");
        assertEquals(at("2026-11-20T18:00"), ProgramacionWeb.situacion(tiktok, at("2026-11-20T12:00"), LIMA).finActual());
    }

    @Test
    void ejemploC_permanenteSinHorarioBloqueaSiempre() {
        Regla siempre = new Regla("BLOQUEAR", true, null, null, List.of());
        var s = ProgramacionWeb.situacion(siempre, at("2026-11-21T03:00"), LIMA);
        assertEquals(ProgramacionWeb.Tipo.SIEMPRE, s.tipo());
        assertTrue(s.bloquea());
        assertNull(s.finActual(), "no tiene fin");
    }

    @Test
    void franjaQueCruzaLaMedianoche() {
        Regla noche = new Regla("BLOQUEAR", true, null, null,
                List.of(new Franja(DayOfWeek.FRIDAY, LocalTime.of(22, 0), LocalTime.of(6, 0), true, "BLOQUEAR")));
        assertTrue(ProgramacionWeb.bloquea(noche, at("2026-11-20T22:00"), LIMA));
        assertTrue(ProgramacionWeb.bloquea(noche, at("2026-11-21T05:59"), LIMA));
        assertFalse(ProgramacionWeb.bloquea(noche, at("2026-11-21T06:00"), LIMA));
        assertEquals(at("2026-11-21T06:00"), ProgramacionWeb.situacion(noche, at("2026-11-20T23:00"), LIMA).finActual());
    }

    @Test
    void repeticionConVencimiento() {
        Regla hastaFinDeMes = new Regla("BLOQUEAR", false, null, at("2026-11-30T00:00"), lunesAViernes("08:00", "18:00"));
        assertTrue(ProgramacionWeb.bloquea(hastaFinDeMes, at("2026-11-27T09:00"), LIMA));
        assertFalse(ProgramacionWeb.bloquea(hastaFinDeMes, at("2026-11-30T09:00"), LIMA), "vencida aunque sea lunes");
        assertEquals(Estado.FINALIZADA, ProgramacionWeb.situacion(hastaFinDeMes, at("2026-11-30T09:00"), LIMA).estado());
    }
}
