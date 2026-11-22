package com.familyguard.admin;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Formulario de reglas web sin JavaFX: convierte lo que el padre elige en la regla que guarda el servidor y la
 * recupera exactamente igual al editar. Las fechas y horas se interpretan en la zona horaria de la familia (la
 * misma que usa el Agente), no en la del equipo del padre.
 */
final class WebRulePlan {
    enum Kind { ALWAYS, DATE, WEEKLY }

    /** Lo que muestra el formulario. {@code weeklyUntil} es el último día incluido de una repetición con vencimiento. */
    record Form(Kind kind, String mode, LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
                Set<DayOfWeek> days, LocalTime dailyStart, LocalTime dailyEnd, LocalDate weeklyUntil) {
        Form { days = days == null || days.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(days); }
    }

    static final Locale ES = Locale.forLanguageTag("es-PE");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private WebRulePlan() { }

    static ZoneId zone(String id) {
        try { return id == null || id.isBlank() ? ZoneId.systemDefault() : ZoneId.of(id); } catch (Exception e) { return ZoneId.systemDefault(); }
    }

    /** Formulario inicial de una regla nueva: «Siempre», o valores razonables para el resto de opciones. */
    static Form blank(LocalDate today) {
        return new Form(Kind.ALWAYS, "BLOQUEAR", today, LocalTime.of(8, 0), today, LocalTime.of(18, 0),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalTime.of(8, 0), LocalTime.of(18, 0), null);
    }

    /** Recupera exactamente lo guardado (sin sustituir fechas por la de hoy ni perder horas o días). */
    static Form fromRule(AdminApiClient.WebRuleInfo rule, LocalDate today) {
        ZoneId zone = zone(rule.timeZone());
        Form base = blank(today);
        if (!rule.schedules().isEmpty()) {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            rule.schedules().forEach(s -> days.add(s.day()));
            var first = rule.schedules().get(0);
            LocalDate until = !rule.permanent() && rule.until() != null ? rule.until().minusNanos(1).atZone(zone).toLocalDate() : null;
            return new Form(Kind.WEEKLY, rule.mode(), base.startDate(), base.startTime(), base.endDate(), base.endTime(),
                    days, first.start(), first.end(), until);
        }
        if (rule.permanent()) return new Form(Kind.ALWAYS, rule.mode(), base.startDate(), base.startTime(), base.endDate(),
                base.endTime(), base.days(), base.dailyStart(), base.dailyEnd(), null);
        LocalDateTime start = rule.from() == null ? null : LocalDateTime.ofInstant(rule.from(), zone);
        LocalDateTime end = rule.until() == null ? null : LocalDateTime.ofInstant(rule.until(), zone);
        return new Form(Kind.DATE, rule.mode(), start == null ? base.startDate() : start.toLocalDate(),
                start == null ? base.startTime() : start.toLocalTime(), end == null ? base.endDate() : end.toLocalDate(),
                end == null ? base.endTime() : end.toLocalTime(), base.days(), base.dailyStart(), base.dailyEnd(), null);
    }

    /** Hora escrita por el padre: «8», «8:00», «08:00» o «18:30». */
    static LocalTime parseTime(String text) {
        String t = text == null ? "" : text.strip();
        if (t.matches("\\d{1,2}")) t = t + ":00";
        if (t.matches("\\d:\\d{2}")) t = "0" + t;
        try { return LocalTime.parse(t, HOUR); }
        catch (Exception e) { throw new IllegalArgumentException("Hora no válida: «" + text + "». Usa el formato 24 h, por ejemplo 08:00 o 18:30."); }
    }

