package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tarjeta «Bloqueados»: solo reglas reales y activas de este equipo, con su estado actual. */
class BlockedSummaryTests {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final ZonedDateTime THURSDAY_16 = ZonedDateTime.of(2026, 11, 19, 16, 0, 0, 0, LIMA);
    private static final long NO_LIMIT = 24 * 3600;

    private static RestrictionRule app(long id, String name, long limit, boolean total, List<ScheduleRule> schedules) {
        return new RestrictionRule(id, id, name, "C:\\Apps\\" + name + ".exe", limit, 0, total, 0, 1, schedules);
    }

    @Test
    void aplicacionesConSuReglaYSuEstadoActual() {
        var weekdays = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY).stream()
                .map(d -> new ScheduleRule(d, LocalTime.of(8, 0), LocalTime.of(18, 0), false, "BLOQUEAR")).toList();
        var rules = List.of(
                app(1, "Roblox", NO_LIMIT, true, List.of()),
                app(2, "Discord", NO_LIMIT, false, weekdays),
                app(3, "Minecraft", 3600, false, List.of()),
                app(3, "Minecraft", 3600, false, List.of()));   // duplicada: una sola fila por aplicación
        var items = BlockedSummary.applications(rules, r -> r.name().equals("Minecraft") ? 1500 : 0, THURSDAY_16, TemporaryPermissions.NONE);
        assertEquals(3, items.size());
        assertEquals("Discord", items.get(0).name());                       // primero lo bloqueado ahora
        assertEquals("Bloqueada de lunes a viernes, 08:00–18:00", items.get(0).detail());
        assertEquals("Bloqueada ahora: horario bloqueado", items.get(0).state());
        assertEquals("Roblox", items.get(1).name());
        assertEquals("Bloqueo total", items.get(1).detail());
        var minecraft = items.get(2);
        assertFalse(minecraft.blockedNow());
        assertEquals("Límite diario de 1 h", minecraft.detail());
        assertEquals("Disponible · quedan 35 min hoy", minecraft.state());
    }

    @Test
    void sitiosActivosDeEsteEquipoYNoLosFinalizados() {
        Instant now = THURSDAY_16.toInstant();
        var always = new WebRule(1, "tiktok.com", true, "BLOQUEAR", true, null, null, 1, List.of());
        var later = new WebRule(2, "youtube.com", false, "BLOQUEAR", false, now.plusSeconds(7200), now.plusSeconds(10800), 1, List.of());
        var ended = new WebRule(3, "juegos.example", false, "BLOQUEAR", false, now.minusSeconds(7200), now.minusSeconds(60), 1, List.of());
        var items = BlockedSummary.sites(List.of(later, always, ended), r -> r.id() == 1, now, LIMA);
        assertEquals(2, items.size());
        assertEquals("tiktok.com (y subdominios)", items.get(0).name());
        assertEquals("Siempre", items.get(0).detail());
        assertEquals("Restringido ahora", items.get(0).state());
        assertTrue(items.get(0).blockedNow());
        assertEquals("youtube.com", items.get(1).name());
        assertEquals("Empieza el 19/11 18:00", items.get(1).state());
        assertEquals("Del 19/11 18:00 al 19/11 19:00", items.get(1).detail());
    }

    @Test
    void contadoresYListasVacias() {
        assertEquals("Apps bloqueadas: 0", BlockedSummary.counter("Apps bloqueadas", BlockedSummary.Summary.EMPTY.applications().size()));
        assertEquals("Sitios web restringidos: 2", BlockedSummary.counter("Sitios web restringidos", 2));
        assertTrue(BlockedSummary.applications(List.of(), r -> 0, THURSDAY_16, TemporaryPermissions.NONE).isEmpty());
        assertTrue(BlockedSummary.sites(List.of(), r -> false, THURSDAY_16.toInstant(), LIMA).isEmpty());
        var weekend = List.of(new WebSchedule(DayOfWeek.SATURDAY, LocalTime.of(10, 0), LocalTime.of(12, 0), false, "BLOQUEAR"),
                new WebSchedule(DayOfWeek.SUNDAY, LocalTime.of(10, 0), LocalTime.of(12, 0), false, "BLOQUEAR"));
        assertEquals("Restringido los sábados y domingos, 10:00–12:00",
                BlockedSummary.webRule(new WebRule(4, "x.com", false, "BLOQUEAR", true, null, null, 1, weekend), LIMA));
    }
}
