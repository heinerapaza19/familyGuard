package com.familyguard.familyguard.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.TreeSet;

/**
 * Semántica única de las reglas web, idéntica a la del Agente ({@code WebRestrictionEngine.active}).
 *
 * <ul>
 *   <li><b>Siempre</b>: permanente y sin horarios. Bloquea mientras la regla esté activa.</li>
 *   <li><b>Fecha y horario</b>: no permanente y sin horarios. Bloquea en [inicio, fin).</li>
 *   <li><b>Semanal</b>: con horarios por día. Bloquea solo dentro de las franjas. «Permanente» significa que la
 *   repetición no vence; nunca que bloquee las 24 horas. Si no es permanente, además vence en {@code hasta}.</li>
 * </ul>
 * Las horas de los horarios se interpretan en la zona horaria de la familia. El inicio es inclusivo y el fin
 * exclusivo; una franja cuyo fin no es posterior al inicio cruza la medianoche.
 */
public final class ProgramacionWeb {
    public enum Tipo { SIEMPRE, FECHA, SEMANAL }
    public enum Estado { FINALIZADA, PROGRAMADA, EN_HORARIO, FUERA_DE_HORARIO }
    public record Franja(DayOfWeek dia, LocalTime inicio, LocalTime fin, boolean cruzaMedianoche, String tipo) { }
    public record Regla(String modo, boolean permanente, Instant desde, Instant hasta, List<Franja> franjas) {
        public Regla { franjas = franjas == null ? List.of() : List.copyOf(franjas); }
    }
    public record Situacion(Tipo tipo, Estado estado, boolean bloquea, Instant finActual, Instant proximoInicio) { }

    private ProgramacionWeb() { }

    public static Tipo tipo(Regla r) {
        if (!r.franjas().isEmpty()) return Tipo.SEMANAL;
        return r.permanente() ? Tipo.SIEMPRE : Tipo.FECHA;
    }

    /** ¿Debe estar bloqueado el dominio en {@code instante}? */
    public static boolean bloquea(Regla r, Instant instante, ZoneId zona) {
        if (!r.permanente() && ((r.desde() != null && instante.isBefore(r.desde()))
                || (r.hasta() != null && !instante.isBefore(r.hasta())))) return false;
        if (r.franjas().isEmpty()) return "BLOQUEAR".equals(r.modo());
        ZonedDateTime now = instante.atZone(zona);
        boolean permitir = r.franjas().stream().anyMatch(f -> "PERMITIR".equals(f.tipo()));
        if (r.franjas().stream().anyMatch(f -> "BLOQUEAR".equals(f.tipo()) && contiene(f, now))) return true;
        if (permitir) return r.franjas().stream().filter(f -> "PERMITIR".equals(f.tipo())).noneMatch(f -> contiene(f, now));
        return false;
    }

    static boolean contiene(Franja f, ZonedDateTime n) {
        LocalTime t = n.toLocalTime();
        if (!f.cruzaMedianoche()) return n.getDayOfWeek() == f.dia() && !t.isBefore(f.inicio()) && t.isBefore(f.fin());
        return (n.getDayOfWeek() == f.dia() && !t.isBefore(f.inicio())) || (n.getDayOfWeek() == f.dia().plus(1) && t.isBefore(f.fin()));
    }

    /** Estado horario y próximos cambios, calculados con los mismos puntos de transición que usa el Agente. */
    public static Situacion situacion(Regla r, Instant ahora, ZoneId zona) {
        boolean bloquea = bloquea(r, ahora, zona);
        Instant cambio = null, inicio = null;
        for (Instant punto : transiciones(r, ahora, zona)) {
            boolean despues = bloquea(r, punto, zona);
            if (bloquea && cambio == null && !despues) cambio = punto;
            if (!bloquea && inicio == null && despues) inicio = punto;
            if ((bloquea && cambio != null) || (!bloquea && inicio != null)) break;
        }
        Estado estado;
        if (!r.permanente() && r.hasta() != null && !ahora.isBefore(r.hasta())) estado = Estado.FINALIZADA;
        else if (bloquea) estado = Estado.EN_HORARIO;
        else if (!r.permanente() && r.desde() != null && ahora.isBefore(r.desde())) estado = Estado.PROGRAMADA;
        else estado = Estado.FUERA_DE_HORARIO;
        return new Situacion(tipo(r), estado, bloquea, bloquea ? cambio : null, bloquea ? null : inicio);
    }

    /** Instantes posteriores a {@code ahora} donde puede cambiar el estado (8 días de franjas y la vigencia). */
    static TreeSet<Instant> transiciones(Regla r, Instant ahora, ZoneId zona) {
        TreeSet<Instant> puntos = new TreeSet<>();
        if (!r.permanente()) {
            if (r.desde() != null && r.desde().isAfter(ahora)) puntos.add(r.desde());
            if (r.hasta() != null && r.hasta().isAfter(ahora)) puntos.add(r.hasta());
        }
        Instant base = r.permanente() || r.desde() == null || r.desde().isBefore(ahora) ? ahora : r.desde();
        LocalDate dia = base.atZone(zona).toLocalDate().minusDays(1);
        for (int i = 0; i <= 9; i++, dia = dia.plusDays(1)) {
            for (Franja f : r.franjas()) {
                if (dia.getDayOfWeek() != f.dia()) continue;
                Instant a = dia.atTime(f.inicio()).atZone(zona).toInstant();
                Instant b = (f.cruzaMedianoche() ? dia.plusDays(1) : dia).atTime(f.fin()).atZone(zona).toInstant();
                if (a.isAfter(ahora)) puntos.add(a);
                if (b.isAfter(ahora)) puntos.add(b);
            }
        }
        return puntos;
    }
}
