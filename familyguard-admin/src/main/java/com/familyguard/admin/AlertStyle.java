package com.familyguard.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/** Presentación de las alertas (sin JavaFX): categoría, color, icono, nombre legible y fecha relativa. */
final class AlertStyle {
    enum Category {
        REQUESTS("Solicitudes", "mail", "alert-request"), SECURITY("Seguridad", "shield", "alert-security"), CONNECTION("Conexión", "server", "alert-connection"),
        BLOCKS("Bloqueos", "lock", "alert-blocks"), WEB("Sitios web", "web", "alert-web"), OTHER("Otros", "alerts", "alert-other");
        final String label, icon, style;
        Category(String label, String icon, String style) { this.label = label; this.icon = icon; this.style = style; }
    }
    enum Severity { DANGER, WARNING, SUCCESS, INFO }

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("SIN_CONEXION", "Sin conexión"), Map.entry("BLOQUEO", "Bloqueo aplicado"),
            Map.entry("PIN_INCORRECTO", "PIN incorrecto"), Map.entry("PIN_BLOQUEADO", "PIN bloqueado"),
            Map.entry("PIN_PARENTAL_CAMBIADO", "PIN parental cambiado"), Map.entry("PIN_PARENTAL_CONSULTADO", "PIN parental consultado"),
            Map.entry("RESTRICCION_CREADA", "Restricción creada"), Map.entry("RESTRICCION_MODIFICADA", "Restricción modificada"),
            Map.entry("RESTRICCION_ELIMINADA", "Restricción eliminada"), Map.entry("RESTRICCION_DESACTIVADA", "Aplicación desbloqueada"),
            Map.entry("RESTRICCION_REACTIVADA", "Restricción reactivada"), Map.entry("LIMITE_TIEMPO", "Límite de tiempo"),
            Map.entry("DESINSTALACION_AUTORIZADA", "Desinstalación autorizada"), Map.entry("DESINSTALACION_INICIADA", "Desinstalación iniciada"),
            Map.entry("AUTORIZACION_CANCELADA", "Autorización cancelada"), Map.entry("DISPOSITIVO_VINCULADO", "Dispositivo vinculado"),
            Map.entry("DISPOSITIVO_REVOCADO", "Dispositivo revocado"), Map.entry("PAUSA_INICIADA", "Pausa iniciada"),
            Map.entry("PAUSA_FINALIZADA", "Pausa finalizada"), Map.entry("PERMISO_TEMPORAL_OTORGADO", "Permiso temporal otorgado"),
            Map.entry("PERMISO_TEMPORAL_REVOCADO", "Permiso temporal revocado"), Map.entry("SITIO_WEB_CREADO", "Sitio web bloqueado"),
            Map.entry("SITIO_WEB_MODIFICADO", "Regla web modificada"), Map.entry("SITIO_WEB_RETIRADO", "Regla web eliminada"),
            Map.entry("SITIO_WEB_ACTIVADO", "Regla web activada"), Map.entry("SITIO_WEB_DESACTIVADO", "Regla web desactivada"),
            Map.entry("FILTRO_ERROR", "Error del filtro web"), Map.entry("OPERACION_MASIVA", "Operación masiva"),
            Map.entry("SOLICITUD_DESBLOQUEO", "Solicitud de desbloqueo"), Map.entry("SOLICITUD_APROBADA", "Solicitud aprobada"),
            Map.entry("SOLICITUD_RECHAZADA", "Solicitud rechazada"));

    private AlertStyle() { }

    static Category category(String type) {
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (t.startsWith("SOLICITUD")) return Category.REQUESTS;
        if (t.startsWith("PIN") || t.startsWith("DESINSTALACION") || t.startsWith("AUTORIZACION") || t.startsWith("PAUSA")
                || t.contains("REVOCADO") || t.startsWith("PROTECCION")) return Category.SECURITY;
        if (t.contains("CONEXION") || t.contains("VINCULA") || t.startsWith("CODIGO")) return Category.CONNECTION;
        if (t.startsWith("SITIO") || t.startsWith("FILTRO") || t.contains("WEB")) return Category.WEB;
        if (t.startsWith("BLOQUEO") || t.startsWith("RESTRICCION") || t.startsWith("LIMITE") || t.startsWith("PERMISO")
                || t.startsWith("OPERACION")) return Category.BLOCKS;
        return Category.OTHER;
    }

    static Severity severity(String type, String result) {
        String upper = type == null ? "" : type.toUpperCase(Locale.ROOT);
        // Una solicitud del hijo requiere atención del padre: en rojo hasta que se responda.
        if (upper.equals("SOLICITUD_DESBLOQUEO")) return Severity.DANGER;
        if (upper.equals("SOLICITUD_APROBADA")) return Severity.SUCCESS;
        if (upper.equals("SOLICITUD_RECHAZADA")) return Severity.INFO;
        String r = result == null ? "" : result.toUpperCase(Locale.ROOT);
        if (r.contains("ERROR") || r.contains("FALL") || r.contains("RECHAZ")) return Severity.DANGER;
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (t.equals("SIN_CONEXION") || t.contains("CANCELADA") || t.contains("REVOCADO") || t.contains("DESINSTALACION")) return Severity.WARNING;
        if (r.contains("EXITO") || r.contains("CORRECT") || r.contains("OK")) return Severity.SUCCESS;
        return Severity.INFO;
    }

    /** «SIN_CONEXION» → «Sin conexión»; los códigos desconocidos se convierten a texto legible. */
    static String name(String type) {
        if (type == null || type.isBlank()) return "Evento";
        String known = NAMES.get(type.toUpperCase(Locale.ROOT));
        if (known != null) return known;
        String text = type.replace('_', ' ').toLowerCase(Locale.forLanguageTag("es-PE"));
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static String result(String result) {
        return switch (result == null ? "" : result.toUpperCase(Locale.ROOT)) {
            case "INFORMATIVO" -> "Aviso";
            case "RECHAZADO" -> "Rechazado";
            case "EXITO", "EXITOSO", "OK" -> "Correcto";
            case "ERROR", "FALLIDO" -> "Error";
            default -> result == null || result.isBlank() ? "—" : name(result);
        };
    }

    /** «hace 5 min», «hace 3 h», «ayer», «hace 4 días». */
    static String relative(Instant when, Instant now) {
        if (when == null) return "—";
        long minutes = Math.max(0, Duration.between(when, now).toMinutes());
        if (minutes < 1) return "ahora";
        if (minutes < 60) return "hace " + minutes + " min";
        long hours = minutes / 60;
        if (hours < 24) return "hace " + hours + " h";
        long days = hours / 24;
        return days == 1 ? "ayer" : "hace " + days + " días";
    }
}
