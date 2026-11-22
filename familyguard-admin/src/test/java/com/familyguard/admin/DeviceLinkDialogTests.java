package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Textos del diálogo «Agregar dispositivo» (sin abrir ventanas JavaFX). */
class DeviceLinkDialogTests {
    @Test void confirmaLaVinculacionConElNombreDelEquipo() {
        assertEquals("✔ Dispositivo vinculado correctamente: LAPTOP-LUZ.", DeviceLinkDialog.statusMessage("VINCULADO", "LAPTOP-LUZ"));
        assertEquals("✔ Dispositivo vinculado correctamente.", DeviceLinkDialog.statusMessage("VINCULADO", null));
    }

    @Test void explicaLosDemasEstados() {
        assertTrue(DeviceLinkDialog.statusMessage("VENCIDO", null).contains("venció"));
        assertTrue(DeviceLinkDialog.statusMessage("REEMPLAZADO", null).contains("reemplazado"));
        assertTrue(DeviceLinkDialog.statusMessage("PENDIENTE", null).startsWith("Esperando"));
    }

    @Test void muestraElCodigoYElTiempoRestanteLegibles() {
        assertEquals("K7P2 M9QX", DeviceLinkDialog.format("K7P2M9QX"));
        assertEquals("9:05", DeviceLinkDialog.remaining(Duration.ofSeconds(545)));
        assertEquals("0:00", DeviceLinkDialog.remaining(Duration.ofSeconds(-3)));
    }
}
