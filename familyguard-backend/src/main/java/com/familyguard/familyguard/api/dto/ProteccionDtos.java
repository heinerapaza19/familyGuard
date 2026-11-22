package com.familyguard.familyguard.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PIN parental, autorizaciones de pausa o desinstalación y eventos locales del Agente. */
public final class ProteccionDtos {
    private ProteccionDtos() { }

    public record CambiarPinRequest(@NotBlank String password, @NotBlank @Size(min = 4, max = 8) String pin,
                                    @NotBlank String confirmarPin) { }
    public record VerPinRequest(@NotBlank String password) { }
    public record PinEstadoResponse(boolean configurado, int version, Instant actualizadoEn) { }
    /** El PIN solo se muestra {@code segundosVisible} segundos en el Administrador. */
    public record PinVisibleResponse(String pin, int version, int segundosVisible) { }

    public record AutorizacionRequest(@NotNull Long dispositivoId, @NotBlank String tipo, Integer minutos) { }
    public record AutorizacionResponse(Long id, Long dispositivoId, String dispositivo, String tipo, Instant autorizadoEn,
                                       Instant expiraEn, Instant usadaEn, Instant canceladaEn, String estado) { }

    // Agente
    public record PinAgenteResponse(boolean configurado, int version, String verificador) { }
    public record AutorizacionAgente(Long id, String tipo, Instant expiraEn) { }
    public record AutorizacionesAgenteResponse(Instant servidorEn, List<AutorizacionAgente> autorizaciones) { }
    public record EventoLocal(@NotNull UUID eventoId, @NotBlank @Size(max = 40) String tipo,
                              @Size(max = 500) String detalle, @NotNull Instant ocurridoEn) { }
    public record EventosLocalesRequest(@NotNull @Size(max = 100) List<@Valid EventoLocal> eventos) { }
    public record EventosLocalesResponse(List<UUID> confirmados) { }
}
