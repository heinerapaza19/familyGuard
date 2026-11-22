package com.familyguard.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Textos sencillos para los permisos temporales, pensados para el padre. */
final class PermissionTexts {
    static final String EXTRA = "TIEMPO_EXTRA", OUTSIDE = "FUERA_HORARIO", UNBLOCK = "DESBLOQUEO_TEMPORAL", SITE = "PERMITIR_SITIO";

    record Choice(String type, String label, String help) {
        @Override public String toString() { return label; }
    }

    static final List<Choice> TYPES = List.of(
            new Choice(EXTRA, "Tiempo extra hoy", "Suma minutos al límite diario de una aplicación. Termina a medianoche."),
            new Choice(OUTSIDE, "Usar fuera de horario", "Permite abrir la aplicación fuera de su horario durante el tiempo elegido."),
            new Choice(UNBLOCK, "Desbloquear aplicación", "Quita por un rato el bloqueo, el horario y el límite de la aplicación."),
            new Choice(SITE, "Permitir un sitio web bloqueado", "Permite entrar al sitio durante el tiempo elegido."));
    static final List<Integer> EXTRA_MINUTES = List.of(15, 30, 45, 60, 90, 120);
    static final List<Integer> DURATIONS = List.of(15, 30, 60, 120, 180, 240);

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private PermissionTexts() { }

    static Choice choice(String type) {
        return TYPES.stream().filter(c -> c.type().equals(type)).findFirst().orElse(TYPES.get(0));
    }

    static String typeLabel(String type) { return choice(type).label(); }

    static String stateLabel(String state) {
        return switch (state == null ? "" : state) {
            case "ACTIVO" -> "Activo";
            case "PENDIENTE" -> "Enviando al equipo";
            case "DESCONECTADO" -> "Pendiente (equipo sin conexión)";
            case "ERROR" -> "Error";
            case "EXPIRADO" -> "Terminado";
            case "REVOCADO" -> "Cancelado";
            default -> state == null || state.isBlank() ? "—" : state;
        };
    }

    static String duration(int minutes) {
        if (minutes < 60) return minutes + " minutos";
        int h = minutes / 60, m = minutes % 60;
        String horas = h == 1 ? "1 hora" : h + " horas";
        return m == 0 ? horas : horas + " " + m + " min";
    }

    /** «hoy a las 18:30», «mañana a las 00:00» o fecha y hora. */
    static String when(Instant instant, ZoneId zone, Instant now) {
        if (instant == null) return "—";
        ZonedDateTime at = instant.atZone(zone);
        LocalDate today = now.atZone(zone).toLocalDate();
        if (at.toLocalDate().equals(today)) return "hoy a las " + HOUR.format(at);
        if (at.toLocalDate().equals(today.plusDays(1))) return "mañana a las " + HOUR.format(at);
        return DAY.format(at);
    }

    /** Resumen previo a confirmar, en lenguaje sencillo. */
    static String preview(String child, String type, String target, Integer minutes, int devices) {
        String equipos = devices == 1 ? "1 computadora" : devices + " computadoras";
        if (target == null || target.isBlank() || minutes == null || devices == 0) return "Completa los datos para ver el resumen.";
        return switch (type) {
            case EXTRA -> child + " podrá usar " + target + " " + duration(minutes) + " más hoy en " + equipos + ". Termina a medianoche.";
            case OUTSIDE -> child + " podrá usar " + target + " fuera de su horario durante " + duration(minutes) + " en " + equipos + ".";
            case UNBLOCK -> child + " podrá usar " + target + " sin bloqueos durante " + duration(minutes) + " en " + equipos + ".";
            default -> child + " podrá entrar a " + target + " durante " + duration(minutes) + " en " + equipos + ".";
        };
    }
}
