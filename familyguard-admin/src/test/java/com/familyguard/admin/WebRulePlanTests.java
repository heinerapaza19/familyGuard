package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Formulario de reglas web: lo guardado se recupera exactamente al editar, en la zona horaria de la familia. */
class WebRulePlanTests {
    private static final String LIMA = "America/Lima";
    private static final Instant NOW = Instant.parse("2026-11-19T13:00:00Z");   // jueves 08:00 en Lima
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    /** Simula el viaje de ida y vuelta: lo que se envía es lo que el servidor devuelve al listar. */
    private static AdminApiClient.WebRuleInfo stored(AdminApiClient.WebRuleInput in) {
        List<AdminApiClient.WebScheduleInfo> s = in.horarios().stream()
                .map(h -> new AdminApiClient.WebScheduleInfo(h.dia(), h.inicio(), h.fin(), h.tipo())).toList();
        return new AdminApiClient.WebRuleInfo(7, in.menorId(), "Hijo", in.dominio(), in.incluirSubdominios(), in.categoria(),
                in.modo(), in.permanente(), in.vigenteDesde(), in.vigenteHasta(), in.motivo(), true, 1, in.dispositivoIds(),
                List.of("PC"), s, "SINCRONIZADO", "PENDIENTE", "", "", "", "PENDIENTE", "", false, null, null, null, null, null, null, LIMA);
    }

    @Test
    void fechaYHorarioSeGuardanEnLaZonaDeLaFamiliaYSeRecuperanSinDesfase() {
        var form = new WebRulePlan.Form(WebRulePlan.Kind.DATE, "BLOQUEAR", TODAY, LocalTime.of(8, 30), TODAY, LocalTime.of(10, 30),
                null, LocalTime.of(8, 0), LocalTime.of(18, 0), null);
        var in = WebRulePlan.toInput(form, 1, 2, " youtube.com ", true, "", LIMA, NOW);
        assertEquals(Instant.parse("2026-11-19T13:30:00Z"), in.vigenteDesde());
        assertEquals(Instant.parse("2026-11-19T15:30:00Z"), in.vigenteHasta());
        assertFalse(in.permanente());
        assertEquals("youtube.com", in.dominio());
        // Editar varias veces no cambia la fecha (antes avanzaba un día en cada edición).
        var again = WebRulePlan.fromRule(stored(in), TODAY.plusDays(3));
        for (int i = 0; i < 3; i++) again = WebRulePlan.fromRule(stored(WebRulePlan.toInput(again, 1, 2, "youtube.com", true, "", LIMA, NOW)), TODAY);
        assertEquals(WebRulePlan.Kind.DATE, again.kind());
        assertEquals(TODAY, again.startDate());
        assertEquals(LocalTime.of(8, 30), again.startTime());
        assertEquals(TODAY, again.endDate());
        assertEquals(LocalTime.of(10, 30), again.endTime());
    }

    @Test
    void semanalRecuperaDiasHorasYVencimientoExactos() {
        var days = EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
        var form = new WebRulePlan.Form(WebRulePlan.Kind.WEEKLY, "BLOQUEAR", TODAY, LocalTime.of(8, 0), TODAY, LocalTime.of(9, 0),
                days, LocalTime.of(22, 0), LocalTime.of(6, 0), LocalDate.of(2026, 12, 31));
        var in = WebRulePlan.toInput(form, 1, 2, "tiktok.com", true, "", LIMA, NOW);
        assertEquals(3, in.horarios().size());
        assertEquals(Instant.parse("2027-01-01T05:00:00Z"), in.vigenteHasta());   // incluye todo el 31/12 en Lima
        var back = WebRulePlan.fromRule(stored(in), TODAY);
        assertEquals(WebRulePlan.Kind.WEEKLY, back.kind());
        assertEquals(days, back.days());
        assertEquals(LocalTime.of(22, 0), back.dailyStart());
        assertEquals(LocalTime.of(6, 0), back.dailyEnd());
        assertEquals(LocalDate.of(2026, 12, 31), back.weeklyUntil());
        assertEquals("tiktok.com se bloqueará los lunes, miércoles y viernes, de 22:00 a 06:00 (del día siguiente) en PC, hasta el 31/12/2026.",
                WebRulePlan.summary(back, "tiktok.com", "PC"));
    }

    @Test
    void semanalSinVencimientoEsPermanenteYSiempreNoTieneFechas() {
        var weekly = new WebRulePlan.Form(WebRulePlan.Kind.WEEKLY, "BLOQUEAR", TODAY, LocalTime.of(8, 0), TODAY, LocalTime.of(9, 0),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalTime.of(8, 0), LocalTime.of(18, 0), null);
        var in = WebRulePlan.toInput(weekly, 1, 2, "youtube.com", true, "", LIMA, NOW);
        assertTrue(in.permanente());
        assertNull(in.vigenteHasta());
        assertNull(WebRulePlan.fromRule(stored(in), TODAY).weeklyUntil());
        assertEquals("youtube.com se bloqueará de lunes a viernes, de 08:00 a 18:00 en el portátil, sin fecha de vencimiento.",
                WebRulePlan.summary(weekly, "youtube.com", "el portátil"));
        var always = WebRulePlan.toInput(WebRulePlan.blank(TODAY), 1, 2, "x.com", false, "", LIMA, NOW);
        assertTrue(always.permanente());
        assertNull(always.vigenteDesde());
        assertTrue(always.horarios().isEmpty());
        assertEquals(WebRulePlan.Kind.ALWAYS, WebRulePlan.fromRule(stored(always), TODAY).kind());
    }

