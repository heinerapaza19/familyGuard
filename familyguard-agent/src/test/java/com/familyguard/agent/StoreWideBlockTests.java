package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bloquear la Microsoft Store bloquea también sus apps y juegos, pero no las herramientas de Windows. */
class StoreWideBlockTests {
    private static final String STORE = "C:\\Program Files\\WindowsApps\\Microsoft.WindowsStore_22410.1401.5.0_x64__8wekyb3d8bbwe\\WinStore.App.exe";
    private static final String MC5 = "C:\\Program Files\\WindowsApps\\GAMELOFTSA.ModernCombat5_1.0.6.0_x86__0pp20fcewvvtj\\ModernCombat5.exe";
    private static final String MINECRAFT = "C:\\Program Files\\WindowsApps\\Microsoft.MinecraftUWP_1.21.0.0_x64__8wekyb3d8bbwe\\Minecraft.Windows.exe";
    private static final String CALC = "C:\\Program Files\\WindowsApps\\Microsoft.WindowsCalculator_11.2405.2.0_x64__8wekyb3d8bbwe\\CalculatorApp.exe";
    private static final String NOTEPAD = "C:\\Program Files\\WindowsApps\\Microsoft.WindowsNotepad_11.2407.9.0_x64__8wekyb3d8bbwe\\Notepad\\Notepad.exe";
    private static final String WORD = "C:\\Program Files\\Microsoft Office\\root\\Office16\\WINWORD.EXE";

    @Test
    void laReglaDeLaStoreAlcanzaASusJuegosYNoALasHerramientasDeWindows() {
        assertTrue(ExecutablePaths.isStoreClient(STORE));
        assertTrue(ExecutablePaths.covers(STORE, STORE));
        assertTrue(ExecutablePaths.covers(STORE, MC5));
        assertTrue(ExecutablePaths.covers(STORE, MINECRAFT));
        assertFalse(ExecutablePaths.covers(STORE, CALC));
        assertFalse(ExecutablePaths.covers(STORE, NOTEPAD));
        assertFalse(ExecutablePaths.covers(STORE, WORD));
        // Una regla de un juego concreto sigue alcanzando solo a ese juego.
        assertFalse(ExecutablePaths.covers(MC5, MINECRAFT));
        assertTrue(ExecutablePaths.covers(MC5, MC5));
    }

    @Test
    void elMotorCierraElJuegoDeLaStoreCuandoLaStoreEstaBloqueada(@TempDir Path dir) {
        RestrictionStore store = new RestrictionStore(dir.resolve("restrictions.bin"));
        List<String> closed = new ArrayList<>();
        ProcessRestrictionController processes = (pid, path, name) -> { closed.add(name + "|" + path); return new ProcessRestrictionController.Result(true, "cerrado"); };
        RestrictionEngine engine = new RestrictionEngine(store, new UsageTracker(new MemoryPersistence()), processes);
        long[] clock = {0};
        engine.useClock(() -> clock[0]);
        engine.replaceRules("America/Lima", List.of(new RestrictionRule(9, 90, "Microsoft Store", STORE, 24 * 3600, 0, true, 0, 1, List.of())));
        Instant t = Instant.parse("2026-11-20T11:00:00Z");
        ActivityObservation playing = new ActivityObservation(new ActiveApplication(5120, "ModernCombat5.exe", MC5), false, t);
        engine.evaluate(playing);   // el juego se abrió con la Store ya bloqueada: se cierra de inmediato
        assertEquals(List.of("ModernCombat5.exe|" + MC5), closed);
        // La Calculadora (herramienta de Windows) no se cierra.
        clock[0] = 90_000_000_000L;
        engine.evaluate(new ActivityObservation(new ActiveApplication(7000, "CalculatorApp.exe", CALC), false, t.plusSeconds(90)));
        assertEquals(1, closed.size());
    }

    private static final class MemoryPersistence implements UsagePersistence {
        private UsageState state = new UsageState(null, List.of());
        public UsageState snapshot() { return state; }
        public void persist(OpenUsage current, List<UsageEvent> pending) { state = new UsageState(current, List.copyOf(pending)); }
    }
}
