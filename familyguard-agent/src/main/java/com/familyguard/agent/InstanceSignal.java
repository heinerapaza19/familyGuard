package com.familyguard.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Aviso entre instancias: si FamilyGuard Agente ya está abierto, la segunda copia deja una solicitud y termina;
 * la primera la detecta y muestra su ventana. Así nunca hay dos Agentes supervisando a la vez.
 */
final class InstanceSignal {
    private final Path file;

    InstanceSignal() { this(AgentPaths.userData("show-window.request")); }
    InstanceSignal(Path file) { this.file = file; }

    /** Segunda instancia: pide a la primera que se muestre. */
    boolean request() {
        try { Files.createDirectories(file.toAbsolutePath().getParent()); Files.writeString(file, "mostrar"); return true; }
        catch (Exception error) { return false; }
    }

    /** Primera instancia: {@code true} una sola vez por cada solicitud recibida. */
    boolean consume() {
        try { return Files.deleteIfExists(file); }
        catch (Exception error) { return false; }
    }
}
