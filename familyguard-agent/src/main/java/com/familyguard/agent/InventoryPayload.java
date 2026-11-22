package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inventario dentro de los límites que valida el servidor. Un solo elemento fuera de límite (por ejemplo el
 * proceso «Idle» con PID 0 o un nombre de más de 180 caracteres) hacía rechazar el envío completo y, con él, se
 * detenía la descarga de reglas de aplicaciones y de sitios web.
 */
final class InventoryPayload {
    static final int MAX_APPS = 500, MAX_PROCESSES = 1000;
    private InventoryPayload() { }

    static List<Map<String, Object>> applications(List<InstalledApplication> apps) {
        return apps.stream().filter(a -> a != null && !blank(a.name())).limit(MAX_APPS).map(a -> map(
                "nombre", cut(a.name(), 180), "rutaEjecutable", cut(a.path(), 700), "version", cut(a.version(), 80),
                "editor", cut(a.publisher(), 160), "tipoInstalacion", cut(a.installType(), 32))).toList();
    }

    static List<Map<String, Object>> processes(List<RunningProcess> processes) {
        return processes.stream().filter(p -> p != null && p.pid() > 0 && !blank(p.name())).limit(MAX_PROCESSES).map(p -> map(
                "pid", p.pid(), "nombre", cut(p.name(), 180), "rutaEjecutable", cut(p.path(), 700),
                "ventanaActiva", p.foreground())).toList();
    }

    static String cut(String value, int max) {
        if (value == null) return null;
        String v = value.strip();
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) if (pairs[i + 1] != null) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
}
