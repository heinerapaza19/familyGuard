package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Vigencia por fechas de las restricciones de aplicaciones: fuera de las fechas no se restringe. */
class RestrictionValidityTests {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 19, 17, 0, 0, 0, LIMA);

    private static RestrictionRule chrome(LocalDate from, LocalDate until) {
        return new RestrictionRule(1, 1, "Google Chrome", "C:\\Apps\\chrome.exe", 24 * 3600, 0, true, 0, 1, List.of(), from, until);
    }

    @Test
    void soloBloqueaDentroDeLasFechasIncluidas() {
        RuleEvaluator evaluator = new RuleEvaluator();
        LocalDate today = NOW.toLocalDate();
        assertTrue(evaluator.evaluate(chrome(today, today), 0, NOW).blocked());                      // inicio y fin incluidos
        assertTrue(evaluator.evaluate(chrome(today.minusDays(3), today.plusDays(3)), 0, NOW).blocked());
        assertFalse(evaluator.evaluate(chrome(today.plusDays(1), today.plusDays(5)), 0, NOW).blocked());    // aún no empieza
        assertFalse(evaluator.evaluate(chrome(today.minusDays(5), today.minusDays(1)), 0, NOW).blocked());  // ya terminó
        assertEquals("FUERA_DE_VIGENCIA", evaluator.evaluate(chrome(null, today.minusDays(1)), 0, NOW).reason());
        assertTrue(evaluator.evaluate(chrome(null, null), 0, NOW).blocked());                         // sin fechas: siempre
        // Reglas guardadas antes de este cambio (sin fechas) siguen funcionando igual.
        assertTrue(evaluator.evaluate(new RestrictionRule(1, 1, "X", "C:\\x.exe", 24 * 3600, 0, true, 0, 1, List.of()), 0, NOW).blocked());
    }

    @Test
    void tarjetaBloqueadosOcultaLasTerminadasYAvisaLasFuturas() {
        LocalDate today = NOW.toLocalDate();
        var items = BlockedSummary.applications(List.of(
                chrome(today.minusDays(5), today.minusDays(1)),
                new RestrictionRule(2, 2, "Roblox", "C:\\r.exe", 24 * 3600, 0, true, 0, 1, List.of(), today.plusDays(2), today.plusDays(4))),
                r -> 0, NOW, TemporaryPermissions.NONE);
        assertEquals(1, items.size());
        assertEquals("Roblox", items.get(0).name());
        assertEquals("Empieza el 21/11/2026", items.get(0).state());
        assertEquals("Bloqueo total · del 21/11/2026 al 23/11/2026", items.get(0).detail());
    }

    @Test
    void leeLasFechasQueEnviaElServidor() throws Exception {
        var json = new ObjectMapper().readTree("{\"vigenteDesde\":\"2026-11-19\",\"vigenteHasta\":null,\"otra\":\"x\"}");
        assertEquals(LocalDate.of(2026, 11, 19), AgentApiClient.localDate(json, "vigenteDesde"));
        assertNull(AgentApiClient.localDate(json, "vigenteHasta"));
        assertNull(AgentApiClient.localDate(json, "noExiste"));
        assertNull(AgentApiClient.localDate(json, "otra"));
    }
}
