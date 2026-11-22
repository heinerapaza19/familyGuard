package com.familyguard.familyguard.api.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class VinculacionDtos {
    private VinculacionDtos() { }
    /** {@code codigoId} permite al Administrador consultar si el agente ya se vinculó. */
    public record CodigoResponse(String codigo, Instant expiraEn, Long codigoId) { }
    /** Estado de un código: PENDIENTE, VINCULADO, VENCIDO o REEMPLAZADO. */
    public record EstadoCodigoResponse(Long codigoId, String estado, Instant expiraEn,
            Long dispositivoId, String dispositivoNombre) { }
    public record VincularRequest(@NotBlank @Pattern(regexp = "[A-Z0-9]{8}") String codigo,
            @NotBlank @Size(max = 120) String nombre,
            @NotBlank @Size(max = 64) String identificador,
            @NotBlank @Size(max = 120) String sistemaOperativo,
            @NotBlank @Size(max = 80) String versionSistema,
            @Size(max=160) String usuarioLocal,@Size(max=40) String versionAgente) { }
    public record VincularResponse(Long dispositivoId, String agenteToken, String estado) { }
    public record InventarioRequest(
            @NotNull @Size(max = 500) List<AplicacionItem> aplicaciones,
            @NotNull @Size(max = 1000) List<ProcesoItem> procesos,
            @NotNull Instant observadoEn) { }
    public record AplicacionItem(@NotBlank @Size(max = 180) String nombre,
            @Size(max = 700) String rutaEjecutable, @Size(max = 80) String version,
            @Size(max = 160) String editor, @Size(max = 32) String tipoInstalacion) { }
    public record ProcesoItem(@Positive long pid, @NotBlank @Size(max = 180) String nombre,
            @Size(max = 700) String rutaEjecutable, boolean ventanaActiva) { }
    public record EstadoAgenteResponse(Long dispositivoId, String estado, Instant ultimaComunicacion) { }
    public record UsoRequest(@NotNull @Size(max = 200) List<UsoItem> registros) { }
    public record UsoItem(@NotNull UUID registroId,
            @NotBlank @Size(max = 180) String aplicacionNombre,
            @Size(max = 700) String rutaEjecutable,
            @Positive long pid,
            @NotNull Instant inicioEn,
            @NotNull Instant finEn,
            @Positive @Max(86400) long duracionSegundos) { }
    /**
     * {@code confirmados}: UUID procesados definitivamente (aceptados, duplicados o rechazados) que el
     * agente debe retirar de su cola. {@code rechazados}: detalle de los registros descartados.
     */
    public record UsoResponse(int aceptados, List<UUID> confirmados, int duplicados, List<UsoRechazado> rechazados) {
        public UsoResponse(int aceptados, List<UUID> confirmados) { this(aceptados, confirmados, 0, List.of()); }
    }
    public record UsoRechazado(UUID registroId, String codigo, String motivo) { }
    public record HeartbeatRequest(@NotNull Instant enviadoEn,@Min(15)@Max(3600)int intervaloSegundos,
            @Size(max=160)String usuarioLocal,@Size(max=40)String versionAgente,@Size(max=500)String ultimoError,
            @PositiveOrZero long revisionAplicada){}
    public record HeartbeatResponse(Long dispositivoId,String estado,Instant servidorEn,long revisionDeseada,
            long revisionAplicada,boolean cambiosPendientes,int proximoHeartbeatSegundos){}
    public record ConfirmacionRequest(@NotNull UUID confirmacionId,@PositiveOrZero long revision,
            @NotBlank @Pattern(regexp="APLICADA|FALLIDA")String estado,@Size(max=500)String detalle,@NotNull Instant confirmadoEn){}
    public record ConfirmacionResponse(boolean aceptada,long revisionAplicada,long revisionDeseada){}
}
