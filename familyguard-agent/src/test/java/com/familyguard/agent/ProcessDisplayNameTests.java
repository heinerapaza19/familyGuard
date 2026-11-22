package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ProcessDisplayNameTests {
    @Test void usaTituloLegibleParaHostDeAplicacionesUwp() {
        assertEquals("Modern Combat 5: eSports FPS",
                ProcessDetector.resolveDisplayName("ApplicationFrameHost.exe", "Modern Combat 5: eSports FPS"));
    }
    @Test void conservaNombreDeProcesosNormales() {
        assertEquals("chrome.exe", ProcessDetector.resolveDisplayName("chrome.exe", "FamilyGuard"));
    }
}
