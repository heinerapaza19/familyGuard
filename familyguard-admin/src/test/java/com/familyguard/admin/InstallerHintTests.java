package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Bloquear el instalador no bloquea el juego: se avisa y se sugiere el ejecutable real. */
class InstallerHintTests {
    @Test
    void reconoceInstaladores() {
        assertTrue(InstallerHint.isInstaller("Modern Combat 5 Installer.exe", "C:\\Users\\Luz\\Downloads\\Modern Combat 5 Installer.exe"));
        assertTrue(InstallerHint.isInstaller("Juego", "C:\\Games\\Juego\\unins000.exe"));
        assertTrue(InstallerHint.isInstaller("setup.exe", null));
        assertFalse(InstallerHint.isInstaller("Google Chrome", "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"));
        assertFalse(InstallerHint.isInstaller("ModernCombat5.exe", "C:\\Program Files\\WindowsApps\\Gameloft\\ModernCombat5.exe"));
    }

    @Test
    void sugiereElEjecutableDelJuego() {
        var related = InstallerHint.related("Modern Combat 5 Installer.exe",
                List.of("ModernCombat5.exe", "Modern Combat 5", "chrome.exe", "Modern Combat 5 Installer.exe", "explorer.exe"));
        assertEquals(List.of("ModernCombat5.exe", "Modern Combat 5"), related);
        assertEquals(List.of(), InstallerHint.related("setup.exe", List.of("chrome.exe")));
    }

    @Test
    void reconoceAppsDeLaStoreYSuContenedor() {
        assertTrue(InstallerHint.isStoreHost("ApplicationFrameHost.exe", "C:\\Windows\\System32\\ApplicationFrameHost.exe"));
        assertFalse(InstallerHint.isStoreHost("ModernCombat5.exe", null));
        assertTrue(InstallerHint.isStoreApp("C:\\Program Files\\WindowsApps\\GAMELOFTSA.ModernCombat5_1.0.6.0_x86__0pp20fcewvvtj\\ModernCombat5.exe"));
        assertFalse(InstallerHint.isStoreApp("C:\\Program Files\\Microsoft Office\\root\\Office16\\WINWORD.EXE"));
    }

    @Test
    void reconoceLaPropiaMicrosoftStore() {
        assertTrue(InstallerHint.isStoreClient("C:\\Program Files\\WindowsApps\\Microsoft.WindowsStore_22410.1401.5.0_x64__8wekyb3d8bbwe\\WinStore.App.exe"));
        assertFalse(InstallerHint.isStoreClient("C:\\Program Files\\WindowsApps\\GAMELOFTSA.ModernCombat5_1.0.6.0_x86__0pp20fcewvvtj\\ModernCombat5.exe"));
    }
}
