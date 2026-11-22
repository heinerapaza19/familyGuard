package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** «Justificar»: validación antes de enviar, lectura de la respuesta del servidor y textos para el hijo. */
class UnblockRequestTests {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    @Test
    void validaMensajeYFoto() {
        assertEquals("Lo necesito para la tarea", UnblockRequestRules.message("  Lo necesito para la tarea  "));
        assertThrows(IllegalArgumentException.class, () -> UnblockRequestRules.message("ok"));
        assertThrows(IllegalArgumentException.class, () -> UnblockRequestRules.message("x".repeat(501)));
        UnblockRequestRules.photo(PNG);
        UnblockRequestRules.photo(null);                                   // la foto es opcional
        assertEquals("image/png", UnblockRequestRules.type(PNG));
        assertThrows(IllegalArgumentException.class, () -> UnblockRequestRules.photo("no es imagen".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> UnblockRequestRules.photo(new byte[UnblockRequestRules.MAX_PHOTO + 1]));
    }

    @Test
    void leeLaRespuestaYMuestraElEstado() throws Exception {
        var json = new ObjectMapper().readTree("""
                {"id":7,"tipo":"WEB","aplicacionId":null,"sitioId":12,"objetivo":"youtube.com","estado":"APROBADA",
                 "respuesta":"Solo para la tarea","minutosAprobados":30,"creadoEn":"2026-11-20T11:00:00Z","resueltoEn":"2026-11-20T11:05:00Z"}
                """);
        var r = AgentApiClient.unblockRequest(json);
        assertEquals(12L, r.siteId());
        assertNull(r.applicationId());
        assertEquals("Solicitud aprobada: desbloqueado por 30 min · «Solo para la tarea»", UnblockRequestRules.status(r));
        assertEquals("Tu solicitud fue aprobada: youtube.com queda desbloqueado por 30 minutos.", UnblockRequestRules.notification(r));
        var pending = new AgentApiClient.UnblockRequest(8, "APP", 3L, null, "Chrome", "PENDIENTE", null, null, null, null);
        assertTrue(pending.pending());
        assertEquals("Solicitud enviada · esperando la respuesta de tu padre o madre", UnblockRequestRules.status(pending));
        assertNull(UnblockRequestRules.notification(pending));
        var rejected = new AgentApiClient.UnblockRequest(9, "APP", 3L, null, "Chrome", "RECHAZADA", "Hoy no", null, null, null);
        assertEquals("Tu solicitud para Chrome fue rechazada: «Hoy no».", UnblockRequestRules.notification(rejected));
    }

    @Test
    void cadaElementoBloqueadoSabeQueSePideDesbloquear() {
        var web = new DetectionModels.WebRule(12, "youtube.com", false, "BLOQUEAR", true, null, null, 1, java.util.List.of());
        var items = BlockedSummary.sites(java.util.List.of(web), r -> true, java.time.Instant.now(), java.time.ZoneId.of("America/Lima"));
        assertEquals("WEB", items.get(0).kind());
        assertEquals(12L, items.get(0).referenceId());
    }
}
