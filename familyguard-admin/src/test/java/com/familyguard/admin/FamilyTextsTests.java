package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** El padre navega sin hijos registrados: solo sugerencias discretas, nunca un registro obligatorio. */
class FamilyTextsTests {
    private static AdminApiClient.ChildProfile hijo(long id, boolean activo) {
        return new AdminApiClient.ChildProfile(id, "Hijo " + id, null, "", activo);
    }

    @Test void menuUsaMisHijosYMisDispositivos() {
        assertEquals("Mis hijos", DashboardSection.CHILDREN.label());
        assertEquals("Mis dispositivos", DashboardSection.DEVICES.label());
        assertEquals("Agregar hijo", FamilyTexts.ADD_CHILD);
    }

    @Test void sinHijosSoloSeIndicaDondeAgregarlos() {
        String texto = FamilyTexts.linkWithoutChildren(List.of());
        assertTrue(texto.startsWith("Todavía no has agregado hijos."));
        assertTrue(texto.contains("«Mis hijos»"));
    }

    @Test void conHijosActivosNoHayIndicacion() {
        assertNull(FamilyTexts.linkWithoutChildren(List.of(hijo(1, true), hijo(2, false))));
    }

    @Test void conHijosDesactivadosSeSugiereReactivar() {
        assertTrue(FamilyTexts.linkWithoutChildren(List.of(hijo(1, false))).contains("Reactiva"));
    }
}
