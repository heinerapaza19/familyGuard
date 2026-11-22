package com.familyguard.familyguard.api.dto;

import com.familyguard.familyguard.domain.model.TipoReglaHorario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class RestriccionDtos {
    private RestriccionDtos() { }

    public record RestriccionRequest(@NotNull Long menorId, @NotNull Long aplicacionId,
            @Min(1) @Max(1440) int minutosDiarios, boolean bloqueoTotal,
            @Min(0) @Max(60) int advertenciaMinutos,
            @NotNull @Size(max = 28) List<@Valid HorarioRequest> horarios,
            /* Vigencia opcional (fechas inclusivas en la zona de la familia); null = sin fecha de inicio o de fin. */
            LocalDate vigenteDesde, LocalDate vigenteHasta) {
        public RestriccionRequest(Long menorId, Long aplicacionId, int minutosDiarios, boolean bloqueoTotal,
                int advertenciaMinutos, List<HorarioRequest> horarios) {
            this(menorId, aplicacionId, minutosDiarios, bloqueoTotal, advertenciaMinutos, horarios, null, null);
        }
        @AssertTrue(message = "La fecha de fin no puede ser anterior a la de inicio")
        public boolean isVigenciaValida() { return vigenteDesde == null || vigenteHasta == null || !vigenteHasta.isBefore(vigenteDesde); }
    }
    public record HorarioRequest(@NotNull DayOfWeek dia, @NotNull LocalTime inicio,
            @NotNull LocalTime fin, @NotNull TipoReglaHorario tipo) {
        @AssertTrue public boolean isValido() { return inicio == null || fin == null || !inicio.equals(fin); }
    }
    public record HorarioResponse(Long id, DayOfWeek dia, LocalTime inicio, LocalTime fin,
            boolean cruzaMedianoche, TipoReglaHorario tipo) { }
    public record RestriccionResponse(Long id, Long menorId, String menorNombre, Long aplicacionId,
            String aplicacionNombre, Long dispositivoId, String dispositivoNombre, String rutaEjecutable,
            int minutosDiarios, boolean bloqueoTotal, int advertenciaMinutos, long consumidoSegundos,
            long restanteSegundos, boolean agotado, boolean activo, Instant actualizadoEn,
            String sincronizacion, List<HorarioResponse> horarios, LocalDate vigenteDesde, LocalDate vigenteHasta) { }
    public record ReglasAgenteResponse(Instant generadoEn, String zonaHoraria, List<ReglaAgente> reglas) { }
    public record ReglaAgente(Long restriccionId, Long aplicacionId, String nombre, String rutaEjecutable,
            int limiteSegundos, int advertenciaSegundos, boolean bloqueoTotal, long consumidoHoySegundos,
            long revision, List<HorarioAgente> horarios, LocalDate vigenteDesde, LocalDate vigenteHasta) { }
    public record HorarioAgente(DayOfWeek dia, LocalTime inicio, LocalTime fin,
            boolean cruzaMedianoche, TipoReglaHorario tipo) { }
    public record EventosRestriccionRequest(@NotNull @Size(max = 100)
            List<@Valid EventoRestriccionItem> eventos) { }
    public record EventoRestriccionItem(@NotNull UUID eventoId, @NotNull Long restriccionId,
            @NotBlank @Size(max = 32) String tipo, @NotBlank @Size(max = 500) String detalle,
            @NotNull Instant ocurridoEn, boolean exitoso) { }
    public record EventosRestriccionResponse(int aceptados, List<UUID> confirmados) { }
}
