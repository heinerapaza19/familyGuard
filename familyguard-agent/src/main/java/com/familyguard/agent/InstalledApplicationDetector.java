package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.InstalledApplication;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Lectura no destructiva de las vistas de desinstalación del Registro. */
final class InstalledApplicationDetector {
    private static final String UNINSTALL = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall";
    private final ObjectMapper mapper = new ObjectMapper();
    private List<InstalledApplication> storeCache = List.of();
    private Instant storeCacheAt = Instant.EPOCH;

    List<InstalledApplication> detect() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) return List.of();
        Map<String, InstalledApplication> unique = new TreeMap<>();
        readHive(WinReg.HKEY_CURRENT_USER, UNINSTALL, "USUARIO", unique);
        readHive(WinReg.HKEY_LOCAL_MACHINE, UNINSTALL, "SISTEMA_64", unique);
        readHive(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "SISTEMA_32", unique);
        detectStoreApplications().forEach(app -> unique.putIfAbsent(key(app), app));
        return List.copyOf(unique.values());
    }

    private List<InstalledApplication> detectStoreApplications() {
        if (storeCacheAt.plus(Duration.ofMinutes(10)).isAfter(Instant.now())) return storeCache;
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", STORE_SCRIPT)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); return storeCache; }
            if (process.exitValue() != 0) return storeCache;
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (output.isEmpty()) return storeCache;
            JsonNode root = mapper.readTree(output);
            List<InstalledApplication> found = new ArrayList<>();
            if (root.isArray()) root.forEach(node -> addStoreApp(found, node)); else addStoreApp(found, root);
            storeCache = List.copyOf(found); storeCacheAt = Instant.now();
        } catch (Exception ignored) {
            // Windows puede restringir el inventario de Inicio; se conserva el inventario clásico.
        }
        return storeCache;
    }

    /**
     * Apps y juegos de la Microsoft Store con su ejecutable real (solo lectura): el nombre viene de Get-StartApps y el
     * ejecutable del manifiesto del paquete (AppxManifest.xml, atributo Executable). Sin ejecutable el Agente no podría
     * reconocer el proceso y una regla del padre no tendría efecto.
     */
    static final String STORE_SCRIPT = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; "
            + "$pk=@{}; Get-AppxPackage | ForEach-Object { $pk[$_.PackageFamilyName]=$_ }; "
            + "$out = foreach ($s in (Get-StartApps | Where-Object { $_.AppID -like '*!*' })) { "
            + "$parts=$s.AppID -split '!',2; $p=$pk[$parts[0]]; $exe=$null; $ver=$null; $pub=$null; "
            + "if ($p) { $ver=[string]$p.Version; $pub=$p.Publisher; "
            + "try { $m=[xml](Get-Content -LiteralPath (Join-Path $p.InstallLocation 'AppxManifest.xml') -Raw); "
            + "$a=$m.Package.Applications.Application | Where-Object { $_.Id -eq $parts[1] } | Select-Object -First 1; "
            + "if ($a -and $a.Executable) { $exe=Join-Path $p.InstallLocation $a.Executable } } catch {} }; "
            + "[pscustomobject]@{Name=$s.Name;AppID=$s.AppID;Path=$exe;Version=$ver;Publisher=$pub} }; "
            + "$out | ConvertTo-Json -Compress";

    static void addStoreApp(List<InstalledApplication> result, JsonNode node) {
        String name = node.path("Name").asText("").strip();
        String appId = node.path("AppID").asText("").strip();
        if (name.isBlank() || appId.isBlank() || !appId.contains("!")) return;
        String path = text(node, "Path"), version = text(node, "Version"), publisher = text(node, "Publisher");
        result.add(new InstalledApplication(name, version, publisherName(publisher), path, "Microsoft Store"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText("").isBlank() ? null : v.asText().strip();
    }

    /** «CN=GAMELOFT SA, O=…» → «GAMELOFT SA». */
    static String publisherName(String publisher) {
        if (publisher == null) return "Microsoft Store";
        for (String part : publisher.split(",")) {
            String p = part.strip();
            if (p.regionMatches(true, 0, "CN=", 0, 3)) return p.substring(3).strip();
        }
        return publisher;
    }

    private void readHive(WinReg.HKEY hive, String path, String type, Map<String, InstalledApplication> result) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, path)) return;
            for (String child : Advapi32Util.registryGetKeys(hive, path)) {
                String key = path + "\\" + child;
                String name = value(hive, key, "DisplayName");
                if (name == null || name.isBlank() || "1".equals(value(hive, key, "SystemComponent"))) continue;
                String location = first(value(hive, key, "DisplayIcon"), value(hive, key, "InstallLocation"));
                InstalledApplication app = new InstalledApplication(name.strip(), value(hive, key, "DisplayVersion"),
                        value(hive, key, "Publisher"), sanitizePath(location), type);
                result.putIfAbsent(key(app), app);
            }
        } catch (RuntimeException ignored) {
            // Una clave inaccesible no interrumpe el inventario autorizado.
        }
    }

    private String value(WinReg.HKEY hive, String key, String name) {
        try {
            return Advapi32Util.registryValueExists(hive, key, name)
                    ? Advapi32Util.registryGetStringValue(hive, key, name) : null;
        } catch (RuntimeException ignored) { return null; }
    }
    private static String first(String first, String second) { return first != null && !first.isBlank() ? first : second; }
    private static String sanitizePath(String path) {
        if (path == null) return null;
        String clean = path.strip().replace("\"", "");
        int comma = clean.lastIndexOf(',');
        return comma > 2 ? clean.substring(0, comma) : clean;
    }
    private static String key(InstalledApplication app) {
        return (app.path() == null ? app.name() : app.path()).toLowerCase(Locale.ROOT);
    }
}
