package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Vigencia por fechas de la restricción de aplicaciones en el Administrador. */
class RestrictionPlanTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    @Test
    void textosDeVigencia() {
        assertEquals("Siempre", RestrictionPlan.validity(null, null));
        assertEquals("Del 19/11/2026 al 25/11/2026", RestrictionPlan.validity(TODAY, TODAY.plusDays(6)));
        assertEquals("Solo el 19/11/2026", RestrictionPlan.validity(TODAY, TODAY));
        assertEquals("Hasta el 25/11/2026", RestrictionPlan.validity(null, TODAY.plusDays(6)));
    }

    @Test
    void validaLasFechasAntesDeGuardar() {
        assertNull(RestrictionPlan.validate(TODAY, TODAY.plusDays(6), TODAY));
        assertNull(RestrictionPlan.validate(TODAY, TODAY, TODAY));
        assertEquals("La fecha de fin no puede ser anterior a la de inicio.", RestrictionPlan.validate(TODAY.plusDays(3), TODAY, TODAY));
        assertEquals("La fecha de fin ya pasó.", RestrictionPlan.validate(TODAY.minusDays(9), TODAY.minusDays(2), TODAY));
        assertEquals("Elige la fecha de inicio y la de fin.", RestrictionPlan.validate(null, TODAY, TODAY));
    }

    @Test
    void estadoDeLaRestriccionNoOcultaQueEstaDesbloqueada() {
        assertEquals("Desbloqueada", RestrictionPlan.state(info(false, true, true)));   // antes se veía «Agotado»
        assertEquals("Bloqueada", RestrictionPlan.state(info(true, true, true)));
        assertEquals("Agotado", RestrictionPlan.state(info(true, false, true)));
        assertEquals("Activa", RestrictionPlan.state(info(true, false, false)));
    }

    private static AdminApiClient.RestrictionInfo info(boolean active, boolean total, boolean exhausted) {
        return new AdminApiClient.RestrictionInfo(1, 2, "Hijo", 3, "ChatGPT.exe", 4, "PC", 2, total, 1, 0, 60, exhausted, active,
                "APLICADA", java.util.List.of(), null, null);
    }

    @Test
    void laVigenciaSoloSeEnviaSiSeEligieronFechas() throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var schedule = new AdminApiClient.ScheduleInput(java.time.DayOfWeek.THURSDAY, java.time.LocalTime.of(16, 0), java.time.LocalTime.of(18, 0), "BLOQUEAR");
        String sinFechas = mapper.writeValueAsString(AdminApiClient.restrictionBody(
                new AdminApiClient.RestrictionInput(1, 2, 60, true, 1, java.util.List.of(schedule))));
        org.junit.jupiter.api.Assertions.assertFalse(sinFechas.contains("vigente"), sinFechas);
        org.junit.jupiter.api.Assertions.assertTrue(sinFechas.contains("\"inicio\":\"16:00:00\""), sinFechas);
        String conFechas = mapper.writeValueAsString(AdminApiClient.restrictionBody(
                new AdminApiClient.RestrictionInput(1, 2, 60, true, 1, java.util.List.of(), TODAY, TODAY.plusDays(6))));
        org.junit.jupiter.api.Assertions.assertTrue(conFechas.contains("\"vigenteDesde\":\"2026-11-19\""), conFechas);
        org.junit.jupiter.api.Assertions.assertTrue(conFechas.contains("\"vigenteHasta\":\"2026-11-25\""), conFechas);
    }
}
