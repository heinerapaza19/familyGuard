package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Primera ejecución, instancia única e inicio automático visible (lo que se puede probar fuera de Windows). */
class FirstRunAndAutoStartTests {
    @Test
    void unaSegundaCopiaPideMostrarLaVentanaUnaSolaVez() throws Exception {
        Path dir = Files.createTempDirectory("fg-instancia");
        Path lockFile = dir.resolve("agent.lock");
        try (SingleInstanceLock first = SingleInstanceLock.acquire(lockFile)) {
            assertThrows(java.io.IOException.class, () -> SingleInstanceLock.acquire(lockFile), "no puede haber dos Agentes");
            InstanceSignal segunda = new InstanceSignal(dir.resolve("show-window.request"));
            InstanceSignal primera = new InstanceSignal(dir.resolve("show-window.request"));
            assertFalse(primera.consume());
            assertTrue(segunda.request());
            assertTrue(primera.consume(), "la primera instancia muestra su ventana");
            assertFalse(primera.consume(), "solo una vez por solicitud");
        }
        try (SingleInstanceLock again = SingleInstanceLock.acquire(lockFile)) { assertNotNull(again); }
    }

    @Test
    void elEstadoDelInicioAutomaticoSeLeeDeWindows() {
        String salida = """

                HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run
                    FamilyGuardAgent    REG_SZ    "C:\\Program Files\\FamilyGuard Agente\\FamilyGuard Agente.exe" --autostart

                """;
        String esperado = WindowsAutoStart.command(Path.of("C:\\Program Files\\FamilyGuard Agente\\FamilyGuard Agente.exe"));
        String registrado = WindowsAutoStart.parseRegistered(salida);
        assertEquals(esperado, registrado);
        assertTrue(WindowsAutoStart.describe(true, registrado, esperado, true).startsWith("Activado"));
        assertTrue(WindowsAutoStart.describe(true, null, esperado, true).startsWith("No activo"));
        assertEquals("Desactivado", WindowsAutoStart.describe(true, null, esperado, false));
        assertTrue(WindowsAutoStart.describe(true, "\"D:\\otra\\ruta.exe\" --autostart", esperado, true).startsWith("Registrado con otra ruta"));
        assertTrue(WindowsAutoStart.describe(false, null, "", true).startsWith("No disponible"));
        // «reg» usa la página de códigos de la consola: una tilde mal leída no se toma como otra ruta.
        String conTilde = "\"C:\\Users\\José\\AppData\\Local\\FamilyGuard Agente\\FamilyGuard Agente.exe\" --autostart";
        assertTrue(WindowsAutoStart.describe(true, conTilde.replace('é', '\uFFFD'), conTilde, true).startsWith("Activado"));
    }
}
