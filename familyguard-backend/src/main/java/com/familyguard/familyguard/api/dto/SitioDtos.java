package com.familyguard.familyguard.api.dto;
import com.familyguard.familyguard.domain.model.*;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.time.*;import java.util.*;
public final class SitioDtos{private SitioDtos(){}
 public record Request(@NotNull Long menorId,@NotEmpty List<@NotNull Long> dispositivoIds,@NotBlank @Size(max=500) String dominio,
  boolean incluirSubdominios,@NotNull CategoriaSitio categoria,@NotNull TipoReglaSitio modo,boolean permanente,
  Instant vigenteDesde,Instant vigenteHasta,@Size(max=255)String motivo,@NotNull @Size(max=28)List<@Valid HorarioRequest> horarios){}
 public record HorarioRequest(@NotNull DayOfWeek dia,@NotNull LocalTime inicio,@NotNull LocalTime fin,@NotNull TipoReglaSitio tipo){@AssertTrue public boolean isValido(){return inicio==null||fin==null||!inicio.equals(fin);}}
 public record HorarioResponse(DayOfWeek dia,LocalTime inicio,LocalTime fin,boolean cruzaMedianoche,TipoReglaSitio tipo){}
 public record Response(Long id,Long menorId,String menorNombre,String dominio,boolean incluirSubdominios,CategoriaSitio categoria,
  TipoReglaSitio modo,boolean permanente,Instant vigenteDesde,Instant vigenteHasta,String motivo,boolean activo,long revision,
  List<Long> dispositivoIds,List<String> dispositivos,List<HorarioResponse> horarios,String sincronizacion,
  /* Estado real del bloqueo en los equipos: APLICADO, PENDIENTE o ERROR. */ String estadoAplicacion,String detalleAplicacion,
  /* Programación: SIEMPRE, FECHA o SEMANAL. Estado horario según la zona de la familia: FINALIZADA, PROGRAMADA,
     EN_HORARIO o FUERA_DE_HORARIO. Estado efectivo combinado con la confirmación de Windows: DESACTIVADA,
     FINALIZADA, PROGRAMADA, FUERA_DE_HORARIO, BLOQUEANDO_AHORA, PENDIENTE o ERROR. */
  String programacion,String estadoHorario,String estadoEfectivo,String detalleEstado,boolean debeBloquearAhora,
  /* Inicio real del bloqueo actual según el evento confirmado del equipo; null si el equipo no lo informó. */
  Instant bloqueoDesde,Instant finActual,Instant proximoInicio,Instant ultimaSincronizacion,Instant ultimaConfirmacion,
  Instant creadoEn,String zonaHoraria){}
 public record Category(String clave,String nombre,boolean disponible,long dominios,String explicacion){}
 public record AgentResponse(Instant generadoEn,String zonaHoraria,List<AgentRule> reglas){}
 public record AgentRule(Long id,String dominio,boolean incluirSubdominios,TipoReglaSitio modo,boolean permanente,Instant desde,Instant hasta,long revision,List<HorarioResponse> horarios){}
 public record EventItem(@NotNull UUID eventoId,@NotNull Long reglaId,@NotBlank String dominio,@NotBlank String tipo,@Size(max=500)String detalle,@NotNull Instant ocurridoEn,boolean exitoso,
  /* Revisión de la regla informada por el agente; opcional para agentes anteriores. */ Long revision){}
 public record EventsRequest(@NotNull @Size(max=100)List<@Valid EventItem> eventos){}
 public record EventsResponse(int aceptados,List<UUID> confirmados){}
 public record EventResponse(Long id,String dominio,String dispositivo,String tipo,String detalle,Instant ocurridoEn,boolean exitoso){}
}
