package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Etapa 5: canal en tiempo real del Agente, sin red ni esperas reales. Cada «paso» simula una conexión
 * (líneas recibidas) o un fallo de red; el temporizador falso registra las esperas de reintento.
 */
class AgentRealtimeSyncTests {
    private static final long DEVICE = 7;

    /** Guion de conexiones: cada elemento es una lista de líneas o una excepción. */
    static final class Script implements AgentRealtimeSync.Connector {
        final Deque<Object> steps = new ArrayDeque<>();
        int opened, closed;
        AgentRealtimeSync sync;
        Script lines(String... lines) { steps.add(List.of(lines)); return this; }
        Script fail(Exception error) { steps.add(error); return this; }
        Script fail(Error error) { steps.add(error); return this; }
        @Override public AgentRealtimeSync.Connection open() throws Exception {
            Object step = steps.poll();
            if (step == null) { sync.stop(); throw new java.io.IOException("fin del guion"); }
            if (step instanceof Exception error) throw error;
            if (step instanceof Error error) throw error;
            opened++;
            @SuppressWarnings("unchecked") List<String> lines = (List<String>) step;
            return new AgentRealtimeSync.Connection() {
                @Override public void read(Consumer<String> line) { lines.forEach(line); }
                @Override public void close() { closed++; }
            };
        }
    }

    private static String[] aviso(long device, long revision) {
        return new String[] {"event:POLICY_CHANGED", "data:{\"tipo\":\"POLICY_CHANGED\",\"dispositivoId\":" + device
                + ",\"revision\":" + revision + "}", ""};
    }
    private static String[] concat(String[]... parts) {
        List<String> all = new ArrayList<>();
        for (String[] p : parts) all.addAll(List.of(p));
        return all.toArray(String[]::new);
    }

    private record Run(List<Long> revisions, List<Duration> waits, List<Exception> errors, Script script) { }

    private static Run run(Script script) {
        List<Long> revisions = new ArrayList<>();
        List<Duration> waits = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();
        AgentRealtimeSync sync = new AgentRealtimeSync(DEVICE, script, revisions::add, errors::add,
                new RetryBackoff(300), waits::add, Instant::now);
        script.sync = sync;
        sync.runInCurrentThread();
        return new Run(revisions, waits, errors, script);
    }

    @Test void avisoDelPropioEquipoPideSincronizarYIgnoraAjenosYRepetidos() {
        Run r = run(new Script().lines(concat(aviso(DEVICE, 5), new String[] {":keepalive", ""}, aviso(DEVICE, 5),
                aviso(99, 50), aviso(DEVICE, 6), new String[] {"event:OTRO", "data:x", ""})));
        assertEquals(List.of(5L, 6L), r.revisions());
        assertTrue(r.errors().stream().anyMatch(e -> e.getMessage().contains("otro dispositivo")));
        assertEquals(1, r.script().closed);
    }

    @Test void sinInternetReintentaConEsperaExponencialAcotadaYNoPideSincronizar() {
        Script script = new Script();
        for (int i = 0; i < 12; i++) script.fail(new java.net.ConnectException("sin red"));
        Run r = run(script);
        assertTrue(r.revisions().isEmpty());
        List<Duration> esperadas = new ArrayList<>();
        for (int i = 1; i <= 12; i++) esperadas.add(new RetryBackoff(300).delay(i));
        assertEquals(esperadas, r.waits().subList(0, 12));
        assertEquals(Duration.ofSeconds(300), r.waits().get(11));     // límite de 5 minutos
        assertTrue(r.errors().size() >= 12);
    }

    @Test void alReconectarseRecuperaLaRevisionVigenteYReiniciaLaEspera() {
        Run r = run(new Script().lines(aviso(DEVICE, 3)).fail(new java.io.IOException("corte")).fail(new java.io.IOException("corte"))
                .lines(aviso(DEVICE, 3)).lines(aviso(DEVICE, 8)));
        // La revisión 3 se vuelve a entregar tras la reconexión (el Agente decide si ya la aplicó); luego la 8.
        assertEquals(List.of(3L, 3L, 8L), r.revisions());
        assertEquals(AgentRealtimeSync.QUICK_RECONNECT, r.waits().get(0));
        assertEquals(new RetryBackoff(300).delay(1), r.waits().get(1));
        assertEquals(new RetryBackoff(300).delay(2), r.waits().get(2));
        assertEquals(AgentRealtimeSync.QUICK_RECONNECT, r.waits().get(3));  // conectado de nuevo: espera reiniciada
    }

    @Test void credencialRevocadaNoProvocaReintentosRapidos() {
        Script script = new Script();
        for (int i = 0; i < 4; i++) script.fail(new AgentApiClient.ApiException(401, "revocada"));
        Run r = run(script);
        assertTrue(r.waits().subList(0, 4).stream().allMatch(w -> w.compareTo(Duration.ofSeconds(2)) >= 0));
        assertTrue(r.waits().get(3).compareTo(r.waits().get(0)) > 0);
        assertTrue(r.revisions().isEmpty());
    }

    @Test void conexionSinActividadSeCierraParaReconectar() {
        Instant[] now = {Instant.parse("2026-11-19T10:00:00Z")};
        List<Long> revisions = new ArrayList<>();
        int[] closed = {0};
        AgentRealtimeSync[] holder = new AgentRealtimeSync[1];
        AgentRealtimeSync.Connector connector = () -> new AgentRealtimeSync.Connection() {
            @Override public void read(Consumer<String> line) {
                line.accept(":keepalive");
                now[0] = now[0].plusSeconds(91);         // 91 s sin datos: la vigilancia cierra la conexión
                holder[0].checkInactivity();
                holder[0].stop();
            }
            @Override public void close() { closed[0]++; }
        };
        holder[0] = new AgentRealtimeSync(DEVICE, connector, revisions::add, e -> { }, new RetryBackoff(300),
                d -> { }, () -> now[0]);
        holder[0].runInCurrentThread();
        assertTrue(closed[0] >= 1);
        assertFalse(holder[0].isRunning());
    }

    /** Problema N.º 4: un Error (no solo una excepción) tampoco detiene el canal en silencio. */
    @Test void unErrorGraveSeRegistraYElCanalSigueFuncionando() {
        Run r = run(new Script().fail(new LinkageError("librería nativa no disponible")).lines(aviso(DEVICE, 4)));
        assertEquals(List.of(4L), r.revisions());
        assertTrue(r.errors().stream().anyMatch(e -> e.getCause() instanceof LinkageError));
        assertEquals(new RetryBackoff(300).delay(1), r.waits().get(0));
    }

        @Test void datosInvalidosNoDetienenElCanal() {
        Run r = run(new Script().lines("event:POLICY_CHANGED", "data:no-json", "", aviso(DEVICE, 2)[0], aviso(DEVICE, 2)[1], ""));
        assertEquals(List.of(2L), r.revisions());
        assertTrue(r.errors().stream().anyMatch(e -> e.getMessage().contains("formato")));
    }
}
