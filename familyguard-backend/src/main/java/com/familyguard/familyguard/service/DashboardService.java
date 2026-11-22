package com.familyguard.familyguard.service;

import com.familyguard.familyguard.api.dto.AlertaResponse;
import com.familyguard.familyguard.api.dto.AplicacionResponse;
import com.familyguard.familyguard.api.dto.DashboardResponse;
import com.familyguard.familyguard.api.dto.DispositivoResponse;
import com.familyguard.familyguard.api.dto.ReporteResponse;
import com.familyguard.familyguard.domain.model.Aplicacion;
import com.familyguard.familyguard.domain.model.Dispositivo;
import com.familyguard.familyguard.domain.model.RolUsuario;
import com.familyguard.familyguard.domain.model.UsoAplicacion;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.EventoAuditoriaRepository;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.HorarioRestriccionRepository;
import com.familyguard.familyguard.domain.repository.LimiteTiempoRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.SitioBloqueadoRepository;
import com.familyguard.familyguard.domain.repository.UsoAplicacionRepository;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.exception.ForbiddenException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    private static final int MAX_DIAS_REPORTE = 366;

    private final FamiliaRepository familias;
    private final MenorRepository menores;
    private final DispositivoRepository dispositivos;
    private final AplicacionRepository aplicaciones;
    private final UsoAplicacionRepository usos;
    private final LimiteTiempoRepository limites;
    private final SitioBloqueadoRepository sitios;
    private final HorarioRestriccionRepository horarios;
    private final EventoAuditoriaRepository eventos;

    public DashboardService(FamiliaRepository familias, MenorRepository menores,
                            DispositivoRepository dispositivos, AplicacionRepository aplicaciones,
                            UsoAplicacionRepository usos, LimiteTiempoRepository limites,
                            SitioBloqueadoRepository sitios, HorarioRestriccionRepository horarios,
                            EventoAuditoriaRepository eventos) {
        this.familias = familias;
        this.menores = menores;
        this.dispositivos = dispositivos;
        this.aplicaciones = aplicaciones;
        this.usos = usos;
        this.limites = limites;
        this.sitios = sitios;
        this.horarios = horarios;
        this.eventos = eventos;
    }

    @Transactional(readOnly = true)
    public DashboardResponse resumen(UsuarioPrincipal principal) {
        validarRol(principal);
        ZoneId zone = zona(principal.familiaId());
        Instant inicio = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant fin = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
        long restricciones = limites.countByFamilia_IdAndActivoTrue(principal.familiaId())
                + sitios.countByFamilia_IdAndActivoTrue(principal.familiaId())
                + horarios.countByFamilia_IdAndActivoTrue(principal.familiaId());
        return new DashboardResponse(
                menores.countByFamilia_IdAndActivoTrue(principal.familiaId()),
                dispositivos.countByFamilia_IdAndActivoTrue(principal.familiaId()),
                aplicaciones.countByFamilia_IdAndActivoTrue(principal.familiaId()),
                usos.sumarDuracionPorFamiliaYPeriodo(principal.familiaId(), inicio, fin),
                restricciones,
                eventos.countByFamilia_IdAndActivoTrue(principal.familiaId()));
    }

    @Transactional(readOnly = true)
    public List<DispositivoResponse> dispositivos(UsuarioPrincipal principal, Long menorId) {
        validarRol(principal);
        return dispositivos.findAllByFamilia_IdAndActivoTrue(principal.familiaId()).stream()
                .filter(d -> menorId == null || (d.getMenor() != null && menorId.equals(d.getMenor().getId())))
                .sorted(Comparator.comparing(Dispositivo::getNombre, String.CASE_INSENSITIVE_ORDER))
                .map(DashboardService::dispositivo).toList();
    }

    @Transactional(readOnly = true)
    public List<AplicacionResponse> aplicaciones(UsuarioPrincipal principal, Long menorId,
                                                 Long dispositivoId, String nombre) {
        validarRol(principal);
        Map<Long, UsoResumen> resumenes = new HashMap<>();
        for (Object[] fila : usos.resumirUsoPorAplicacion(principal.familiaId())) {
            resumenes.put(((Number) fila[0]).longValue(),
                    new UsoResumen(((Number) fila[1]).longValue(), (Instant) fila[2]));
        }
        String filtro = nombre == null ? "" : nombre.strip().toLowerCase(Locale.ROOT);
        return aplicaciones.findAllByFamilia_IdAndActivoTrue(principal.familiaId()).stream()
                .filter(a -> dispositivoId == null || dispositivoId.equals(a.getDispositivo().getId()))
                .filter(a -> menorId == null || (a.getDispositivo().getMenor() != null
                        && menorId.equals(a.getDispositivo().getMenor().getId())))
                .filter(a -> filtro.isEmpty() || a.getNombre().toLowerCase(Locale.ROOT).contains(filtro)
                        || (a.getRutaEjecutable() != null && a.getRutaEjecutable().toLowerCase(Locale.ROOT).contains(filtro)))
                .sorted(Comparator.comparing(Aplicacion::getNombre, String.CASE_INSENSITIVE_ORDER))
                .map(a -> aplicacion(a, resumenes.getOrDefault(a.getId(), new UsoResumen(0, null))))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReporteResponse reporte(UsuarioPrincipal principal, LocalDate desde, LocalDate hasta,
                                   Long menorId, Long dispositivoId, Long aplicacionId) {
        validarRol(principal);
        ZoneId zone = zona(principal.familiaId());
        LocalDate finFecha = hasta == null ? LocalDate.now(zone) : hasta;
        LocalDate inicioFecha = desde == null ? finFecha.minusDays(29) : desde;
        if (inicioFecha.isAfter(finFecha)) {
            throw new IllegalArgumentException("La fecha inicial no puede ser posterior a la final");
        }
        if (inicioFecha.plusDays(MAX_DIAS_REPORTE).isBefore(finFecha)) {
            throw new IllegalArgumentException("El rango del reporte no puede superar 366 dias");
        }
        Instant inicio = inicioFecha.atStartOfDay(zone).toInstant();
        Instant fin = finFecha.plusDays(1).atStartOfDay(zone).toInstant();
        List<UsoAplicacion> registros = usos
                .findAllByFamilia_IdAndInicioEnGreaterThanEqualAndInicioEnLessThanAndActivoTrue(
                        principal.familiaId(), inicio, fin).stream()
                .filter(u -> menorId == null || menorId.equals(u.getMenor().getId()))
                .filter(u -> dispositivoId == null || dispositivoId.equals(u.getDispositivo().getId()))
                .filter(u -> aplicacionId == null || aplicacionId.equals(u.getAplicacion().getId()))
                .toList();

        Map<LocalDate, Long> diario = new LinkedHashMap<>();
        for (LocalDate fecha = inicioFecha; !fecha.isAfter(finFecha); fecha = fecha.plusDays(1)) diario.put(fecha, 0L);
        Map<Long, AcumuladoAplicacion> ranking = new HashMap<>();
        Map<String, AcumuladoResumen> resumen = new HashMap<>();
        long total = 0;
        for (UsoAplicacion uso : registros) {
            long segundos = uso.getDuracionSegundos();
            total += segundos;
            diario.merge(uso.getInicioEn().atZone(zone).toLocalDate(), segundos, Long::sum);
            ranking.computeIfAbsent(uso.getAplicacion().getId(), ignored ->
                    new AcumuladoAplicacion(uso.getAplicacion().getId(), uso.getAplicacion().getNombre()))
                    .segundos += segundos;
            String clave = uso.getMenor().getId() + ":" + uso.getDispositivo().getId();
            resumen.computeIfAbsent(clave, ignored -> new AcumuladoResumen(uso)).segundos += segundos;
        }
        List<ReporteResponse.PuntoUso> puntos = diario.entrySet().stream()
                .map(e -> new ReporteResponse.PuntoUso(e.getKey(), e.getValue())).toList();
        List<ReporteResponse.RankingAplicacion> rankingRespuesta = ranking.values().stream()
                .sorted(Comparator.comparingLong((AcumuladoAplicacion a) -> a.segundos).reversed())
                .limit(10).map(a -> new ReporteResponse.RankingAplicacion(a.id, a.nombre, a.segundos)).toList();
        List<ReporteResponse.ResumenUso> resumenRespuesta = resumen.values().stream()
                .sorted(Comparator.comparingLong((AcumuladoResumen a) -> a.segundos).reversed())
                .map(a -> new ReporteResponse.ResumenUso(a.menorId, a.menorNombre,
                        a.dispositivoId, a.dispositivoNombre, a.segundos)).toList();
        return new ReporteResponse(inicioFecha, finFecha, total, puntos, rankingRespuesta, resumenRespuesta);
    }

    @Transactional(readOnly = true)
    public List<AlertaResponse> alertas(UsuarioPrincipal principal) {
        validarRol(principal);
        return eventos.findAllByFamilia_IdOrderByOcurridoEnDesc(principal.familiaId(), PageRequest.of(0, 100))
                .stream().map(e -> new AlertaResponse(e.getId(), e.getTipoEvento(), e.getDescripcion(),
                        e.getResultado().name(), e.getDispositivo() == null ? null : e.getDispositivo().getId(),
                        e.getDispositivo() == null ? null : e.getDispositivo().getNombre(), e.getOcurridoEn()))
                .toList();
    }

    private ZoneId zona(Long familiaId) {
        String nombre = familias.findById(familiaId).orElseThrow().getZonaHoraria();
        try { return ZoneId.of(nombre); } catch (Exception ignored) { return ZoneId.of("UTC"); }
    }

    private static void validarRol(UsuarioPrincipal principal) {
        if (principal.rol() != RolUsuario.PADRE && principal.rol() != RolUsuario.TUTOR) {
            throw new ForbiddenException("El rol no puede consultar informacion familiar");
        }
    }

    /** Conectado solo con heartbeat real y reciente (máx(45 s, 3 × intervalo)) y credencial vigente. */
    public static boolean estaConectado(Dispositivo d) {
        return d.getUltimoHeartbeat() != null
                && d.getUltimoHeartbeat().isAfter(Instant.now().minusSeconds(Math.max(45, d.getIntervaloHeartbeatSegundos() * 3L)))
                && !d.isAgenteRevocado();
    }

    private static DispositivoResponse dispositivo(Dispositivo d) {
        return new DispositivoResponse(d.getId(), d.getNombre(), d.getTipo().name(), d.getSistemaOperativo(),
                d.getVersionSistema(), d.getMenor() == null ? null : d.getMenor().getId(),
                d.getMenor() == null ? null : d.getMenor().getNombre(), d.getEstado().name(),
                d.getCreadoEn(), d.getUltimaConexion(), d.getUltimaSincronizacion(),
                estaConectado(d),d.getUsuarioLocal(),d.getVersionAgente(),d.getUltimoHeartbeat(),d.getUltimoErrorComunicacion(),d.getRevisionDeseada(),d.getRevisionAplicada(),d.getRevisionAplicada()>=d.getRevisionDeseada()?"APLICADA":"PENDIENTE");
    }

    private static AplicacionResponse aplicacion(Aplicacion a, UsoResumen uso) {
        Dispositivo d = a.getDispositivo();
        return new AplicacionResponse(a.getId(), a.getNombre(), d.getId(), d.getNombre(),
                d.getMenor() == null ? null : d.getMenor().getId(),
                d.getMenor() == null ? null : d.getMenor().getNombre(), a.getVersion(), a.getEditor(),
                  a.getTipoInstalacion(), a.getRutaEjecutable(), a.isEnEjecucion(), a.isVentanaActiva(), a.getUltimoPid(),
                a.getUltimaDeteccion(),
                uso.segundos, uso.ultimoUso);
    }

    private record UsoResumen(long segundos, Instant ultimoUso) { }
    private static final class AcumuladoAplicacion {
        private final Long id; private final String nombre; private long segundos;
        private AcumuladoAplicacion(Long id, String nombre) { this.id = id; this.nombre = nombre; }
    }
    private static final class AcumuladoResumen {
        private final Long menorId; private final String menorNombre;
        private final Long dispositivoId; private final String dispositivoNombre; private long segundos;
        private AcumuladoResumen(UsoAplicacion uso) {
            menorId = uso.getMenor().getId(); menorNombre = uso.getMenor().getNombre();
            dispositivoId = uso.getDispositivo().getId(); dispositivoNombre = uso.getDispositivo().getNombre();
        }
    }
}
