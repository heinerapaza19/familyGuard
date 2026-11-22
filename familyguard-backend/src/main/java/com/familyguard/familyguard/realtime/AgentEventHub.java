package com.familyguard.familyguard.realtime;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Conexiones SSE de los Agentes (Etapa 5). Cada conexión pertenece a un único dispositivo, autenticado con
 * su propia credencial, y solo recibe avisos de ese dispositivo. El aviso {@code POLICY_CHANGED} indica que
 * hay una revisión nueva: el Agente descarga la política por la API autenticada y confirma lo que aplicó.
 * Una conexión activa NO es una confirmación de aplicación.
 *
 * <p>Recursos: como máximo {@value #MAX_POR_DISPOSITIVO} conexiones por equipo (la más antigua se cierra) y
 * {@value #MAX_TOTAL} en total; caducan a los 10 minutos (el Agente se reconecta) y reciben una señal de
 * mantenimiento cada 25 s, que también detecta y libera conexiones abandonadas.
 */
@Component
public class AgentEventHub {
    public static final int MAX_POR_DISPOSITIVO = 2;
    public static final int MAX_TOTAL = 5000;
    public static final long TIEMPO_CONEXION_MS = 10 * 60 * 1000L;
    public static final String POLICY_CHANGED = "POLICY_CHANGED";
    private static final Logger log = LoggerFactory.getLogger(AgentEventHub.class);

    /** Una conexión SSE de un dispositivo, con la versión de credencial con la que se autenticó. */
    static final class Conexion {
        final long dispositivoId;
        final int versionCredencial;
        final SseEmitter emitter;
        volatile long ultimaRevisionEnviada;
        Conexion(long dispositivoId, int versionCredencial, SseEmitter emitter, long revision) {
            this.dispositivoId = dispositivoId; this.versionCredencial = versionCredencial;
            this.emitter = emitter; this.ultimaRevisionEnviada = revision;
        }
    }

    private final Map<Long, List<Conexion>> conexiones = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final ScheduledExecutorService mantenimiento;

    public AgentEventHub() { this(true); }

    /** {@code programarMantenimiento=false} en pruebas unitarias: la señal se invoca de forma determinista. */
    AgentEventHub(boolean programarMantenimiento) {
        if (programarMantenimiento) {
            mantenimiento = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "familyguard-sse-mantenimiento"); t.setDaemon(true); return t;
            });
            mantenimiento.scheduleWithFixedDelay(this::enviarMantenimiento, 25, 25, TimeUnit.SECONDS);
        } else mantenimiento = null;
    }

    /** Fábrica sustituible en pruebas. */
    protected SseEmitter nuevoEmitter() { return new SseEmitter(TIEMPO_CONEXION_MS); }

    /**
     * Registra una conexión y envía de inmediato la revisión deseada actual: así, al reconectarse, el Agente
     * recupera los cambios ocurridos mientras estaba desconectado. Devuelve {@code null} si se alcanzó el
     * límite global de conexiones.
     */
    public SseEmitter suscribir(long dispositivoId, int versionCredencial, long revisionDeseada) {
        if (total.get() >= MAX_TOTAL) return null;
        SseEmitter emitter = nuevoEmitter();
        Conexion conexion = new Conexion(dispositivoId, versionCredencial, emitter, revisionDeseada);
        List<Conexion> lista = conexiones.computeIfAbsent(dispositivoId, k -> new CopyOnWriteArrayList<>());
        Conexion antigua = null;
        synchronized (lista) {
            lista.add(conexion);
            total.incrementAndGet();
            if (lista.size() > MAX_POR_DISPOSITIVO) antigua = lista.get(0);
        }
        Runnable limpiar = () -> quitar(conexion);
        emitter.onCompletion(limpiar);
        emitter.onTimeout(limpiar);
        emitter.onError(error -> limpiar.run());
        if (antigua != null) cerrar(antigua);
        enviar(conexion, revisionDeseada);
        log.info("Conexión en tiempo real abierta para el dispositivo {}", dispositivoId);
        return emitter;
    }

    /**
     * Cambio confirmado (después del commit) en un dispositivo. Si la credencial ya no es la misma o el equipo
     * dejó de estar autorizado, se cierran sus conexiones. Si la revisión deseada aumentó, se avisa una sola
     * vez a cada conexión: guardar el equipo por un heartbeat u otro dato no genera avisos.
     */
    public void cambio(long dispositivoId, long revisionDeseada, int versionCredencial, boolean autorizado) {
        List<Conexion> lista = conexiones.get(dispositivoId);
        if (lista == null) return;
        for (Conexion c : lista) {
            if (!autorizado || c.versionCredencial != versionCredencial) {
                cerrar(c);
                log.info("Conexión en tiempo real cerrada para el dispositivo {}: credencial revocada o renovada", dispositivoId);
            } else if (revisionDeseada > c.ultimaRevisionEnviada) {
                enviar(c, revisionDeseada);
            }
        }
    }

    /** Señal de mantenimiento SSE (comentario): mantiene vivos proxies y detecta conexiones abandonadas. */
    void enviarMantenimiento() {
        for (List<Conexion> lista : conexiones.values())
            for (Conexion c : lista) {
                try {
                    c.emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException | IllegalStateException error) {
                    descartar(c);
                }
            }
    }

    public int conexiones(long dispositivoId) {
        List<Conexion> lista = conexiones.get(dispositivoId);
        return lista == null ? 0 : lista.size();
    }

    public int totalConexiones() { return total.get(); }

    @PreDestroy
    public void cerrarTodo() {
        if (mantenimiento != null) mantenimiento.shutdownNow();
        for (List<Conexion> lista : conexiones.values()) for (Conexion c : new ArrayList<>(lista)) cerrar(c);
    }

    // ------------------------------------------------------------------ interno

    /** Evento liviano: sin tokens, contraseñas ni datos personales; solo el equipo y la revisión. */
    static String datos(long dispositivoId, long revision) {
        return "{\"tipo\":\"" + POLICY_CHANGED + "\",\"dispositivoId\":" + dispositivoId + ",\"revision\":" + revision + "}";
    }

    private boolean enviar(Conexion c, long revision) {
        try {
            synchronized (c) {
                c.emitter.send(SseEmitter.event().name(POLICY_CHANGED).id(Long.toString(revision))
                        .reconnectTime(5000).data(datos(c.dispositivoId, revision)));
                c.ultimaRevisionEnviada = Math.max(c.ultimaRevisionEnviada, revision);
            }
            return true;
        } catch (IOException | IllegalStateException error) {
            descartar(c);
            return false;
        }
    }

    private void cerrar(Conexion c) {
        if (quitar(c)) {
            try { c.emitter.complete(); } catch (RuntimeException ignored) { /* ya cerrada */ }
        }
    }

    private void descartar(Conexion c) {
        if (quitar(c)) {
            try { c.emitter.completeWithError(new IOException("Conexión SSE no disponible")); } catch (RuntimeException ignored) { }
            log.debug("Conexión en tiempo real liberada para el dispositivo {}", c.dispositivoId);
        }
    }

    /**
     * Quita la conexión una sola vez (idempotente). La lista vacía de un equipo se conserva (una por equipo
     * vinculado) para no perder una conexión que se registre al mismo tiempo.
     */
    private boolean quitar(Conexion c) {
        List<Conexion> lista = conexiones.get(c.dispositivoId);
        if (lista == null) return false;
        boolean quitada;
        synchronized (lista) {
            quitada = lista.remove(c);
            if (quitada) total.decrementAndGet();
        }
        return quitada;
    }
}
