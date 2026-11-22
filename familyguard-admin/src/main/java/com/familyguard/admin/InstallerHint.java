package com.familyguard.admin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Evita bloquear el programa equivocado: el Agente cierra solo el ejecutable exacto de la regla, y el instalador de un
 * juego («… Installer.exe», «setup.exe», «unins000.exe») no es el juego.
 */
final class InstallerHint {
    private static final List<String> MARKERS = List.of("installer", "install", "setup", "unins", "uninstall", "updater", "update.exe", "bootstrapper");
    private static final Set<String> NOISE = Set.of("installer", "install", "setup", "uninstall", "unins000", "updater", "update", "exe",
            "edition", "version", "game", "games", "launcher", "windows", "microsoft", "corporation", "the", "for");
    private InstallerHint() { }

    static boolean isInstaller(String name, String path) {
        String text = ((name == null ? "" : name) + " " + fileName(path)).toLowerCase(Locale.ROOT);
        return MARKERS.stream().anyMatch(text::contains);
    }

    /** Aplicaciones del mismo equipo que comparten una palabra significativa con el instalador (p. ej. «Combat»). */
    static List<String> related(String installerName, List<String> candidates) {
        Set<String> words = words(installerName);
        List<String> found = new ArrayList<>();
        for (String c : candidates) {
            if (c == null || isInstaller(c, null) || c.equalsIgnoreCase(installerName)) continue;
            String compact = c.toLowerCase(Locale.ROOT).replace(".exe", "").replaceAll("[^\\p{L}\\p{N}]", "");
            for (String w : words) if (compact.contains(w)) { found.add(c); break; }   // «ModernCombat5.exe» también
        }
        return found;
    }

    private static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String w : text.toLowerCase(Locale.ROOT).replace(".exe", "").split("[^\\p{L}\\p{N}]+"))
            if (w.length() >= 4 && !NOISE.contains(w)) out.add(w);
        return out;
    }

    private static String fileName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Contenedor de ventanas de la Microsoft Store: no es el juego y Windows lo protege. */
    static boolean isStoreHost(String name, String path) {
        String text = ((name == null ? "" : name) + " " + fileName(path)).toLowerCase(Locale.ROOT);
        return text.contains("applicationframehost") || text.contains("wwahost");
    }

    static boolean isStoreApp(String path) { return path != null && path.toLowerCase(Locale.ROOT).replace('/', '\\').contains("\\windowsapps\\"); }

    /** La propia Microsoft Store (WinStore.App.exe): bloquearla bloquea también sus apps y juegos. */
    static boolean isStoreClient(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT).replace('/', '\\');
        return p.contains("\\windowsapps\\microsoft.windowsstore_");
    }
}
