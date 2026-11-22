package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Tarjeta «Bloqueados»: restricciones activas que el padre definió para ESTE equipo, a partir de las reglas que el
 * Agente descargó del servidor (y guarda cifradas para funcionar sin conexión). El estado «ahora» se calcula con la
 * misma lógica que aplica el bloqueo ({@link RuleEvaluator} y el motor web), incluidos los permisos temporales.
 */
final class BlockedSummary {
    /** {@code detail}: la regla en palabras; {@code state}: qué pasa ahora mismo. */
    /** {@code kind}: «APP» o «WEB»; {@code referenceId}: id de la aplicación o de la regla web en el servidor (para «Justificar»). */
    record Item(String name, String detail, String state, boolean blockedNow, String kind, Long referenceId) {
        Item(String name, String detail, String state, boolean blockedNow) { this(name, detail, state, blockedNow, null, null); }
    }
    record Summary(List<Item> applications, List<Item> sites) {
        static final Summary EMPTY = new Summary(List.of(), List.of());
    }

    static final long NO_DAILY_LIMIT = 24 * 60 * 60;   // un límite de 24 h equivale a no tener límite diario
    private static final Locale ES = Locale.forLanguageTag("es-PE");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private BlockedSummary() { }

    static List<Item> applications(List<RestrictionRule> rules, ToLongFunction<RestrictionRule> usedToday,
                                   ZonedDateTime now, TemporaryPermissions grants) {
        RuleEvaluator evaluator = new RuleEvaluator();
        Map<Long, RestrictionRule> byApplication = new LinkedHashMap<>();   // una fila por aplicación
        for (RestrictionRule r : rules) if (r != null && r.name() != null) byApplication.putIfAbsent(r.applicationId(), r);
        List<Item> items = new ArrayList<>();
        for (RestrictionRule r : byApplication.values()) {
            java.time.LocalDate today = now.toLocalDate();
            if (r.validUntil() != null && today.isAfter(r.validUntil())) continue;          // su vigencia ya terminó
            if (r.validFrom() != null && today.isBefore(r.validFrom())) {                     // aún no empieza
                items.add(new Item(r.name(), appRule(r), "Empieza el " + DAY.format(r.validFrom()), false, "APP", r.applicationId()));
                continue;
            }
            RuleEvaluator.Decision d = evaluator.evaluate(r, Math.max(0, usedToday.applyAsLong(r)), now, grants == null ? TemporaryPermissions.NONE : grants);
            String state;
            if (d.blocked()) state = "Bloqueada ahora: " + reason(d.reason());
            else if ("PERMISO_TEMPORAL".equals(d.reason())) state = "Permitida temporalmente por el padre";
            else if (r.limitSeconds() < NO_DAILY_LIMIT) state = "Disponible · quedan " + minutes(d.remainingSeconds()) + " hoy";
            else state = "Disponible ahora";
            String detail = appRule(r) + (ExecutablePaths.isStoreClient(r.path()) ? " · incluye las apps y juegos de la Microsoft Store" : "");
            items.add(new Item(r.name(), detail, state, d.blocked(), "APP", r.applicationId()));
        }
        items.sort(Comparator.comparing(Item::blockedNow).reversed().thenComparing(i -> i.name().toLowerCase(ES)));
        return List.copyOf(items);
    }

    static List<Item> sites(List<WebRule> rules, Predicate<WebRule> blockingNow, Instant now, ZoneId zone) {
        List<Item> items = new ArrayList<>();
        for (WebRule r : rules) {
            if (r == null || r.domain() == null) continue;
            if (!r.permanent() && r.until() != null && !r.until().isAfter(now)) continue;   // ya finalizada
            boolean blocked = blockingNow.test(r);
            String state;
            if (blocked) state = "Restringido ahora";
            else if (!r.permanent() && r.from() != null && r.from().isAfter(now)) state = "Empieza el " + DATE.format(r.from().atZone(zone));
            else state = "Disponible ahora (fuera del horario de restricción)";
            items.add(new Item(r.domain() + (r.subdomains() ? " (y subdominios)" : ""), webRule(r, zone), state, blocked, "WEB", r.id()));
        }
        items.sort(Comparator.comparing(Item::blockedNow).reversed().thenComparing(i -> i.name().toLowerCase(ES)));
        return List.copyOf(items);
    }

    static String counter(String label, int count) { return label + ": " + count; }

