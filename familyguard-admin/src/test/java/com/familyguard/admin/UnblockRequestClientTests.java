package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Lectura de las solicitudes de desbloqueo que envía el servidor al Administrador. */
class UnblockRequestClientTests {
    @Test
    void leeLaSolicitudPendienteConFoto() throws Exception {
        var json = new ObjectMapper().readTree("""
                {"id":5,"tipo":"APP","objetivo":"Google Chrome","menorId":2,"menor":"Luz","dispositivoId":9,"dispositivo":"DESKTOP-CEUCT1P",
                 "mensaje":"Necesito Chrome para la tarea","tieneFoto":true,"estado":"PENDIENTE","respuesta":null,
                 "minutosAprobados":null,"creadoEn":"2026-11-20T11:00:00Z","resueltoEn":null}
                """);
        var r = AdminApiClient.unblockRequest(json);
        assertTrue(r.pending());
        assertTrue(r.hasPhoto());
        assertEquals("Luz", r.childName());
        assertEquals("Google Chrome", r.target());
        assertNull(r.reply());
        assertNull(r.minutes());
        assertEquals(java.time.Instant.parse("2026-11-20T11:00:00Z"), r.createdAt());
    }
}