    /** Convierte el formulario en la solicitud al servidor, validando antes de enviar. */
    static AdminApiClient.WebRuleInput toInput(Form f, long childId, long deviceId, String domain, boolean subdomains,
                                               String reason, String zoneId, Instant now) {
        if (domain == null || domain.isBlank()) throw new IllegalArgumentException("Escribe el dominio, por ejemplo youtube.com.");
        ZoneId zone = zone(zoneId);
        List<AdminApiClient.WebScheduleInput> schedules = new ArrayList<>();
        boolean permanent; Instant from = null, until = null;
        switch (f.kind()) {
            case ALWAYS -> {
                if (!"BLOQUEAR".equals(f.mode())) throw new IllegalArgumentException("«Permitir solo en horario» requiere repetir por días de semana.");
                permanent = true;
            }
            case DATE -> {
                if (!"BLOQUEAR".equals(f.mode())) throw new IllegalArgumentException("«Permitir solo en horario» requiere repetir por días de semana.");
                if (f.startDate() == null || f.endDate() == null) throw new IllegalArgumentException("Elige la fecha de inicio y la de fin.");
                from = f.startDate().atTime(f.startTime()).atZone(zone).toInstant();
                until = f.endDate().atTime(f.endTime()).atZone(zone).toInstant();
                if (!until.isAfter(from)) throw new IllegalArgumentException("El fin debe ser posterior al inicio.");
                if (!until.isAfter(now)) throw new IllegalArgumentException("La fecha y hora de fin ya pasaron.");
                permanent = false;
            }
            default -> {
                if (f.days().isEmpty()) throw new IllegalArgumentException("Elige al menos un día de la semana.");
                if (f.dailyStart().equals(f.dailyEnd())) throw new IllegalArgumentException("La hora de inicio y la de fin no pueden ser iguales.");
                for (DayOfWeek day : f.days()) schedules.add(new AdminApiClient.WebScheduleInput(day, f.dailyStart(), f.dailyEnd(), f.mode()));
                permanent = f.weeklyUntil() == null;
                if (!permanent) {
                    until = f.weeklyUntil().plusDays(1).atStartOfDay(zone).toInstant();   // incluye todo el último día
                    if (!until.isAfter(now)) throw new IllegalArgumentException("La fecha de vencimiento ya pasó.");
                }
            }
        }
        return new AdminApiClient.WebRuleInput(childId, List.of(deviceId), domain.strip(), subdomains, "PERSONALIZADO",
                f.mode(), permanent, from, until, reason == null ? "" : reason, schedules);
    }

    /**
     * «Actualizar restricción»: misma regla con el mismo sitio, hijo, dispositivos, categoría, tipo de bloqueo y motivo;
     * solo cambia la programación (modalidad, fechas, horas, días y vencimiento).
     */
    static AdminApiClient.WebRuleInput scheduleUpdate(Form f, AdminApiClient.WebRuleInfo current, Instant now) {
        if (current.deviceIds().isEmpty()) throw new IllegalArgumentException("La regla no tiene un dispositivo asociado; usa «Editar».");
        Form keepMode = new Form(f.kind(), current.mode(), f.startDate(), f.startTime(), f.endDate(), f.endTime(), f.days(),
                f.dailyStart(), f.dailyEnd(), f.weeklyUntil());
        AdminApiClient.WebRuleInput in = toInput(keepMode, current.childId(), current.deviceIds().get(0), current.domain(),
                current.subdomains(), current.reason(), current.timeZone(), now);
        return new AdminApiClient.WebRuleInput(in.menorId(), current.deviceIds(), in.dominio(), in.incluirSubdominios(),
                current.category(), in.modo(), in.permanente(), in.vigenteDesde(), in.vigenteHasta(), in.motivo(), in.horarios());
    }

    /** Resumen previo a guardar, en lenguaje sencillo. */
    static String summary(Form f, String domain, String device) {
        String site = domain == null || domain.isBlank() ? "El sitio" : domain.strip();
        String where = device == null || device.isBlank() ? "" : " en " + device;
        boolean permit = "PERMITIR".equals(f.mode());
        return switch (f.kind()) {
            case ALWAYS -> site + " quedará bloqueado siempre" + where + ", mientras la regla esté activa.";
            case DATE -> site + " se bloqueará desde el " + DATE.format(f.startDate()) + " a las " + HOUR.format(f.startTime())
                    + " hasta el " + DATE.format(f.endDate()) + " a las " + HOUR.format(f.endTime()) + where + ".";
            default -> {
                String days = daysLabel(f.days());
                String range = "de " + HOUR.format(f.dailyStart()) + " a " + HOUR.format(f.dailyEnd())
                        + (f.dailyEnd().isAfter(f.dailyStart()) ? "" : " (del día siguiente)");
                String expiry = f.weeklyUntil() == null ? ", sin fecha de vencimiento" : ", hasta el " + DATE.format(f.weeklyUntil());
                yield permit ? site + " solo se permitirá " + days + ", " + range + where + expiry + "; el resto del tiempo estará bloqueado."
                        : site + " se bloqueará " + days + ", " + range + where + expiry + ".";
            }
        };
    }

