package com.familyguard.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Textos del panel principal calculados solo a partir del estado real del Agente. Nunca afirma que el equipo
 * está protegido si la supervisión no está en marcha, está en pausa o la autorización fue revocada.
 */
final class AgentDashboardState {
    enum Tone { SUCCESS, INFO, WARNING, DANGER }

    /** Estado observado: {@code started=false} mientras no llega el primer informe del Agente. */
    record Snapshot(boolean started, boolean running, boolean connected, boolean authorizationRequired,
                    Instant pausedUntil, Instant observedAt) {
        static Snapshot initial() { return new Snapshot(false, false, false, false, null, null); }
        boolean paused() { return pausedUntil != null; }
    }

    record Text(String value, String detail, Tone tone) { }
    record Banner(String title, String subtitle, boolean protectedState) { }

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private AgentDashboardState() { }

    static Banner banner(Snapshot s, ZoneId zone) {
        if (!s.started()) return new Banner("Iniciando la protección", "FamilyGuard está preparando la supervisión de esta computadora.", false);
        if (s.authorizationRequired())
            return new Banner("Vinculación requerida", "La autorización de este equipo fue revocada. Se requiere una nueva vinculación.", false);
        if (!s.running()) return new Banner("Supervisión detenida", "FamilyGuard no está supervisando esta computadora en este momento.", false);
        if (s.paused())
            return new Banner("Supervisión en pausa", "Pausa autorizada por el padre o tutor hasta las " + HOUR.withZone(zone).format(s.pausedUntil()) + ".", false);
        if (!s.connected())
            return new Banner("Protección activa sin conexión", "Se aplican las últimas reglas recibidas. FamilyGuard se sincronizará al reconectarse.", false);
        return new Banner("Tu equipo está protegido", "FamilyGuard está supervisando el uso de esta computadora.", true);
    }

    static Text agent(Snapshot s, Instant now) {
        if (!s.started()) return new Text("Iniciando…", "Esperando el primer informe del agente", Tone.INFO);
        if (!s.running()) return new Text("Detenido", lastActivity(s.observedAt(), now), Tone.WARNING);
        return new Text("En ejecución", lastActivity(s.observedAt(), now), Tone.SUCCESS);
    }

    static Text supervision(Snapshot s, ZoneId zone) {
        if (!s.started()) return new Text("Sin iniciar", "Monitoreo del uso y aplicaciones", Tone.INFO);
        if (s.authorizationRequired()) return new Text("Requiere vinculación", "Se aplican las últimas reglas recibidas", Tone.WARNING);
        if (!s.running()) return new Text("Detenida", "Monitoreo del uso y aplicaciones", Tone.WARNING);
        if (s.paused()) return new Text("En pausa", "Se reanuda sola a las " + HOUR.withZone(zone).format(s.pausedUntil()), Tone.WARNING);
        return new Text("Activa", "Monitoreo del uso y aplicaciones", Tone.SUCCESS);
    }

    /** Tarjeta «Conexión y vinculación»: el botón de vincular solo aparece con revocación confirmada. */
    static Text connection(Snapshot s) {
        if (s.authorizationRequired()) return new Text("Vinculación requerida", "La autorización fue revocada", Tone.DANGER);
        if (!s.started()) return new Text("Vinculado", "Comprobando la conexión con el servidor…", Tone.INFO);
        if (s.connected()) return new Text("Vinculado", "Conectado al servidor FamilyGuard", Tone.SUCCESS);
        return new Text("Vinculado", "Sin conexión con el servidor; se reintentará automáticamente", Tone.WARNING);
    }

    /** Texto de la barra inferior. Nunca «Listo» sin una conexión confirmada. */
    static Text footer(Snapshot s) {
        if (s.authorizationRequired()) return new Text("Vinculación requerida", "", Tone.WARNING);
        if (!s.started()) return new Text("Conectando…", "", Tone.WARNING);
        return s.connected() ? new Text("Conectado", "", Tone.SUCCESS) : new Text("Sin conexión", "", Tone.WARNING);
    }

    static String lastActivity(Instant observedAt, Instant now) {
        if (observedAt == null) return "Sin actividad registrada todavía";
        long seconds = Math.max(0, Duration.between(observedAt, now).getSeconds());
        if (seconds < 60) return "Última actividad: hace " + seconds + (seconds == 1 ? " segundo" : " segundos");
        long minutes = seconds / 60;
        if (minutes < 60) return "Última actividad: hace " + minutes + (minutes == 1 ? " minuto" : " minutos");
        long hours = minutes / 60;
        return "Última actividad: hace " + hours + (hours == 1 ? " hora" : " horas");
    }

    /** Nombre de este equipo tal como se envió al vincularlo. */
    static String deviceName() { return System.getenv().getOrDefault("COMPUTERNAME", "Equipo Windows"); }
}
