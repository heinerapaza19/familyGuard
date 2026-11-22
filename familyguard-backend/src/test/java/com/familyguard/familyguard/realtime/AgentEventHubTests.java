package com.familyguard.familyguard.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Etapa 5: ciclo de vida de las conexiones SSE, sin red ni esperas (determinista). */
class AgentEventHubTests {

    /** Conexión simulada: registra lo enviado, puede fallar y expone sus callbacks. */
    static final class EmitterFalso extends SseEmitter {
        final List<String> enviados = new ArrayList<>();
        boolean fallar, completado;
        Runnable alCompletar, alExpirar;
        Consumer<Throwable> alFallar;
        @Override public void send(SseEventBuilder builder) throws IOException {
            if (fallar) throw new IOException("cliente desconectado");
            StringBuilder texto = new StringBuilder();
            builder.build().forEach(d -> texto.append(d.getData()));
            enviados.add(texto.toString());
        }
        @Override public synchronized void onCompletion(Runnable callback) { alCompletar = callback; }
        @Override public synchronized void onTimeout(Runnable callback) { alExpirar = callback; }
        @Override public synchronized void onError(Consumer<Throwable> callback) { alFallar = callback; }
        @Override public synchronized void complete() { completado = true; }
        @Override public synchronized void completeWithError(Throwable ex) { completado = true; }
        long avisos() { return enviados.stream().filter(e -> e.contains("POLICY_CHANGED")).count(); }
    }

    static final class HubPrueba extends AgentEventHub {
        final List<EmitterFalso> creados = new ArrayList<>();
        HubPrueba() { super(false); }
        @Override protected SseEmitter nuevoEmitter() { EmitterFalso e = new EmitterFalso(); creados.add(e); return e; }
        EmitterFalso ultimo() { return creados.get(creados.size() - 1); }
    }

    @Test void alSuscribirseRecibeLaRevisionActualSinDatosSensibles() {
        HubPrueba hub = new HubPrueba();
        hub.suscribir(7, 1, 12);
        EmitterFalso e = hub.ultimo();
        assertThat(e.enviados).hasSize(1);
        assertThat(e.enviados.get(0)).contains("\"dispositivoId\":7").contains("\"revision\":12").doesNotContain("token");
        assertThat(hub.conexiones(7)).isEqualTo(1);
    }

    @Test void avisaSoloAlDispositivoAfectadoYSinDuplicados() {
        HubPrueba hub = new HubPrueba();
        hub.suscribir(1, 1, 5); EmitterFalso uno = hub.ultimo();
        hub.suscribir(2, 1, 9); EmitterFalso dos = hub.ultimo();
        hub.cambio(1, 6, 1, true);
        hub.cambio(1, 6, 1, true);   // mismo cambio repetido (p. ej., otra actualización del equipo)
        hub.cambio(1, 4, 1, true);   // revisión anterior: se ignora
        assertThat(uno.avisos()).isEqualTo(2);  // inicial + revisión 6
        assertThat(dos.avisos()).isEqualTo(1);  // solo el inicial
        hub.cambio(99, 50, 1, true); // equipo sin conexión: no afecta a nadie
        assertThat(uno.avisos()).isEqualTo(2);
    }

    @Test void limitePorDispositivoCierraLaConexionMasAntigua() {
        HubPrueba hub = new HubPrueba();
        hub.suscribir(3, 1, 1); EmitterFalso primera = hub.ultimo();
        hub.suscribir(3, 1, 1);
        hub.suscribir(3, 1, 1);
        assertThat(hub.conexiones(3)).isEqualTo(AgentEventHub.MAX_POR_DISPOSITIVO);
        assertThat(primera.completado).isTrue();
        hub.cambio(3, 2, 1, true);
        assertThat(primera.avisos()).isEqualTo(1);
    }

    @Test void revocacionYCredencialRenovadaCierranLasConexiones() {
        HubPrueba hub = new HubPrueba();
        hub.suscribir(4, 1, 3); EmitterFalso revocada = hub.ultimo();
        hub.cambio(4, 4, 1, false);
        assertThat(revocada.completado).isTrue();
        assertThat(hub.conexiones(4)).isZero();
        hub.suscribir(4, 1, 3); EmitterFalso antigua = hub.ultimo();
        hub.cambio(4, 3, 2, true);   // credencial renovada al revincular: la conexión antigua se cierra
        assertThat(antigua.completado).isTrue();
        assertThat(hub.conexiones(4)).isZero();
        assertThat(hub.totalConexiones()).isZero();
    }

    @Test void conexionesAbandonadasSeLiberanAlFallarElEnvioOAlFinalizar() {
        HubPrueba hub = new HubPrueba();
        hub.suscribir(5, 1, 1); EmitterFalso caida = hub.ultimo();
        caida.fallar = true;
        hub.enviarMantenimiento();
        assertThat(hub.conexiones(5)).isZero();

        hub.suscribir(6, 1, 1); EmitterFalso a = hub.ultimo();
        hub.suscribir(7, 1, 1); EmitterFalso b = hub.ultimo();
        hub.suscribir(8, 1, 1); EmitterFalso c = hub.ultimo();
        a.alCompletar.run(); b.alExpirar.run(); c.alFallar.accept(new IOException("reset"));
        a.alCompletar.run();          // repetido: idempotente
        assertThat(hub.totalConexiones()).isZero();
    }

    @Test void limiteGlobalYCierreOrdenado() {
        HubPrueba hub = new HubPrueba();
        for (long id = 1; id <= AgentEventHub.MAX_TOTAL; id++) assertThat(hub.suscribir(id, 1, 0)).isNotNull();
        assertThat(hub.suscribir(AgentEventHub.MAX_TOTAL + 1L, 1, 0)).isNull();
        hub.cerrarTodo();
        assertThat(hub.totalConexiones()).isZero();
        assertThat(hub.creados).allMatch(e -> e.completado);
    }
}