    @Test
    void validacionesConMensajesClaros() {
        var past = new WebRulePlan.Form(WebRulePlan.Kind.DATE, "BLOQUEAR", TODAY.minusDays(2), LocalTime.of(8, 0), TODAY.minusDays(1),
                LocalTime.of(8, 0), null, LocalTime.of(8, 0), LocalTime.of(18, 0), null);
        assertEquals("La fecha y hora de fin ya pasaron.",
                assertThrows(IllegalArgumentException.class, () -> WebRulePlan.toInput(past, 1, 2, "a.com", true, "", LIMA, NOW)).getMessage());
        var inverted = new WebRulePlan.Form(WebRulePlan.Kind.DATE, "BLOQUEAR", TODAY, LocalTime.of(10, 0), TODAY, LocalTime.of(9, 0),
                null, LocalTime.of(8, 0), LocalTime.of(18, 0), null);
        assertEquals("El fin debe ser posterior al inicio.",
                assertThrows(IllegalArgumentException.class, () -> WebRulePlan.toInput(inverted, 1, 2, "a.com", true, "", LIMA, NOW)).getMessage());
        var noDays = new WebRulePlan.Form(WebRulePlan.Kind.WEEKLY, "BLOQUEAR", TODAY, LocalTime.of(8, 0), TODAY, LocalTime.of(9, 0),
                EnumSet.noneOf(DayOfWeek.class), LocalTime.of(8, 0), LocalTime.of(18, 0), null);
        assertEquals("Elige al menos un día de la semana.",
                assertThrows(IllegalArgumentException.class, () -> WebRulePlan.toInput(noDays, 1, 2, "a.com", true, "", LIMA, NOW)).getMessage());
        assertEquals(LocalTime.of(8, 0), WebRulePlan.parseTime("8"));
        assertEquals(LocalTime.of(7, 5), WebRulePlan.parseTime("7:05"));
        assertThrows(IllegalArgumentException.class, () -> WebRulePlan.parseTime("25:00"));
    }

    @Test
    void textosDeTablaYDetalles() {
        assertEquals("Bloqueando ahora", WebRulePlan.stateLabel("BLOQUEANDO_AHORA"));
        assertEquals("Fuera de horario", WebRulePlan.stateLabel("FUERA_DE_HORARIO"));
        assertEquals("Pendiente", WebRulePlan.stateLabel(null));
        assertEquals("1 hora y 25 minutos", WebRulePlan.duration(Duration.ofMinutes(85)));
        assertEquals("2 días y 4 horas", WebRulePlan.duration(Duration.ofHours(52)));
        assertEquals("menos de un minuto", WebRulePlan.duration(Duration.ofSeconds(20)));
        assertEquals("hoy a las 18:00", WebRulePlan.when(Instant.parse("2026-11-19T23:00:00Z"), WebRulePlan.zone(LIMA), NOW));
        assertEquals("de lunes a viernes", WebRulePlan.daysLabel(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)));
        assertEquals("los sábados y domingos", WebRulePlan.daysLabel(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)));
    }

    @Test
    void actualizarRestriccionSoloCambiaLaProgramacionDeLaMismaRegla() {
        var original = WebRulePlan.toInput(new WebRulePlan.Form(WebRulePlan.Kind.WEEKLY, "BLOQUEAR", TODAY, LocalTime.of(8, 0), TODAY,
                LocalTime.of(9, 0), EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalTime.of(8, 0), LocalTime.of(18, 0), null),
                1, 2, "tiktok.com", true, "Tareas", LIMA, NOW);
        var rule = stored(original);
        var form = WebRulePlan.fromRule(rule, TODAY);
        assertEquals(LocalTime.of(8, 0), form.dailyStart());   // se abre con lo guardado
        var changed = new WebRulePlan.Form(WebRulePlan.Kind.DATE, "PERMITIR", TODAY, LocalTime.of(15, 0), TODAY.plusDays(1),
                LocalTime.of(7, 30), form.days(), form.dailyStart(), form.dailyEnd(), null);
        var update = WebRulePlan.scheduleUpdate(changed, rule, NOW);
        assertEquals("tiktok.com", update.dominio());
        assertEquals(List.of(2L), update.dispositivoIds());
        assertEquals(1, update.menorId());
        assertTrue(update.incluirSubdominios());
        assertEquals("Tareas", update.motivo());
        assertEquals("BLOQUEAR", update.modo());                // el tipo de bloqueo no cambia
        assertTrue(update.horarios().isEmpty());
        assertEquals(Instant.parse("2026-11-19T20:00:00Z"), update.vigenteDesde());
        assertEquals(Instant.parse("2026-11-20T12:30:00Z"), update.vigenteHasta());
        var reopened = WebRulePlan.fromRule(stored(update), TODAY);
        assertEquals(WebRulePlan.Kind.DATE, reopened.kind());
        assertEquals(LocalTime.of(15, 0), reopened.startTime());
        assertEquals(TODAY.plusDays(1), reopened.endDate());
        assertEquals(LocalTime.of(7, 30), reopened.endTime());
    }
}
