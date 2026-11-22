package com.familyguard.agent;

import java.util.List;

/**
 * Desinstalación autorizada y transparente: FamilyGuard no impide ni oculta la desinstalación de Windows. Con el
 * PIN del padre (o una autorización remota) el Agente quita su inicio automático, retira su bloque del archivo
 * hosts y abre «Aplicaciones instaladas» de Windows, donde se completa la desinstalación de forma normal.
 */
final class UninstallAssistant {
    static final List<String> STEPS = List.of(
            "Se avisa al padre en FamilyGuard Administrador.",
            "Se detiene la supervisión y se quita el inicio automático de este usuario.",
            "Se retiran los sitios bloqueados por FamilyGuard (Windows puede pedir permiso de administrador).",
            "Se abre «Aplicaciones instaladas» de Windows: busca «FamilyGuard Agente» y pulsa «Desinstalar».");

    static String explanation() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < STEPS.size(); i++) text.append(i + 1).append(". ").append(STEPS.get(i)).append('\n');
        return text.toString().strip();
    }

    /** Abre la página de aplicaciones de Windows; si no existe (Windows antiguo), el Panel de control. */
    static boolean openWindowsApps() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) return false;
        try { new ProcessBuilder("cmd", "/c", "start", "", "ms-settings:appsfeatures").start(); return true; }
        catch (Exception first) {
            try { new ProcessBuilder("control", "appwiz.cpl").start(); return true; }
            catch (Exception ignored) { return false; }
        }
    }
}
