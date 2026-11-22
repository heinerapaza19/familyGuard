package com.familyguard.agent;

import java.nio.file.Path;

/** Rutas modificables del agente, separadas de los binarios instalados. */
final class AgentPaths {
    private AgentPaths() { }

    static Path userData(String fileName) {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local")
                : Path.of(localAppData);
        return root.resolve("FamilyGuard").resolve("Agent").resolve(fileName);
    }
}
