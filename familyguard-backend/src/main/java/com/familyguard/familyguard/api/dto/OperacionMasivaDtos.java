package com.familyguard.familyguard.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contratos de las restricciones masivas (Etapa 4). */
public final class OperacionMasivaDtos {
    private OperacionMasivaDtos() { }

    public static final int MAX_DISPOSITIVOS = 200;

    /**
     * Tipos: BLOQUEAR_SITIO y RETIRAR_SITIO (usan {@code dominio}); LIMITAR_APLICACION (usa
     * {@code aplicacionId} como referencia y {@code minutosDiarios}); RETIRAR_LIMITE (usa {@code aplicacionId}).
     * {@code operacionId} lo genera el Administrador y se repite en los reintentos.
     */
    public record Request(@NotNull UUID operacionId,
                          @NotBlank @Size(max = 24) String tipo,
                          @NotEmpty @Size(max = MAX_DISPOSITIVOS) List<@NotNull Long> dispositivoIds,
                          @Size(max = 500) String dominio,
                          Long aplicacionId,
                          @Min(1) @Max(1440) Integer minutosDiarios) { }

    public record PreviewItem(Long dispositivoId, String dispositivoNombre, String menorNombre, boolean conectado,
                              String resultado, String detalle) { }

    public record PreviewResponse(String tipo, String objetivo, int total, int conectados, int desconectados,
                                  int programados, int sinCambios, int noAplicables, String mensaje,
                                  List<PreviewItem> dispositivos) { }

    /**
     * {@code resultado}: lo decidido al registrar la operación. {@code estado}: estado vivo y verificable
     * (PENDIENTE_ENTREGA, DESCONECTADO, RECIBIDA, APLICADA, ERROR, CANCELADA, SIN_CAMBIOS,
     * APLICACION_NO_REGISTRADA, APLICACION_AMBIGUA, REVOCADO, RETIRADA).
     */
    public record ResultadoDispositivo(Long dispositivoId, String dispositivoNombre, String menorNombre,
                                       boolean conectado, String resultado, String estado, String detalle,
                                       Long revisionObjetivo) { }

    public record OperacionResponse(Long id, UUID operacionId, String tipo, String objetivo, String parametros,
                                    String estado, Instant creadoEn, Instant canceladaEn, int total,
                                    Map<String, Integer> resumen, boolean completa, String mensaje,
                                    List<ResultadoDispositivo> dispositivos) { }
}
