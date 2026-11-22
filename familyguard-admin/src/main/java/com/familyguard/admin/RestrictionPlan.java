package com.familyguard.admin;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Vigencia por fechas de las restricciones de aplicaciones (textos y validación, sin JavaFX). */
final class RestrictionPlan {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private RestrictionPlan() { }

    /** «Siempre», «Del 19/11/2026 al 25/11/2026», «Desde el …», «Hasta el …». */
    static String validity(LocalDate from, LocalDate until) {
        if (from == null && until == null) return "Siempre";
        if (from == null) return "Hasta el " + DAY.format(until);
        if (until == null) return "Desde el " + DAY.format(from);
        return from.equals(until) ? "Solo el " + DAY.format(from) : "Del " + DAY.format(from) + " al " + DAY.format(until);
    }

    /** {@code null} si es válida; si no, el mensaje para el padre. */
    static String validate(LocalDate from, LocalDate until, LocalDate today) {
        if (from == null || until == null) return "Elige la fecha de inicio y la de fin.";
        if (until.isBefore(from)) return "La fecha de fin no puede ser anterior a la de inicio.";
        if (until.isBefore(today)) return "La fecha de fin ya pasó.";
        return null;
    }

    /** Estado claro de una restricción: si está desbloqueada se dice primero (antes «Agotado» lo ocultaba). */
    static String state(AdminApiClient.RestrictionInfo r) {
        if (!r.active()) return "Desbloqueada";
        if (r.fullyBlocked()) return "Bloqueada";
        return r.exhausted() ? "Agotado" : "Activa";
    }
}
