package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Alertas: nombres legibles, categoría, gravedad y fecha relativa. */
class AlertStyleTests {
    @Test
    void nombresYCategorias() {
        assertEquals("Sin conexión", AlertStyle.name("SIN_CONEXION"));
        assertEquals("Desinstalación autorizada", AlertStyle.name("DESINSTALACION_AUTORIZADA"));
        assertEquals("Codigo vinculacion", AlertStyle.name("CODIGO_VINCULACION"));   // desconocido: legible
        assertEquals(AlertStyle.Category.SECURITY, AlertStyle.category("PIN_INCORRECTO"));
        assertEquals(AlertStyle.Category.CONNECTION, AlertStyle.category("SIN_CONEXION"));
        assertEquals(AlertStyle.Category.BLOCKS, AlertStyle.category("RESTRICCION_ELIMINADA"));
        assertEquals(AlertStyle.Category.WEB, AlertStyle.category("SITIO_WEB_CREADO"));
    }

    @Test
    void gravedadYResultado() {
        assertEquals(AlertStyle.Severity.DANGER, AlertStyle.severity("PIN_INCORRECTO", "ERROR"));
        assertEquals(AlertStyle.Severity.WARNING, AlertStyle.severity("DESINSTALACION_AUTORIZADA", "EXITO"));
        assertEquals(AlertStyle.Severity.SUCCESS, AlertStyle.severity("BLOQUEO", "EXITO"));
        assertEquals("Correcto", AlertStyle.result("EXITO"));
        assertEquals("Error", AlertStyle.result("ERROR"));
    }

    @Test
    void fechaRelativa() {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        assertEquals("ahora", AlertStyle.relative(now.minusSeconds(20), now));
        assertEquals("hace 46 min", AlertStyle.relative(now.minusSeconds(46 * 60), now));
        assertEquals("hace 5 h", AlertStyle.relative(now.minusSeconds(5 * 3600 + 100), now));
        assertEquals("ayer", AlertStyle.relative(now.minusSeconds(30 * 3600), now));
        assertEquals("hace 3 días", AlertStyle.relative(now.minusSeconds(3 * 86400 + 10), now));
    }

    @Test
    void lasSolicitudesDelHijoSeVenEnRojoHastaResponderse() {
        assertEquals(AlertStyle.Category.REQUESTS, AlertStyle.category("SOLICITUD_DESBLOQUEO"));
        assertEquals("Solicitud de desbloqueo", AlertStyle.name("SOLICITUD_DESBLOQUEO"));
        assertEquals(AlertStyle.Severity.DANGER, AlertStyle.severity("SOLICITUD_DESBLOQUEO", "INFORMATIVO"));
        assertEquals(AlertStyle.Severity.SUCCESS, AlertStyle.severity("SOLICITUD_APROBADA", "EXITO"));
        assertEquals(AlertStyle.Severity.INFO, AlertStyle.severity("SOLICITUD_RECHAZADA", "RECHAZADO"));
        assertEquals("Aviso", AlertStyle.result("INFORMATIVO"));
    }
}
