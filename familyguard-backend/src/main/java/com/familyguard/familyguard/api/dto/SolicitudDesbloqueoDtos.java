package com.familyguard.familyguard.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** «Justificar»: el hijo pide desbloquear una aplicación o un sitio con un mensaje y, si quiere, una foto. */
public final class SolicitudDesbloqueoDtos {
    private SolicitudDesbloqueoDtos() { }

    /** Envío del Agente. La foto va en Base64 (PNG o JPG, máximo 2 MB); el nombre mostrado lo decide el servidor. */
    public record CrearRequest(@NotBlank String tipo, Long aplicacionId, Long sitioId,
                               @NotBlank @Size(min = 3, max = 500) String mensaje,
                               @Size(max = 2_900_000) String fotoBase64) { }

    /** Lo que ve el hijo en su equipo: estado y respuesta del padre. */
    public record AgenteItem(Long id, String tipo, Long aplicacionId, Long sitioId, String objetivo, String estado,
                             String respuesta, Integer minutosAprobados, Instant creadoEn, Instant resueltoEn) { }

    /** Lo que ve el padre en el Administrador. */
    public record Response(Long id, String tipo, String objetivo, Long menorId, String menor, Long dispositivoId,
                           String dispositivo, String mensaje, boolean tieneFoto, String estado, String respuesta,
                           Integer minutosAprobados, Instant creadoEn, Instant resueltoEn) { }

    public record AprobarRequest(@NotNull @Min(5) @Max(720) Integer minutos, @Size(max = 300) String respuesta) { }

    public record RechazarRequest(@Size(max = 300) String respuesta) { }

    public record PendientesResponse(long pendientes, Long ultimaId) { }
}
