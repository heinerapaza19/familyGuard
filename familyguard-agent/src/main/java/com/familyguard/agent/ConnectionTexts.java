package com.familyguard.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Textos de la pantalla «Conexión». Nunca muestra «Conectado» solo porque haya Wi-Fi: el estado del servidor
 * depende de una respuesta autenticada de FamilyGuard; la falta de red se muestra como protección offline.
 */
final class ConnectionTexts {
    enum Tone { SUCCESS, INFO, WARNING, DANGER, NEUTRAL }
    record Line(String text, Tone tone) { }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private ConnectionTexts() { }

    static Line internet(NetworkMonitor.Snapshot n) {
        if (n == null) return new Line("Comprobando…", Tone.NEUTRAL);
        return switch (n.internet()) {
            case CHECKING -> new Line("Comprobando…", Tone.NEUTRAL);
            case ONLINE -> new Line("Con Internet", Tone.SUCCESS);
            case LOCAL_ONLY -> new Line("Red local sin acceso a Internet", Tone.WARNING);
            case OFFLINE -> new Line("Sin Internet", Tone.DANGER);
            case UNKNOWN -> new Line("Red disponible (Windows no informó si hay Internet)", Tone.INFO);
        };
    }

    static String medium(NetworkMonitor.Snapshot n) {
        if (n == null || n.internet() == NetworkMonitor.Internet.CHECKING) return "Comprobando…";
        if (n.internet() == NetworkMonitor.Internet.OFFLINE) return "Ninguna conexión activa";
        return n.medium() == null ? "No identificado por Windows" : n.medium();
    }

    static Line server(AgentRuntime.ConnectionInfo c, Instant now) {
        if (!c.running()) return new Line("Agente detenido", Tone.NEUTRAL);
        if (c.sync().authorizationRequired()) return new Line("Autorización revocada: se requiere una nueva vinculación", Tone.DANGER);
        if (c.serverConnected()) return new Line("Conectado y autorizado", Tone.SUCCESS);
        if (c.sync().lastAttempt() == null) return new Line("Comprobando…", Tone.NEUTRAL);
        if (!AgentRuntime.localServer(c.serverUrl()) && c.network() != null && c.network().internet() == NetworkMonitor.Internet.OFFLINE)
            return new Line("Sin conexión — Protección offline", Tone.WARNING);
        return new Line("El servidor no responde — Protección offline", Tone.WARNING);
    }

    static String retries(ConnectionSupervisor.View s, Instant now) {
        if (s.authorizationRequired()) return "En espera hasta una nueva vinculación autorizada";
        if (s.consecutiveFailures() == 0) return s.totalRetries() == 0 ? "Sin reintentos" : "Sin reintentos pendientes (" + s.totalRetries() + " desde el inicio)";
        String next = s.nextAttempt() == null || !s.nextAttempt().isAfter(now) ? "ahora" : "en " + wait(Duration.between(now, s.nextAttempt()));
        return s.consecutiveFailures() + (s.consecutiveFailures() == 1 ? " intento fallido" : " intentos fallidos") + " · próximo intento " + next;
    }

    static String lastSync(Instant last, ZoneId zone, Instant now) {
        if (last == null) return "Aún no se ha sincronizado en este equipo";
        return WHEN.format(last.atZone(zone)) + " (hace " + wait(Duration.between(last, now)) + ")";
    }

    static Line offline(AgentRuntime.ConnectionInfo c) {
        String rules = c.applicationRules() + (c.applicationRules() == 1 ? " regla de aplicaciones" : " reglas de aplicaciones")
                + " y " + c.webRules() + (c.webRules() == 1 ? " regla web" : " reglas web");
        if (c.serverConnected()) return new Line("Reglas al día: " + rules + " guardadas y cifradas en este equipo", Tone.SUCCESS);
        return new Line("Protección offline activa: se aplican " + rules + " guardadas, con sus fechas y horarios", Tone.INFO);
    }

    static String pending(int count) {
        return count == 0 ? "Ninguno" : count + (count == 1 ? " evento se enviará" : " eventos se enviarán") + " al reconectar";
    }

    static Line serverKind(String url) {
        if (AgentRuntime.localServer(url)) return new Line("Desarrollo local: solo funciona en este equipo", Tone.WARNING);
        return url != null && url.toLowerCase(java.util.Locale.ROOT).startsWith("https://")
                ? new Line("Servidor remoto HTTPS (certificado validado)", Tone.SUCCESS)
                : new Line("Dirección no válida para Internet", Tone.DANGER);
    }

    static String serverHint(String url) {
        if (AgentRuntime.localServer(url)) return "Para conectarse desde cualquier Wi-Fi hace falta un servidor FamilyGuard con HTTPS publicado y autorizado. "
                + "Todavía no hay uno configurado (variable FAMILYGUARD_API_URL o server.properties). Mientras tanto, la protección funciona offline.";
        return "Este equipo se conecta a esta dirección desde cualquier red con Internet; no hace falta cambiar IP al cambiar de Wi-Fi.";
    }

    /** «8 s», «3 min», «2 h 5 min», «3 días». */
    static String wait(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 48) return h + " h" + (m % 60 == 0 ? "" : " " + m % 60 + " min");
        return h / 24 + " días";
    }
}
