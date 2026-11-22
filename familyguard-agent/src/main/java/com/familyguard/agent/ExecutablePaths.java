package com.familyguard.agent;

import java.util.Locale;

/**
 * Comparación de ejecutables para las reglas. Las apps y juegos de la Microsoft Store viven en
 * {@code C:\Program Files\WindowsApps\<Nombre>_<Versión>_<Arquitectura>__<Editor>\…}: al actualizarse cambia la carpeta de
 * versión, así que para ellas se compara el nombre del paquete, el editor y el ejecutable, no la versión.
 */
final class ExecutablePaths {
    private static final String STORE = "\\windowsapps\\";
    private ExecutablePaths() { }

    static boolean same(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        String x = normalize(a), y = normalize(b);
        if (x.equals(y)) return true;
        String kx = storeKey(x), ky = storeKey(y);
        return kx != null && kx.equals(ky);
    }

    static boolean isStoreApp(String path) { return path != null && normalize(path).contains(STORE); }

    /**
     * Herramientas de Windows que también se instalan desde la Store y nunca se bloquean al bloquear la Store
     * (Calculadora, Bloc de notas, Fotos, Paint, Recortes, Terminal, Cámara, Seguridad de Windows, componentes del sistema).
     */
    private static final java.util.List<String> PROTECTED = java.util.List.of(
            "microsoft.windowscalculator", "microsoft.windowsnotepad", "microsoft.windowsterminal", "microsoft.windows.",
            "microsoft.screensketch", "microsoft.paint", "microsoft.windowscamera", "microsoft.windowsalarms",
            "microsoft.windowssoundrecorder", "microsoft.desktopappinstaller", "microsoft.sechealthui", "microsoft.windowsappruntime",
            "microsoft.vclibs", "microsoft.ui.xaml", "microsoft.net.", "microsoft.storepurchaseapp", "microsoft.gamingservices",
            "microsoft.lockapp", "microsoft.accounts", "microsoft.aad", "microsoftwindows.", "windows.", "familyguard");

    /** La regla es sobre la propia Microsoft Store (WinStore.App.exe). */
    static boolean isStoreClient(String path) {
        String key = path == null ? null : storeKey(normalize(path));
        return key != null && key.startsWith("microsoft.windowsstore__");
    }

    /** App o juego de la Store que se bloquea junto con la Store (no es una herramienta protegida de Windows). */
    static boolean isBlockableStoreApp(String path) {
        String key = path == null ? null : storeKey(normalize(path));
        if (key == null) return false;
        String pkg = key.substring(0, key.indexOf("__"));
        return PROTECTED.stream().noneMatch(pkg::startsWith);
    }

    /** La regla alcanza a este proceso: mismo ejecutable, o la regla bloquea la Microsoft Store y el proceso es de la Store. */
    static boolean covers(String rulePath, String processPath) {
        if (same(rulePath, processPath)) return true;
        return isStoreClient(rulePath) && isBlockableStoreApp(processPath);
    }

    /** «gameloft.moderncombat5__abc123\moderncombat5.exe» o {@code null} si no es una app de la Store. */
    static String storeKey(String normalizedPath) {
        int at = normalizedPath.indexOf(STORE);
        if (at < 0) return null;
        String rest = normalizedPath.substring(at + STORE.length());
        int slash = rest.indexOf('\\');
        if (slash <= 0) return null;
        String folder = rest.substring(0, slash), inside = rest.substring(slash + 1);
        int first = folder.indexOf('_'), publisher = folder.lastIndexOf("__");
        if (first <= 0 || publisher < first) return null;
        return folder.substring(0, first) + folder.substring(publisher) + "\\" + inside;
    }

    private static String normalize(String path) {
        String p = path.strip().replace('/', '\\').toLowerCase(Locale.ROOT);
        while (p.contains("\\\\")) p = p.replace("\\\\", "\\");
        return p;
    }
}
