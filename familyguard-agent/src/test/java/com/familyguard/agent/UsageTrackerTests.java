package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.familyguard.agent.DetectionModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class UsageTrackerTests {
    private static final ZoneId ZONE = ZoneId.of("America/Lima");
    private static final ActiveApplication CHROME = new ActiveApplication(10, "chrome.exe", "C:\\Chrome\\chrome.exe");
    private static final ActiveApplication EDGE = new ActiveApplication(20, "msedge.exe", "C:\\Edge\\msedge.exe");

    @Test
    void calculaTiempoDeLaAplicacionActiva() {
        var tracker = tracker(); Instant start = instant("2026-11-15T10:00:00Z");
        tracker.observe(active(CHROME, start));
        tracker.observe(active(CHROME, start.plusSeconds(5)));
        tracker.observe(locked(start.plusSeconds(8)));
        assertEquals(8, tracker.pendingBatch(10).get(0).durationSeconds());
    }

    @Test
    void cierraUnaSesionAlCambiarDeAplicacion() {
        var tracker = tracker(); Instant start = instant("2026-11-15T10:00:00Z");
        tracker.observe(active(CHROME, start));
        tracker.observe(active(EDGE, start.plusSeconds(10)));
        tracker.observe(locked(start.plusSeconds(15)));
        var events = tracker.pendingBatch(10);
        assertEquals(List.of("chrome.exe", "msedge.exe"), events.stream().map(UsageEvent::applicationName).toList());
        assertEquals(List.of(10L, 5L), events.stream().map(UsageEvent::durationSeconds).toList());
    }

    @Test
    void acumulaSesionesSeparadasDeLaMismaAplicacion() {
        var tracker = tracker(); Instant start = instant("2026-11-15T10:00:00Z");
        tracker.observe(active(CHROME, start)); tracker.observe(locked(start.plusSeconds(5)));
        tracker.observe(active(CHROME, start.plusSeconds(10))); tracker.observe(locked(start.plusSeconds(17)));
        assertEquals(12, tracker.pendingBatch(10).stream().mapToLong(UsageEvent::durationSeconds).sum());
    }

    @Test
    void excluyeElIntervaloDeSuspension() {
        var tracker = tracker(); Instant start = instant("2026-11-15T10:00:00Z");
        tracker.observe(active(CHROME, start)); tracker.observe(active(CHROME, start.plusSeconds(5)));
        tracker.observe(active(CHROME, start.plusSeconds(100))); tracker.observe(locked(start.plusSeconds(105)));
        assertEquals(10, tracker.pendingBatch(10).stream().mapToLong(UsageEvent::durationSeconds).sum());
    }

    @Test
    void noCuentaTiempoMientrasWindowsEstaBloqueado() {
        var tracker = tracker(); Instant start = instant("2026-11-15T10:00:00Z");
        tracker.observe(locked(start)); tracker.observe(locked(start.plusSeconds(30)));
        assertTrue(tracker.pendingBatch(10).isEmpty());
    }

    @Test
    void divideSesionesAlCambiarLaFechaLocal() {
        var tracker = tracker(); Instant beforeMidnight = instant("2026-11-16T04:59:58Z");
        tracker.observe(active(CHROME, beforeMidnight));
        tracker.observe(active(CHROME, beforeMidnight.plusSeconds(4)));
        tracker.observe(locked(beforeMidnight.plusSeconds(7)));
        assertEquals(List.of(2L, 5L), tracker.pendingBatch(10).stream().map(UsageEvent::durationSeconds).toList());
    }

    @Test
    void recuperaSesionAbiertaTrasReinicioSinContarLaPausa() {
        var persistence = new MemoryPersistence(); Instant start = instant("2026-11-15T10:00:00Z");
        var first = new UsageTracker(persistence, ZONE);
        first.observe(active(CHROME, start)); first.observe(active(CHROME, start.plusSeconds(10)));
        var recovered = new UsageTracker(persistence, ZONE);
        recovered.observe(active(CHROME, start.plusSeconds(100)));
        assertEquals(10, recovered.pendingBatch(10).get(0).durationSeconds());
    }

    @Test
    void eliminaSoloRegistrosConfirmadosPorElBackend() {
        var tracker = tracker(); Instant start = instant("2026-11-15T10:00:00Z");
        tracker.observe(active(CHROME, start)); tracker.observe(locked(start.plusSeconds(5)));
        tracker.observe(active(EDGE, start.plusSeconds(8))); tracker.observe(locked(start.plusSeconds(12)));
        UUID confirmed = tracker.pendingBatch(10).get(0).eventId();
        tracker.acknowledge(List.of(confirmed));
        assertEquals(1, tracker.pendingCount());
        assertNotEquals(confirmed, tracker.pendingBatch(10).get(0).eventId());
    }

    @Test
    void generaCortesParcialesSinDetenerLaAplicacionActiva() {
        var tracker = tracker(); Instant start = instant("2026-11-15T10:00:00Z");
        tracker.observe(active(CHROME, start));
        tracker.observe(active(CHROME, start.plusSeconds(8)));
        tracker.checkpoint(start.plusSeconds(9));
        assertEquals(9, tracker.pendingBatch(10).get(0).durationSeconds());
        tracker.observe(active(CHROME, start.plusSeconds(12)));
        tracker.observe(locked(start.plusSeconds(14)));
        assertEquals(14, tracker.pendingBatch(10).stream().mapToLong(UsageEvent::durationSeconds).sum());
    }

    private static UsageTracker tracker() { return new UsageTracker(new MemoryPersistence(), ZONE); }
    private static ActivityObservation active(ActiveApplication app, Instant at) { return new ActivityObservation(app, false, at); }
    private static ActivityObservation locked(Instant at) { return new ActivityObservation(null, true, at); }
    private static Instant instant(String value) { return Instant.parse(value); }

    private static final class MemoryPersistence implements UsagePersistence {
        private UsageState state = new UsageState(null, List.of());
        public UsageState snapshot() { return state; }
        public void persist(OpenUsage current, List<UsageEvent> pending) {
            state = new UsageState(current, List.copyOf(pending));
        }
    }
}
