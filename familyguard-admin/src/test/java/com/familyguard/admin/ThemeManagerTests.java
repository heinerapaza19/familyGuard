package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

class ThemeManagerTests {
    @Test
    void changesThemeWithoutRestartAndKeepsBothOptions() {
        ThemeManager manager = ThemeManager.get();
        ThemeManager.Theme previous = manager.current();
        ThemeManager.Theme next = previous == ThemeManager.Theme.LIGHT
                ? ThemeManager.Theme.DARK : ThemeManager.Theme.LIGHT;
        try {
            manager.setTheme(next);
            assertEquals(next, manager.current());
            assertEquals("Claro", ThemeManager.Theme.LIGHT.toString());
            assertEquals("Oscuro", ThemeManager.Theme.DARK.toString());
        } finally {
            manager.setTheme(previous);
        }
    }

    @Test
    void packagesBothThemeStylesheets() {
        assertNotNull(ThemeManager.class.getResource("familyguard.css"));
        assertNotNull(ThemeManager.class.getResource("familyguard-light.css"));
        assertNotNull(FamilyIllustration.class.getResource("images/family-cutout.png"));
    }
}