    static String reason(String code) {
        return switch (code == null ? "" : code) {
            case "BLOQUEO_TOTAL" -> "bloqueo total";
            case "HORARIO_BLOQUEADO" -> "horario bloqueado";
            case "FUERA_HORARIO_PERMITIDO" -> "fuera del horario permitido";
            case "LIMITE_DIARIO" -> "se agotó el tiempo de hoy";
            default -> "restricción del padre";
        };
    }

    static String appRule(RestrictionRule r) {
        List<String> parts = new ArrayList<>();
        if (r.fullyBlocked()) parts.add("Bloqueo total");
        else {
            List<ScheduleRule> blockedHours = r.schedules().stream().filter(s -> "BLOQUEAR".equals(s.type())).toList();
            List<ScheduleRule> allowedHours = r.schedules().stream().filter(s -> "PERMITIR".equals(s.type())).toList();
            if (!blockedHours.isEmpty()) parts.add("Bloqueada " + scheduleText(blockedHours.stream().map(s -> new Slot(s.day(), s.start(), s.end())).toList()));
            if (!allowedHours.isEmpty()) parts.add("Permitida solo " + scheduleText(allowedHours.stream().map(s -> new Slot(s.day(), s.start(), s.end())).toList()));
            if (r.limitSeconds() < NO_DAILY_LIMIT) parts.add("Límite diario de " + minutes(r.limitSeconds()));
        }
        String text = parts.isEmpty() ? "Restricción del padre" : String.join(" · ", parts);
        if (r.validFrom() != null || r.validUntil() != null)
            text += " · " + (r.validFrom() == null ? "hasta el " + DAY.format(r.validUntil())
                    : r.validUntil() == null ? "desde el " + DAY.format(r.validFrom())
                    : "del " + DAY.format(r.validFrom()) + " al " + DAY.format(r.validUntil()));
        return text;
    }

    static String webRule(WebRule r, ZoneId zone) {
        String when;
        if (r.schedules().isEmpty()) {
            when = r.permanent() ? "Siempre"
                    : "Del " + (r.from() == null ? "inicio" : DATE.format(r.from().atZone(zone))) + " al " + (r.until() == null ? "—" : DATE.format(r.until().atZone(zone)));
        } else {
            String slots = scheduleText(r.schedules().stream().map(s -> new Slot(s.day(), s.start(), s.end())).toList());
            when = ("PERMITIR".equals(r.mode()) ? "Permitido solo " : "Restringido ") + slots;
            if (!r.permanent() && r.until() != null) when += " (hasta el " + DATE.format(r.until().minusNanos(1).atZone(zone)).substring(0, 5) + ")";
        }
        return when;
    }

    private record Slot(DayOfWeek day, LocalTime start, LocalTime end) { }

    /** «de lunes a viernes, 08:00–18:00» o «los sábados y domingos, 10:00–12:00». */
    private static String scheduleText(List<Slot> slots) {
        Map<String, Set<DayOfWeek>> byHours = new LinkedHashMap<>();
        for (Slot s : slots) byHours.computeIfAbsent(HOUR.format(s.start()) + "–" + HOUR.format(s.end()), k -> EnumSet.noneOf(DayOfWeek.class)).add(s.day());
        List<String> parts = new ArrayList<>();
        byHours.forEach((hours, days) -> parts.add(days(days) + ", " + hours));
        return String.join("; ", parts);
    }

    static String days(Set<DayOfWeek> days) {
        if (days.size() == 7) return "todos los días";
        List<DayOfWeek> list = new ArrayList<>(days);
        boolean consecutive = list.size() >= 3 && list.get(list.size() - 1).getValue() - list.get(0).getValue() == list.size() - 1;
        if (consecutive) return "de " + name(list.get(0)) + " a " + name(list.get(list.size() - 1));
        List<String> names = list.stream().map(d -> { String n = name(d); return n.endsWith("s") ? n : n + "s"; }).toList();
        return "los " + (names.size() == 1 ? names.get(0) : String.join(", ", names.subList(0, names.size() - 1)) + " y " + names.get(names.size() - 1));
    }

    private static String name(DayOfWeek d) { return d.getDisplayName(TextStyle.FULL, ES).toLowerCase(ES); }

    static String minutes(long seconds) {
        long m = Math.max(0, seconds) / 60;
        if (m < 60) return m + " min";
        return m / 60 + " h" + (m % 60 == 0 ? "" : " " + m % 60 + " min");
    }
}
