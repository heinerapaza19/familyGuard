package com.familyguard.agent;

/** Entrada estable para launchers nativos classpath de jpackage. */
public final class AgentLauncher {
    private AgentLauncher() { }
    public static void main(String[] args) {
        // Modo auxiliar autorizado por UAC: actualiza solo el bloque de hosts y termina sin interfaz.
        if (args.length > 0 && WebFilterElevation.HELPER_ARGUMENT.equals(args[0])) {
            System.exit(WebFilterHelper.run(args));
            return;
        }
        AgentApplication.main(args);
    }
}
