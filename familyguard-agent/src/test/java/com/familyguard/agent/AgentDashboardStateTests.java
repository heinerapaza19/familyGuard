package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** El panel solo muestra «Tu equipo está protegido» cuando el estado real lo confirma. */
class AgentDashboardStateTests {
    private static final Instant NOW = Instant.parse("2026-11-19T20:00:00Z");
    private static AgentDashboardState.Snapshot state(boolean running, boolean connected, boolean revoked, Instant paused) {
        return new AgentDashboardState.Snapshot(true, running, connected, revoked, paused, NOW.minusSeconds(5));
    }

    @Test
    void elBannerNuncaAfirmaProteccionSinConfirmarla() {
        assertTrue(AgentDashboardState.banner(state(true, true, false, null), ZoneOffset.UTC).protectedState());
        assertEquals("Tu equipo está protegido", AgentDashboardState.banner(state(true, true, false, null), ZoneOffset.UTC).title());
        assertFalse(AgentDashboardState.banner(AgentDashboardState.Snapshot.initial(), ZoneOffset.UTC).protectedState());
        assertEquals("Vinculación requerida", AgentDashboardState.banner(state(true, true, true, null), ZoneOffset.UTC).title());
        assertEquals("Supervisión detenida", AgentDashboardState.banner(state(false, true, false, null), ZoneOffset.UTC).title());
        assertEquals("Supervisión en pausa", AgentDashboardState.banner(state(true, true, false, NOW.plusSeconds(900)), ZoneOffset.UTC).title());
        assertFalse(AgentDashboardState.banner(state(true, false, false, null), ZoneOffset.UTC).protectedState());
    }

    @Test
    void tarjetasConValoresReales() {
        assertEquals("En ejecución", AgentDashboardState.agent(state(true, true, false, null), NOW).value());
        assertEquals("Última actividad: hace 5 segundos", AgentDashboardState.agent(state(true, true, false, null), NOW).detail());
        assertEquals("Activa", AgentDashboardState.supervision(state(true, true, false, null), ZoneOffset.UTC).value());
        assertEquals("En pausa", AgentDashboardState.supervision(state(true, true, false, NOW.plusSeconds(600)), ZoneOffset.UTC).value());
        assertEquals("Sin iniciar", AgentDashboardState.supervision(AgentDashboardState.Snapshot.initial(), ZoneOffset.UTC).value());
        assertEquals("Vinculación requerida", AgentDashboardState.connection(state(true, false, true, null)).value());
        assertEquals("La autorización fue revocada", AgentDashboardState.connection(state(true, false, true, null)).detail());
        assertEquals("Vinculado", AgentDashboardState.connection(state(true, true, false, null)).value());
        assertEquals(AgentDashboardState.Tone.SUCCESS, AgentDashboardState.connection(state(true, true, false, null)).tone());
    }

    @Test
    void laBarraInferiorNoDiceListoSinConexion() {
        assertEquals("Sin conexión", AgentDashboardState.footer(state(true, false, false, null)).value());
        assertEquals("Conectado", AgentDashboardState.footer(state(true, true, false, null)).value());
        assertEquals("Conectando…", AgentDashboardState.footer(AgentDashboardState.Snapshot.initial()).value());
        assertEquals("Última actividad: hace 2 minutos", AgentDashboardState.lastActivity(NOW.minusSeconds(150), NOW));
        assertEquals("Sin actividad registrada todavía", AgentDashboardState.lastActivity(null, NOW));
    }
}
