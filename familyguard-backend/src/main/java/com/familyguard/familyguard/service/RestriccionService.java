package com.familyguard.familyguard.service;

import static com.familyguard.familyguard.api.dto.RestriccionDtos.*;
import com.familyguard.familyguard.domain.model.*;
import com.familyguard.familyguard.domain.repository.*;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.exception.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RestriccionService {
    private final LimiteTiempoRepository limites;
    private final HorarioRestriccionRepository horarios;
    private final MenorRepository menores;
    private final AplicacionRepository aplicaciones;
    private final DispositivoRepository dispositivos;
    private final UsoAplicacionRepository usos;
    private final EventoAuditoriaRepository eventos;
    private final FamiliaRepository familias;
    private final UsuarioRepository usuarios;

    public RestriccionService(LimiteTiempoRepository limites, HorarioRestriccionRepository horarios,
            MenorRepository menores, AplicacionRepository aplicaciones, DispositivoRepository dispositivos,
            UsoAplicacionRepository usos, EventoAuditoriaRepository eventos,
            FamiliaRepository familias, UsuarioRepository usuarios) {
        this.limites=limites; this.horarios=horarios; this.menores=menores; this.aplicaciones=aplicaciones;
        this.dispositivos=dispositivos; this.usos=usos; this.eventos=eventos;
        this.familias=familias; this.usuarios=usuarios;
    }

    @Transactional
    public RestriccionResponse guardar(UsuarioPrincipal principal, Long id, RestriccionRequest request) {
        validarFamiliar(principal);
        Menor menor = menores.findByIdAndFamilia_IdAndActivoTrue(request.menorId(), principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Perfil no encontrado"));
        Aplicacion app = aplicaciones.findById(request.aplicacionId())
                .filter(a -> a.getFamilia().getId().equals(principal.familiaId()))
                .orElseThrow(() -> new ResourceNotFoundException("Aplicacion no encontrada"));
        if (app.getDispositivo().getMenor() == null || !app.getDispositivo().getMenor().getId().equals(menor.getId()))
            throw new ConflictException("La aplicacion no pertenece al hijo seleccionado");
        LimiteTiempo limite;
        if (id != null) {
            limite = limites.findByIdAndFamilia_Id(id, principal.familiaId())
                    .orElseThrow(() -> new ResourceNotFoundException("Restriccion no encontrada"));
            if (!limite.getMenor().getId().equals(menor.getId()) || !limite.getAplicacion().getId().equals(app.getId()))
                throw new ConflictException("No se puede cambiar el objetivo de una restriccion");
        } else {
            limite = limites.findByFamilia_IdAndMenor_IdAndAplicacion_IdAndPeriodo(
                    principal.familiaId(), menor.getId(), app.getId(), PeriodoLimite.DIARIO)
                    .orElseGet(() -> new LimiteTiempo(familias.getReferenceById(principal.familiaId()),
                            menor, app, PeriodoLimite.DIARIO, request.minutosDiarios()));
        }
        limite.setMinutosPermitidos(request.minutosDiarios());
        limite.setBloqueoTotal(request.bloqueoTotal());
        limite.setAdvertenciaMinutos(request.advertenciaMinutos());
        // Vigencia por fechas (columnas existentes vigente_desde / vigente_hasta; sin cambios de esquema).
        if (request.vigenteHasta() != null && request.vigenteHasta().isBefore(LocalDate.now(zona(menor.getFamilia()))))
            throw new ConflictException("La fecha de fin de la restricción ya pasó");
        limite.setVigenteDesde(request.vigenteDesde());
        limite.setVigenteHasta(request.vigenteHasta());
        limite.setActivo(true); limite.incrementarRevision(); limites.save(limite);
        actualizarHorarios(limite, request.horarios());
        app.getDispositivo().incrementarRevisionDeseada();
        auditar(principal, app.getDispositivo(), id == null ? "RESTRICCION_CREADA" : "RESTRICCION_MODIFICADA",
                "Restriccion configurada para " + app.getNombre(), ResultadoEvento.EXITO);
        return respuesta(limite);
    }

    @Transactional(readOnly = true)
    public List<RestriccionResponse> listar(UsuarioPrincipal principal, Long menorId, Long dispositivoId) {
        validarFamiliar(principal);
        return limites.findAllByFamilia_Id(principal.familiaId()).stream()
                .filter(l -> l.getPeriodo() == PeriodoLimite.DIARIO)
                .filter(l -> menorId == null || menorId.equals(l.getMenor().getId()))
                .filter(l -> dispositivoId == null || dispositivoId.equals(l.getAplicacion().getDispositivo().getId()))
                .map(this::respuesta).toList();
    }

    @Transactional
    public void desactivar(UsuarioPrincipal principal, Long id) {
        validarFamiliar(principal);
        LimiteTiempo limite = limites.findByIdAndFamilia_Id(id, principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Restriccion no encontrada"));
        Dispositivo dispositivo = limite.getAplicacion().getDispositivo();
        String nombre = limite.getAplicacion().getNombre();
        horarios.deleteAll(horarios.findAllByFamilia_IdAndMenor_IdAndAplicacion_Id(principal.familiaId(),
                limite.getMenor().getId(), limite.getAplicacion().getId()));
        limites.delete(limite);
        dispositivo.incrementarRevisionDeseada();
        auditar(principal, dispositivo, "RESTRICCION_ELIMINADA",
                "Restricción eliminada para " + nombre + "; se conserva la auditoría histórica", ResultadoEvento.EXITO);
    }

    @Transactional
    public RestriccionResponse cambiarEstado(UsuarioPrincipal principal, Long id, boolean activo) {
        validarFamiliar(principal);
        LimiteTiempo limite = limites.findByIdAndFamilia_Id(id, principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Restricción no encontrada"));
        limite.setActivo(activo);
        limite.incrementarRevision();
        limite.getAplicacion().getDispositivo().incrementarRevisionDeseada();
        auditar(principal, limite.getAplicacion().getDispositivo(),
                activo ? "RESTRICCION_REACTIVADA" : "RESTRICCION_DESACTIVADA",
                (activo ? "Restricción reactivada para " : "Restricción desactivada para ")
                        + limite.getAplicacion().getNombre(), ResultadoEvento.EXITO);
        return respuesta(limite);
    }

    @Transactional(readOnly = true)
    public ReglasAgenteResponse reglas(String token) {
        Dispositivo dispositivo = autenticarAgente(token);
        ZoneId zone = zona(dispositivo.getFamilia());
        Instant desde = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant hasta = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
        List<ReglaAgente> reglas = limites.findAllByFamilia_IdAndActivoTrue(dispositivo.getFamilia().getId()).stream()
                .filter(l -> l.getMenor().getId().equals(dispositivo.getMenor().getId()))
                .filter(l -> l.getAplicacion().getDispositivo().getId().equals(dispositivo.getId()))
                .map(l -> regla(l, desde, hasta)).toList();
        return new ReglasAgenteResponse(Instant.now(), zone.getId(), reglas);
    }

    @Transactional
    public EventosRestriccionResponse registrarEventos(String token, EventosRestriccionRequest request) {
        Dispositivo dispositivo = autenticarAgente(token);
        List<UUID> confirmados = new ArrayList<>(); int aceptados = 0;
        for (EventoRestriccionItem item : request.eventos()) {
            String uuid = item.eventoId().toString();
            if (eventos.existsByEventoUuid(uuid)) { confirmados.add(item.eventoId()); continue; }
            LimiteTiempo limite = limites.findByIdAndFamilia_Id(item.restriccionId(), dispositivo.getFamilia().getId())
                    .filter(l -> l.getAplicacion().getDispositivo().getId().equals(dispositivo.getId()))
                    .orElseThrow(() -> new ForbiddenException("Restriccion no autorizada para el dispositivo"));
            EventoAuditoria evento = new EventoAuditoria(dispositivo.getFamilia(), item.tipo(), item.detalle(),
                    item.exitoso() ? ResultadoEvento.EXITO : ResultadoEvento.ERROR);
            evento.setDispositivo(dispositivo); evento.setEntidadId(limite.getId());
            evento.setTipoEntidad("LIMITE_TIEMPO"); evento.setEventoUuid(uuid); evento.setOcurridoEn(item.ocurridoEn());
            eventos.save(evento); confirmados.add(item.eventoId()); aceptados++;
        }
        return new EventosRestriccionResponse(aceptados, List.copyOf(confirmados));
    }

    private void actualizarHorarios(LimiteTiempo limite, List<HorarioRequest> requests) {
        List<HorarioRestriccion> existentes = horarios.findAllByFamilia_IdAndMenor_IdAndAplicacion_Id(
                limite.getFamilia().getId(), limite.getMenor().getId(), limite.getAplicacion().getId());
        existentes.forEach(h -> h.setActivo(false));
        for (HorarioRequest request : requests) {
            HorarioRestriccion horario = existentes.stream().filter(h -> h.getDiaSemana()==request.dia()
                    && h.getHoraInicio().equals(request.inicio()) && h.getHoraFin().equals(request.fin())).findFirst()
                    .orElseGet(() -> new HorarioRestriccion(limite.getFamilia(), limite.getMenor(),
                            limite.getAplicacion(), request.dia(), request.inicio(), request.fin(), request.tipo()));
            horario.setTipoRegla(request.tipo()); horario.setActivo(true); horarios.save(horario);
        }
    }
    private RestriccionResponse respuesta(LimiteTiempo limite) {
        ZoneId zone = zona(limite.getFamilia()); Instant desde=LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant hasta=LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
        long consumido=usos.sumarDuracionPorAplicacionYPeriodo(limite.getFamilia().getId(), limite.getMenor().getId(),
                limite.getAplicacion().getId(), desde, hasta); long max=limite.getMinutosPermitidos()*60L;
        var hs=horarios.findAllByFamilia_IdAndMenor_IdAndAplicacion_IdAndActivoTrueOrderByDiaSemanaAscHoraInicioAsc(
                limite.getFamilia().getId(), limite.getMenor().getId(), limite.getAplicacion().getId()).stream()
                .map(h -> new HorarioResponse(h.getId(),h.getDiaSemana(),h.getHoraInicio(),h.getHoraFin(),
                        h.isCruzaMedianoche(),h.getTipoRegla())).toList();
        Aplicacion a=limite.getAplicacion(); Dispositivo d=a.getDispositivo();
        return new RestriccionResponse(limite.getId(),limite.getMenor().getId(),limite.getMenor().getNombre(),
                a.getId(),a.getNombre(),d.getId(),d.getNombre(),a.getRutaEjecutable(),limite.getMinutosPermitidos(),
                limite.isBloqueoTotal(),limite.getAdvertenciaMinutos(),consumido,Math.max(0,max-consumido),
                limite.isBloqueoTotal()||consumido>=max,limite.isActivo(),limite.getActualizadoEn(),
                d.getRevisionAplicada()>=d.getRevisionDeseada()?"APLICADA":"PENDIENTE",hs,limite.getVigenteDesde(),limite.getVigenteHasta());
    }
    private ReglaAgente regla(LimiteTiempo limite, Instant desde, Instant hasta) {
        long consumido=usos.sumarDuracionPorAplicacionYPeriodo(limite.getFamilia().getId(),limite.getMenor().getId(),
                limite.getAplicacion().getId(),desde,hasta);
        var hs=horarios.findAllByFamilia_IdAndMenor_IdAndAplicacion_IdAndActivoTrueOrderByDiaSemanaAscHoraInicioAsc(
                limite.getFamilia().getId(),limite.getMenor().getId(),limite.getAplicacion().getId()).stream()
                .map(h -> new HorarioAgente(h.getDiaSemana(),h.getHoraInicio(),h.getHoraFin(),h.isCruzaMedianoche(),h.getTipoRegla())).toList();
        Aplicacion a=limite.getAplicacion(); return new ReglaAgente(limite.getId(),a.getId(),a.getNombre(),
                a.getRutaEjecutable(),limite.getMinutosPermitidos()*60,limite.getAdvertenciaMinutos()*60,
                limite.isBloqueoTotal(),consumido,limite.getRevision(),hs,limite.getVigenteDesde(),limite.getVigenteHasta());
    }
    private Dispositivo autenticarAgente(String token) {
        if(token==null||token.isBlank()) throw new UnauthorizedException("Agente no autorizado");
        Dispositivo d=dispositivos.findByAgenteTokenHashAndAgenteRevocadoFalseAndActivoTrue(AutenticacionService.hashToken(token))
                .filter(x -> x.getEstado()==EstadoDispositivo.AUTORIZADO).orElseThrow(() -> new UnauthorizedException("Agente no autorizado"));
        if(d.getMenor()==null) throw new ConflictException("El dispositivo no tiene perfil supervisado"); return d;
    }
    private void auditar(UsuarioPrincipal p, Dispositivo d, String tipo, String detalle, ResultadoEvento resultado) {
        EventoAuditoria e=new EventoAuditoria(d.getFamilia(),tipo,detalle,resultado); e.setDispositivo(d);
        e.setUsuario(usuarios.getReferenceById(p.usuarioId())); eventos.save(e);
    }
    private static ZoneId zona(Familia f){try{return ZoneId.of(f.getZonaHoraria());}catch(Exception e){return ZoneId.of("UTC");}}
    private static void validarFamiliar(UsuarioPrincipal p){if(p.rol()!=RolUsuario.PADRE&&p.rol()!=RolUsuario.TUTOR)throw new ForbiddenException("Rol no autorizado");}
}
