package com.familyguard.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Avisos en tiempo real del backend (SSE, Etapa 5). Solo acelera la sincronización: al recibir
 * {@code POLICY_CHANGED} de su propio equipo pide un ciclo de descarga autenticada; nunca aplica ni confirma
 * nada por sí mismo. Si no hay Internet o el servidor no responde, reintenta con espera exponencial acotada
 * mientras la supervisión local y la consulta periódica siguen funcionando con normalidad.
 */
final class AgentRealtimeSync implements AutoCloseable {
    /** Conexión abierta: {@link #read} bloquea entregando líneas hasta que el flujo termina o se cierra. */
    interface Connection extends AutoCloseable {
        void read(Consumer<String> line) throws Exception;
        @Override void close();
    }
    interface Connector { Connection open() throws Exception; }
    interface Sleeper { void sleep(Duration duration) throws InterruptedException; }

    static final Duration INACTIVITY_LIMIT = Duration.ofSeconds(90);
    static final Duration QUICK_RECONNECT = Duration.ofSeconds(2);
    private static final Pattern DEVICE = Pattern.compile("\"dispositivoId\"\\s*:\\s*(\\d+)");
    private static final Pattern REVISION = Pattern.compile("\"revision\"\\s*:\\s*(\\d+)");

    private final long deviceId;
    private final Connector connector;
    private final LongConsumer onPolicyChanged;
    private final Consumer<Exception> onError;
    private final RetryBackoff backoff;
    private final Sleeper sleeper;
    private final Supplier<Instant> clock;
    private volatile boolean running;
    private volatile Connection current;
    private volatile Instant lastActivity = Instant.EPOCH;
    private volatile long lastSeenRevision = -1;
    private volatile boolean connected;
    private int failures;
    private Thread thread;
    private ScheduledExecutorService watchdog;

    AgentRealtimeSync(long deviceId, Connector connector, LongConsumer onPolicyChanged, Consumer<Exception> onError) {
        this(deviceId, connector, onPolicyChanged, onError, new RetryBackoff(), d -> Thread.sleep(d.toMillis()), Instant::now);
    }

    AgentRealtimeSync(long deviceId, Connector connector, LongConsumer onPolicyChanged, Consumer<Exception> onError,
                      RetryBackoff backoff, Sleeper sleeper, Supplier<Instant> clock) {
        this.deviceId = deviceId; this.connector = connector; this.onPolicyChanged = onPolicyChanged;
        this.onError = onError; this.backoff = backoff; this.sleeper = sleeper; this.clock = clock;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::runLoop, "familyguard-tiempo-real");
        thread.setDaemon(true);
        thread.start();
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "familyguard-tiempo-real-vigilancia"); t.setDaemon(true); return t;
        });
        watchdog.scheduleWithFixedDelay(this::checkInactivity, 30, 30, TimeUnit.SECONDS);
    }

    /** Pruebas: ejecuta el bucle en el hilo actual (con un {@link Sleeper} simulado) hasta {@link #stop()}. */
    void runInCurrentThread() {
        running = true;
        runLoop();
    }

    private void runLoop() {
        while (running) {
            Duration wait;
            try {
                Connection connection = connector.open();
                current = connection;
                lastSeenRevision = -1;
                lastActivity = clock.get();
                SseEventParser parser = new SseEventParser(this::handle);
                try (connection) {
                    connection.read(line -> {
                        lastActivity = clock.get();
                        if (!connected) { connected = true; failures = 0; }
                        parser.accept(line);
                    });
                }
                // El servidor cerró el flujo (caducidad normal de la conexión): reconexión rápida.
                wait = QUICK_RECONNECT;
            } catch (Throwable error) {
                // Problema N.º 4: también los Error (p. ej., de una librería nativa) se capturan; el hilo nunca muere
                // en silencio y vuelve a intentar con espera creciente.
                if (!running) break;
                failures = Math.min(20, failures + 1);
                try { onError.accept(error instanceof Exception exception ? exception : new IllegalStateException(error)); }
                catch (Throwable ignored) { /* el registro de diagnóstico no debe detener el canal */ }
                wait = backoff.delay(failures);
            } finally {
                current = null;
                connected = false;
            }
            if (!running) break;
            try { sleeper.sleep(wait); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
    }

    /** Cierra una conexión sin datos (ni señales de mantenimiento) durante 90 s para reconectar. */
    void checkInactivity() {
        Connection connection = current;
        if (connection != null && Duration.between(lastActivity, clock.get()).compareTo(INACTIVITY_LIMIT) > 0)
            connection.close();
    }

    private void handle(SseEventParser.Event event) {
        if (!"POLICY_CHANGED".equals(event.name())) return;
        Matcher device = DEVICE.matcher(event.data()), revision = REVISION.matcher(event.data());
        if (!device.find() || !revision.find()) {
            onError.accept(new IllegalStateException("Aviso en tiempo real con formato no válido"));
            return;
        }
        if (Long.parseLong(device.group(1)) != deviceId) {
            // Nunca debería ocurrir (el backend filtra por credencial); se ignora por seguridad.
            onError.accept(new IllegalStateException("Aviso en tiempo real de otro dispositivo ignorado"));
            return;
        }
        long value = Long.parseLong(revision.group(1));
        if (value == lastSeenRevision) return;          // aviso repetido en la misma conexión
        lastSeenRevision = value;
        onPolicyChanged.accept(value);
    }

    int failures() { return failures; }
    boolean isRunning() { return running; }

    synchronized void stop() {
        running = false;
        Connection connection = current;
        if (connection != null) connection.close();
        if (watchdog != null) { watchdog.shutdownNow(); watchdog = null; }
        if (thread != null) { thread.interrupt(); thread = null; }
    }

    @Override public void close() { stop(); }
}
