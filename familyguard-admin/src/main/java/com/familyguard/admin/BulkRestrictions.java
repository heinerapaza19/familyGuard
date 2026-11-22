package com.familyguard.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lógica de las restricciones masivas del Administrador, independiente de JavaFX (Etapa 4).
 * La validación definitiva de dominios y equipos la hace siempre el backend.
 */
final class BulkRestrictions {
    static final int MAX_DOMAINS = 20;
    private static final Pattern DOMAIN = Pattern.compile("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    private BulkRestrictions() { }

    /** Dominios aceptados (normalizados, sin duplicados y en orden) y entradas no válidas. */
    record ParsedDomains(List<String> valid, List<String> invalid) { }

    static ParsedDomains parseDomains(String text) {
        Set<String> valid = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        if (text != null) {
            for (String raw : text.split("[\\s,;]+")) {
                if (raw.isBlank()) continue;
                String value = normalize(raw);
                if (DOMAIN.matcher(value).matches()) valid.add(value); else invalid.add(raw.strip());
            }
        }
        return new ParsedDomains(List.copyOf(valid), List.copyOf(invalid));
    }

    static String normalize(String raw) {
        String value = raw.strip().toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://", "").replaceFirst("[/?#].*$", "");
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    /** Nombre del ejecutable en minúsculas, igual que el backend (C:\...\Roblox.exe → roblox.exe). */
    static String executable(String path) {
        if (path == null) return "";
        String value = path.strip();
        return value.substring(Math.max(value.lastIndexOf('\\'), value.lastIndexOf('/')) + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Aplicaciones de referencia: las detectadas en los equipos seleccionados, una por ejecutable.
     * Solo se ofrecen aplicaciones realmente detectadas; nunca se escribe una ruta a mano.
     */
    static List<AdminApiClient.ApplicationInfo> referenceApplications(Collection<AdminApiClient.ApplicationInfo> apps,
                                                                      Set<Long> selectedDevices) {
        Map<String, AdminApiClient.ApplicationInfo> byExecutable = new LinkedHashMap<>();
        apps.stream().filter(a -> selectedDevices.contains(a.deviceId()))
                .filter(a -> !executable(a.executablePath()).isBlank())
                .forEach(a -> byExecutable.putIfAbsent(executable(a.executablePath()), a));
        List<AdminApiClient.ApplicationInfo> result = new ArrayList<>(byExecutable.values());
        result.sort(Comparator.comparing((AdminApiClient.ApplicationInfo a) -> a.name().toLowerCase(Locale.ROOT))
                .thenComparing(a -> executable(a.executablePath())));
        return result;
    }

    /** Cuántos equipos seleccionados tienen detectado el mismo ejecutable que la referencia. */
    static int devicesWithExecutable(Collection<AdminApiClient.ApplicationInfo> apps, Set<Long> selectedDevices, String executable) {
        Set<Long> devices = new LinkedHashSet<>();
        apps.stream().filter(a -> selectedDevices.contains(a.deviceId()))
                .filter(a -> executable(a.executablePath()).equals(executable)).forEach(a -> devices.add(a.deviceId()));
        return devices.size();
    }

    static String stateLabel(String state) {
        return switch (state == null ? "" : state) {
            case "PENDIENTE_ENTREGA" -> "Pendiente de entrega";
            case "DESCONECTADO" -> "Desconectado, pendiente de sincronización";
            case "RECIBIDA" -> "Recibida por el Agente";
            case "APLICADA" -> "Aplicada";
            case "ERROR" -> "Error";
            case "CANCELADA" -> "Cancelada";
            case "SIN_CAMBIOS" -> "Sin cambios (ya estaba así)";
            case "APLICACION_NO_REGISTRADA" -> "Aplicación no registrada en el equipo";
            case "APLICACION_AMBIGUA" -> "Aplicación ambigua";
            case "REVOCADO" -> "Dispositivo revocado";
            case "RETIRADA" -> "Retirada después";
            case "PROGRAMADA" -> "Se aplicará";
            default -> state == null || state.isBlank() ? "—" : state;
        };
    }

    static String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "BLOQUEAR_SITIO" -> "Bloquear sitio web";
            case "RETIRAR_SITIO" -> "Retirar sitio web";
            case "LIMITAR_APLICACION" -> "Limitar aplicación";
            case "RETIRAR_LIMITE" -> "Retirar límite";
            default -> type;
        };
    }

    static String duration(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        return h == 0 ? m + " min" : m == 0 ? h + " h" : h + " h " + m + " min";
    }

    /** Confirmación para varios dominios sobre la misma selección de equipos. */
    static String confirmation(String verb, List<String> targets, int total, int connected) {
        int disconnected = total - connected;
        String what = targets.size() == 1 ? targets.get(0) : targets.size() + " sitios (" + String.join(", ", targets) + ")";
        StringBuilder text = new StringBuilder("Vas a ").append(verb).append(' ').append(what).append(" en ").append(total)
                .append(total == 1 ? " computadora" : " computadoras").append(" (").append(connected)
                .append(connected == 1 ? " conectada, " : " conectadas, ").append(disconnected)
                .append(disconnected == 1 ? " desconectada)." : " desconectadas).");
        if (disconnected > 0) text.append(" Las desconectadas quedarán pendientes hasta que vuelvan a comunicarse.");
        return text.append(" ¿Deseas continuar?").toString();
    }

    /** Líneas de aviso de la vista previa: solo equipos donde no se aplicará o ya estaba aplicado. */
    static List<String> previewNotes(List<AdminApiClient.BulkPreview> previews) {
        List<String> notes = new ArrayList<>();
        for (AdminApiClient.BulkPreview preview : previews)
            for (AdminApiClient.BulkPreviewItem item : preview.devices())
                if (!"PROGRAMADA".equals(item.result()))
                    notes.add(item.deviceName() + " · " + preview.target() + ": " + stateLabel(item.result())
                            + (item.detail() == null || item.detail().isBlank() ? "" : " — " + item.detail()));
        return notes;
    }

    /** Solo un bloqueo de sitio registrado se puede cancelar (en los equipos que aún no lo aplicaron). */
    static boolean cancellable(AdminApiClient.BulkOperation operation) {
        return "BLOQUEAR_SITIO".equals(operation.type()) && "REGISTRADA".equals(operation.status()) && !operation.complete();
    }
}
