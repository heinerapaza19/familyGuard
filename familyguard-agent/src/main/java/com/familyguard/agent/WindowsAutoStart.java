package com.familyguard.agent;

import java.nio.file.Path;

/** Inicio visible por usuario mediante HKCU Run; no requiere elevación ni crea persistencia oculta. */
final class WindowsAutoStart {
    private static final String KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE = "FamilyGuardAgent";

    boolean available() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows") && executable() != null;
    }

    boolean enabled() {
        if (!available()) return false;
        return run("reg", "query", KEY, "/v", VALUE) == 0;
    }

    void setEnabled(boolean enabled) {
        if (!available()) throw new IllegalStateException("Disponible al ejecutar el agente instalado en Windows.");
        int exit = enabled
                ? run("reg", "add", KEY, "/v", VALUE, "/t", "REG_SZ", "/d", command(executable()), "/f")
                : run("reg", "delete", KEY, "/v", VALUE, "/f");
        if (exit != 0 && (enabled || this.enabled()))
            throw new IllegalStateException("Windows no permitió actualizar el inicio automático.");
    }

    /** Comando registrado en HKCU Run, o {@code null} si no hay ninguno. */
    String registeredCommand() {
        if (!available()) return null;
        try {
            Process process = new ProcessBuilder("reg", "query", KEY, "/v", VALUE).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
            if (process.waitFor() != 0) return null;
            return parseRegistered(output);
        } catch (Exception error) { return null; }
    }

    /** Valor de «reg query»: la línea «FamilyGuardAgent    REG_SZ    "C:\...exe" --autostart». */
    static String parseRegistered(String output) {
        for (String line : output.split("\\R")) {
            String t = line.strip();
            if (!t.startsWith(VALUE)) continue;
            int type = t.indexOf("REG_SZ");
            if (type >= 0) return t.substring(type + "REG_SZ".length()).strip();
        }
        return null;
    }

    /** Estado comprensible para Ajustes: activo, desactivado, apuntando a otra ruta o no disponible. */
    static String describe(boolean available, String registered, String expected, boolean wanted) {
        if (!available) return "No disponible: el inicio automático funciona con el Agente instalado en Windows";
        if (registered == null) return wanted ? "No activo: Windows no tiene registrado el inicio automático" : "Desactivado";
        if (!ascii(registered).equalsIgnoreCase(ascii(expected))) return "Registrado con otra ruta (se corrige al activarlo)";
        return "Activado: FamilyGuard se abrirá al iniciar sesión en Windows";
    }

    /** «reg» escribe en la página de códigos de la consola: los caracteres no ASCII no se comparan. */
    private static String ascii(String value) { return value.replaceAll("[^\\x20-\\x7E]", "?"); }

    String describe(boolean wanted) {
        return describe(available(), registeredCommand(), configuredCommand(), wanted);
    }

    String configuredCommand() {
        Path value = executable();
        return value == null ? "" : command(value);
    }

    private Path executable() {
        try {
            Path path = Path.of(ProcessHandle.current().info().command().orElse(""));
            return path.toString().toLowerCase().endsWith(".exe") ? path.toAbsolutePath() : null;
        } catch (Exception ignored) { return null; }
    }

    static String command(Path path) { return "\"" + path + "\" --autostart"; }
    private static int run(String... command) {
        try { return new ProcessBuilder(command).redirectErrorStream(true).start().waitFor(); }
        catch (Exception exception) { return -1; }
    }
}
