package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NavigationModelTests {
    @Test
    void todasLasSeccionesPuedenSerSeleccionadas() {
        NavigationModel model = new NavigationModel();
        assertEquals(DashboardSection.HOME, model.current());
        for (DashboardSection section : DashboardSection.values()) {
            model.navigate(section);
            assertEquals(section, model.current());
        }
    }

    @Test
    void noAdmiteUnaSeccionNula() {
        NavigationModel model = new NavigationModel();
        assertThrows(NullPointerException.class, () -> model.navigate(null));
    }
}
