package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Etapa 4: lógica del Administrador para restricciones masivas (sin JavaFX). */
class BulkRestrictionsTests {

    private static AdminApiClient.ApplicationInfo app(long id, String name, long device, String path) {
        return new AdminApiClient.ApplicationInfo(id, name, device, "Equipo " + device, 1L, "Luz", "1.0", "Editor",
                "REGISTRY", path, false, false, null, Instant.EPOCH, 0, null);
    }

    @Test void normalizaDominiosEliminaDuplicadosYSeparaInvalidos() {
        BulkRestrictions.ParsedDomains parsed = BulkRestrictions.parseDomains(
                "HTTPS://TikTok.com/foryou?x=1\ninstagram.com, tiktok.com;facebook.com.\n\nno-es-dominio 192.168.1.1");
        assertEquals(List.of("tiktok.com", "instagram.com", "facebook.com"), parsed.valid());
        assertEquals(List.of("no-es-dominio", "192.168.1.1"), parsed.invalid());
        assertTrue(BulkRestrictions.parseDomains("   ").valid().isEmpty());
    }

    @Test void identificaElEjecutableSinDistinguirMayusculas() {
        assertEquals("robloxplayerbeta.exe",
                BulkRestrictions.executable("C:\\Users\\luz\\AppData\\Local\\Roblox\\Versions\\v-1\\RobloxPlayerBeta.exe"));
        assertEquals("editor", BulkRestrictions.executable("/opt/editor"));
        assertEquals("", BulkRestrictions.executable(null));
    }

    @Test void aplicacionesDeReferenciaSoloDeEquiposSeleccionadosYUnaPorEjecutable() {
        List<AdminApiClient.ApplicationInfo> apps = List.of(
                app(1, "Roblox", 10, "C:\\R\\v1\\RobloxPlayerBeta.exe"),
                app(2, "Roblox", 11, "C:\\R\\v2\\RobloxPlayerBeta.exe"),
                app(3, "Editor", 11, "C:\\Apps\\editor.exe"),
                app(4, "Juego ajeno", 99, "C:\\Juego\\juego.exe"));
        List<AdminApiClient.ApplicationInfo> refs = BulkRestrictions.referenceApplications(apps, Set.of(10L, 11L));
        assertEquals(List.of(3L, 1L), refs.stream().map(AdminApiClient.ApplicationInfo::id).toList());
        assertEquals(2, BulkRestrictions.devicesWithExecutable(apps, Set.of(10L, 11L, 12L), "robloxplayerbeta.exe"));
        assertEquals(0, BulkRestrictions.devicesWithExecutable(apps, Set.of(10L), "juego.exe"));
    }

    @Test void etiquetasNoPresentanPendientesComoAplicados() {
        for (String pendiente : List.of("PENDIENTE_ENTREGA", "DESCONECTADO", "RECIBIDA", "ERROR", "CANCELADA",
                "APLICACION_NO_REGISTRADA", "APLICACION_AMBIGUA", "REVOCADO", "RETIRADA"))
            assertNotEquals("Aplicada", BulkRestrictions.stateLabel(pendiente), pendiente);
        assertEquals("Aplicada", BulkRestrictions.stateLabel("APLICADA"));
        assertEquals("Desconectado, pendiente de sincronización", BulkRestrictions.stateLabel("DESCONECTADO"));
        assertEquals("Recibida por el Agente", BulkRestrictions.stateLabel("RECIBIDA"));
        assertEquals("Bloquear sitio web", BulkRestrictions.typeLabel("BLOQUEAR_SITIO"));
    }

    @Test void confirmacionIndicaEquiposConectadosYDesconectados() {
        assertEquals("Vas a bloquear tiktok.com en 5 computadoras (3 conectadas, 2 desconectadas). Las desconectadas quedarán"
                        + " pendientes hasta que vuelvan a comunicarse. ¿Deseas continuar?",
                BulkRestrictions.confirmation("bloquear", List.of("tiktok.com"), 5, 3));
        assertEquals("Vas a bloquear 2 sitios (tiktok.com, instagram.com) en 1 computadora (1 conectada, 0 desconectadas)."
                        + " ¿Deseas continuar?",
                BulkRestrictions.confirmation("bloquear", List.of("tiktok.com", "instagram.com"), 1, 1));
        assertEquals("2 h", BulkRestrictions.duration(120));
        assertEquals("1 h 30 min", BulkRestrictions.duration(90));
        assertEquals("45 min", BulkRestrictions.duration(45));
    }

    @Test void avisosDeVistaPreviaYCancelacion() {
        AdminApiClient.BulkPreview preview = new AdminApiClient.BulkPreview("LIMITAR_APLICACION", "roblox.exe", 2, 1, 1, 1, 0, 1, "",
                List.of(new AdminApiClient.BulkPreviewItem(1, "Laptop Luz 1", "Luz", true, "PROGRAMADA", ""),
                        new AdminApiClient.BulkPreviewItem(2, "Laptop Luz 2", "Luz", false, "APLICACION_NO_REGISTRADA", "No detectado")));
        assertEquals(List.of("Laptop Luz 2 · roblox.exe: Aplicación no registrada en el equipo — No detectado"),
                BulkRestrictions.previewNotes(List.of(preview)));
        AdminApiClient.BulkOperation bloqueo = new AdminApiClient.BulkOperation(1, "u", "BLOQUEAR_SITIO", "tiktok.com", "",
                "REGISTRADA", Instant.EPOCH, null, 2, Map.of(), false, "", List.of());
        assertTrue(BulkRestrictions.cancellable(bloqueo));
        assertFalse(BulkRestrictions.cancellable(new AdminApiClient.BulkOperation(1, "u", "LIMITAR_APLICACION", "x", "",
                "REGISTRADA", Instant.EPOCH, null, 2, Map.of(), false, "", List.of())));
        assertFalse(BulkRestrictions.cancellable(new AdminApiClient.BulkOperation(1, "u", "BLOQUEAR_SITIO", "x", "",
                "CANCELADA", Instant.EPOCH, null, 2, Map.of(), false, "", List.of())));
    }
}
