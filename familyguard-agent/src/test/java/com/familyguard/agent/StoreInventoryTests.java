package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.InstalledApplication;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Inventario de la Microsoft Store con el ejecutable real: sin él, una regla del padre no podría aplicarse. */
class StoreInventoryTests {
    @Test
    void cadaJuegoDeLaStoreLlegaConSuEjecutableYEditor() throws Exception {
        String json = """
                [{"Name":"Modern Combat 5","AppID":"GAMELOFTSA.ModernCombat5_0pp20fcewvvtj!App",
                  "Path":"C:\\\\Program Files\\\\WindowsApps\\\\GAMELOFTSA.ModernCombat5_1.0.6.0_x86__0pp20fcewvvtj\\\\ModernCombat5.exe",
                  "Version":"1.0.6.0","Publisher":"CN=GAMELOFT SA, O=GAMELOFT SA, L=Paris, C=FR"},
                 {"Name":"Asphalt 9","AppID":"GAMELOFTSA.Asphalt9_0pp20fcewvvtj!App","Path":null,"Version":null,"Publisher":null},
                 {"Name":"Configuración","AppID":"windows.immersivecontrolpanel_cw5n1h2txyewy!microsoft.windows.immersivecontrolpanel"}]
                """;
        List<InstalledApplication> found = new ArrayList<>();
        new ObjectMapper().readTree(json).forEach(node -> InstalledApplicationDetector.addStoreApp(found, node));
        assertEquals(3, found.size());
        InstalledApplication mc5 = found.get(0);
        assertEquals("Modern Combat 5", mc5.name());
        assertTrue(mc5.path().endsWith("\\ModernCombat5.exe"));
        assertEquals("GAMELOFT SA", mc5.publisher());
        assertEquals("1.0.6.0", mc5.version());
        assertEquals("Microsoft Store", mc5.installType());
        // La ruta del inventario coincide con la del proceso en ejecución, aunque la Store actualice el juego.
        assertTrue(ExecutablePaths.same(mc5.path(),
                "C:\\Program Files\\WindowsApps\\GAMELOFTSA.ModernCombat5_1.0.7.0_x86__0pp20fcewvvtj\\ModernCombat5.exe"));
        assertNull(found.get(1).path());   // si Windows no informa el ejecutable, no se inventa
    }

    @Test
    void nombreDelEditor() {
        assertEquals("GAMELOFT SA", InstalledApplicationDetector.publisherName("CN=GAMELOFT SA, O=GAMELOFT SA, C=FR"));
        assertEquals("Microsoft Store", InstalledApplicationDetector.publisherName(null));
    }
}