    /** «de lunes a viernes», «los sábados y domingos», «los lunes, miércoles y viernes». */
    static String daysLabel(Set<DayOfWeek> days) {
        if (days.isEmpty()) return "ningún día";
        if (days.size() == 7) return "todos los días";
        List<DayOfWeek> list = new ArrayList<>(new java.util.TreeSet<>(days));
        boolean consecutive = list.size() >= 3 && list.get(list.size() - 1).getValue() - list.get(0).getValue() == list.size() - 1;
        if (consecutive) return "de " + name(list.get(0)) + " a " + name(list.get(list.size() - 1));
        List<String> names = list.stream().map(d -> plural(name(d))).toList();
        return "los " + (names.size() == 1 ? names.get(0)
                : String.join(", ", names.subList(0, names.size() - 1)) + " y " + names.get(names.size() - 1));
    }

    private static String name(DayOfWeek d) { return d.getDisplayName(TextStyle.FULL, ES).toLowerCase(ES); }
    private static String plural(String n) { return n.endsWith("s") ? n : n + "s"; }

    /** Texto compacto de la programación para la tabla. */
    static String scheduleLabel(AdminApiClient.WebRuleInfo r) {
        ZoneId zone = zone(r.timeZone());
        if (!r.schedules().isEmpty()) {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            r.schedules().forEach(s -> days.add(s.day()));
            var s = r.schedules().get(0);
            String text = capital(daysLabel(days)) + ", " + HOUR.format(s.start()) + "–" + HOUR.format(s.end());
            return !r.permanent() && r.until() != null ? text + " (hasta " + DATE.format(r.until().minusNanos(1).atZone(zone)) + ")" : text;
        }
        if (r.permanent()) return "Siempre";
        String from = r.from() == null ? "ahora" : SHORT.format(r.from().atZone(zone));
        return from + " → " + (r.until() == null ? "—" : SHORT.format(r.until().atZone(zone)));
    }

    static String stateLabel(String state) {
        return switch (state == null ? "" : state) {
            case "BLOQUEANDO_AHORA" -> "Bloqueando ahora";
            case "PROGRAMADA" -> "Programada";
            case "FUERA_DE_HORARIO" -> "Fuera de horario";
            case "FINALIZADA" -> "Finalizada";
            case "DESACTIVADA" -> "Desactivada";
            case "ERROR" -> "Error";
            default -> "Pendiente";
        };
    }

    /** «Termina hoy a las 18:00», «Empieza el lunes 23/11 a las 08:00» o «—». */
    static String nextChange(AdminApiClient.WebRuleInfo r, Instant now) {
        ZoneId zone = zone(r.timeZone());
        if (r.currentEnd() != null && r.shouldBlockNow()) return "Termina " + when(r.currentEnd(), zone, now);
        if (r.nextStart() != null && !r.shouldBlockNow()) return "Empieza " + when(r.nextStart(), zone, now);
        if (r.shouldBlockNow() && "SIEMPRE".equals(r.scheduleKind())) return "Sin fin programado";
        return "—";
    }

    static String when(Instant instant, ZoneId zone, Instant now) {
        LocalDateTime at = LocalDateTime.ofInstant(instant, zone);
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (at.toLocalDate().equals(today)) return "hoy a las " + HOUR.format(at);
        if (at.toLocalDate().equals(today.plusDays(1))) return "mañana a las " + HOUR.format(at);
        return "el " + name(at.getDayOfWeek()) + " " + DateTimeFormatter.ofPattern("dd/MM").format(at) + " a las " + HOUR.format(at);
    }

    /** «1 hora y 25 minutos», «3 minutos», «2 días y 4 horas». */
    static String duration(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        long days = minutes / 1440, hours = (minutes % 1440) / 60, mins = minutes % 60;
        if (days > 0) return plural(days, "día", "días") + (hours > 0 ? " y " + plural(hours, "hora", "horas") : "");
        if (hours > 0) return plural(hours, "hora", "horas") + (mins > 0 ? " y " + plural(mins, "minuto", "minutos") : "");
        return minutes == 0 ? "menos de un minuto" : plural(mins, "minuto", "minutos");
    }

    private static String plural(long n, String one, String many) { return n + " " + (n == 1 ? one : many); }
    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    static String format(Instant instant, ZoneId zone) { return instant == null ? null : DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(instant.atZone(zone)); }
}
