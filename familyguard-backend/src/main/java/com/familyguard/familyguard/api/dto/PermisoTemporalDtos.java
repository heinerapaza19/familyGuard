package com.familyguard.familyguard.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Permisos temporales: concesión del padre, estado real por equipo y contrato con el Agente. */
public final class PermisoTemporalDtos {
    private PermisoTemporalDtos() { }

    /**
     * Solicitud del padre. {@code aplicacionId} es una aplicación de cualquiera de los equipos elegidos: en los
     * demás se usa la aplicación con el mismo ejecutable. {@code minutosExtra} solo para TIEMPO_EXTRA;
     * {@code duracionMinutos} para los demás tipos.
     */
    public record Request(@NotNull UUID concesionId, @NotNull Long menorId,
                          @NotEmpty @Size(max = 100) List<@NotNull Long> dispositivoIds,
                          @NotBlank String tipo, Long aplicacionId, Long sitioId,
                          Integer minutosExtra, Integer duracionMinutos, @Size(max = 200) String motivo) { }

    public record EquipoEstado(Long filaId, Long dispositivoId, String dispositivo, boolean conectado,
                               String estado, String detalle, Instant actualizadoEn) { }

    public record PermisoResponse(Long id, UUID concesionId, Long menorId, String menor, String tipo, String objetivo,
                                  Integer minutosExtra, Instant inicioEn, Instant expiraEn, String motivo,
                                  String estado, String resumen, List<EquipoEstado> equipos, List<String> omitidos) { }

    // ----------------------------------------------------------------------------------------- Agente
    public record PermisoAgente(Long filaId, Long permisoId, String tipo, Long aplicacionId, String rutaEjecutable,
                                Long sitioId, String dominio, Integer minutosExtra, Instant inicioEn, Instant expiraEn) { }

    public record PermisosAgenteResponse(Instant servidorEn, List<PermisoAgente> permisos) { }

    public record EventoPermiso(@NotNull Long filaId, @NotBlank String estado, @Size(max = 500) String detalle,
                                @NotNull Instant ocurridoEn) { }

    public record EventosPermisoRequest(@NotNull @Size(max = 200) List<@Valid EventoPermiso> eventos) { }

    public record EventosPermisoResponse(int aceptados) { }
}
