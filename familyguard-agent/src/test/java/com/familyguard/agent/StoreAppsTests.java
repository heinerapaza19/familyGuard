package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Juegos y apps de la Microsoft Store: proceso real detrás de ApplicationFrameHost y rutas que cambian con cada versión. */
class StoreAppsTests {
    private static final String V1 = "C:\\Program Files\\WindowsApps\\GAMELOFTSA.ModernCombat5_1.0.6.0_x86__0pp20fcewvvtj\\ModernCombat5.exe";
    private static final String V2 = "C:\\Program Files\\WindowsApps\\GAMELOFTSA.ModernCombat5_1.0.7.0_x86__0pp20fcewvvtj\\ModernCombat5.exe";

    @Test
    void elMismoJuegoSeReconoceAunqueLaStoreLoActualice() {
        assertTrue(ExecutablePaths.same(V1, V2));
        assertTrue(ExecutablePaths.same(V1, V1.toLowerCase()));
        assertTrue(ExecutablePaths.isStoreApp(V1));
        // Otro editor u otro ejecutable del paquete no coincide.
        assertFalse(ExecutablePaths.same(V1, V2.replace("0pp20fcewvvtj", "8wekyb3d8bbwe")));
        assertFalse(ExecutablePaths.same(V1, V1.replace("ModernCombat5.exe", "Helper.exe")));
        // Programas normales: misma ruta exacta (sin distinguir mayúsculas).
        assertTrue(ExecutablePaths.same("C:\\Program Files\\Microsoft Office\\root\\Office16\\WINWORD.EXE",
                "c:/program files/microsoft office/root/office16/winword.exe"));
        assertFalse(ExecutablePaths.same("C:\\Games\\a.exe", "C:\\Games\\b.exe"));
        assertFalse(ExecutablePaths.same(null, V1));
    }

    @Test
    void laVentanaDeLaStoreSeAtribuyeAlJuegoYNoAApplicationFrameHost() {
        assertEquals(5120, ForegroundWindowDetector.resolveFrameHost(880, List.of(880L, 5120L)));
        assertEquals(880, ForegroundWindowDetector.resolveFrameHost(880, List.of(880L)));      // sin hijo: se mantiene
        assertEquals(880, ForegroundWindowDetector.resolveFrameHost(880, List.of()));
    }
}
