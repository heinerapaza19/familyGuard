package com.familyguard.admin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Filtros del panel «Mis dispositivos», aplicados sobre el listado completo antes de paginar. */
record DeviceFilter(String text, Long childId, String connection, String synchronization, String restrictions) {
    static final List<String> CONNECTION = List.of("Todos", "Conectados", "Desconectados");
    static final List<String> SYNCHRONIZATION = List.of("Todas", "Aplicada", "Pendiente");
    static final List<String> RESTRICTIONS = List.of("Todas", "Con pendientes", "Con errores", "Sin restricciones");

    static DeviceFilter none() { return new DeviceFilter("", null, "Todos", "Todas", "Todas"); }

    List<AdminApiClient.DevicePanelInfo> apply(List<AdminApiClient.DevicePanelInfo> devices) {
        return devices.stream().filter(this::matches).toList();
    }

    boolean matches(AdminApiClient.DevicePanelInfo info) {
        AdminApiClient.DeviceInfo d = info.device();
        String q = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (!q.isEmpty() && !(contains(d.name(), q) || contains(d.childName(), q) || contains(d.localUser(), q))) return false;
        if (childId != null && !Objects.equals(childId, d.childId())) return false;
        if ("Conectados".equals(connection) && !d.connected()) return false;
        if ("Desconectados".equals(connection) && d.connected()) return false;
        if ("Aplicada".equals(synchronization) && !"APLICADA".equals(d.syncStatus())) return false;
        if ("Pendiente".equals(synchronization) && "APLICADA".equals(d.syncStatus())) return false;
        if ("Con pendientes".equals(restrictions) && !info.hasPending()) return false;
        if ("Con errores".equals(restrictions) && !info.hasErrors()) return false;
        if ("Sin restricciones".equals(restrictions) && (info.appRestrictions() > 0 || info.webRules() > 0)) return false;
        return true;
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    /** Texto visible de cada estado, sin confundir conexión, sincronización y aplicación real. */
    static String appLabel(AdminApiClient.DevicePanelInfo info) {
        return switch (info.appState()) {
            case "APLICADA" -> "Aplicadas (" + info.appApplied() + ")";
            case "PENDIENTE" -> "Pendientes (" + info.appPending() + ")";
            case "ERROR" -> "Error (" + info.appErrors() + ")";
            default -> "Sin restricciones";
        };
    }
    static String webLabel(AdminApiClient.DevicePanelInfo info) {
        return switch (info.webState()) {
            case "APLICADO" -> "Aplicado (" + info.webApplied() + ")";
            case "PENDIENTE" -> "Pendiente (" + info.webPending() + ")";
            case "ERROR" -> "Error (" + info.webErrors() + ")";
            default -> "Sin reglas";
        };
    }
}
