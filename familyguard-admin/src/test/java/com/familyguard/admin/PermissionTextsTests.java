package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class PermissionTextsTests {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final Instant NOW = Instant.parse("2026-11-19T20:00:00Z");   // 15:00 en Lima

    @Test
    void resumenEnLenguajeSencilloAntesDeConfirmar() {
        assertEquals("Luz podrá usar Minecraft 30 minutos más hoy en 2 computadoras. Termina a medianoche.",
                PermissionTexts.preview("Luz", PermissionTexts.EXTRA, "Minecraft", 30, 2));
        assertEquals("Luz podrá entrar a youtube.com durante 1 hora en 1 computadora.",
                PermissionTexts.preview("Luz", PermissionTexts.SITE, "youtube.com", 60, 1));
        assertEquals("Completa los datos para ver el resumen.", PermissionTexts.preview("Luz", PermissionTexts.EXTRA, null, 30, 1));
    }

    @Test
    void estadosYHorasSeMuestranClaros() {
        assertEquals("Pendiente (equipo sin conexión)", PermissionTexts.stateLabel("DESCONECTADO"));
        assertEquals("Cancelado", PermissionTexts.stateLabel("REVOCADO"));
        assertEquals("hoy a las 15:30", PermissionTexts.when(NOW.plusSeconds(1800), LIMA, NOW));
        assertEquals("mañana a las 00:00", PermissionTexts.when(Instant.parse("2026-11-20T05:00:00Z"), LIMA, NOW));
        assertEquals("1 hora 30 min", PermissionTexts.duration(90));
        assertEquals("Desbloquear aplicación", PermissionTexts.typeLabel(PermissionTexts.UNBLOCK));
    }
}
