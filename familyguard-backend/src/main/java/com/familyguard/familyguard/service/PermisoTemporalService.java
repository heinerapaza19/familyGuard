package com.familyguard.familyguard.service;

import static com.familyguard.familyguard.api.dto.PermisoTemporalDtos.*;
import static com.familyguard.familyguard.domain.model.PermisoTemporal.*;

import com.familyguard.familyguard.domain.model.Aplicacion;
import com.familyguard.familyguard.domain.model.Dispositivo;
import com.familyguard.familyguard.domain.model.EstadoDispositivo;
import com.familyguard.familyguard.domain.model.EventoAuditoria;
import com.familyguard.familyguard.domain.model.Familia;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.model.PermisoTemporal;
import com.familyguard.familyguard.domain.model.PermisoTemporalDispositivo;
import com.familyguard.familyguard.domain.model.ResultadoEvento;
import com.familyguard.familyguard.domain.model.RolUsuario;
import com.familyguard.familyguard.domain.model.SitioBloqueado;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.EventoAuditoriaRepository;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.PermisoTemporalDispositivoRepository;
import com.familyguard.familyguard.domain.repository.PermisoTemporalRepository;
import com.familyguard.familyguard.domain.repository.SitioBloqueadoRepository;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.exception.ConflictException;
import com.familyguard.familyguard.service.exception.ForbiddenException;
import com.familyguard.familyguard.service.exception.ResourceNotFoundException;
import com.familyguard.familyguard.service.exception.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permisos temporales (entrega final).
 *
 * <ul>
 *   <li>La caducidad es una hora absoluta del servidor: «Tiempo extra» vence al terminar el día de la familia; el
 *   resto, a la hora autorizada. El Agente solo recibe permisos vigentes y además los vence por su cuenta.</li>
 *   <li>Todo se valida contra la familia de la sesión; la base de datos lo garantiza también con claves
 *   compuestas (V11). Un reintento con el mismo identificador devuelve lo ya registrado; con otro contenido, 409.</li>
 *   <li>El estado de cada equipo procede de lo que confirma el Agente; nunca se da por aplicado de antemano.</li>
 * </ul>
 */
@Service
public class PermisoTemporalService {
    public static final int MAX_MINUTOS_EXTRA_DIA = 240, MIN_DURACION = 5, MAX_DURACION = 720;
    static final Set<String> TIPOS = Set.of(TIEMPO_EXTRA, FUERA_HORARIO, DESBLOQUEO_TEMPORAL, PERMITIR_SITIO);
    // Estados mostrados al padre por equipo.
    public static final String ACTIVO = "ACTIVO", PENDIENTE = "PENDIENTE", DESCONECTADO = "DESCONECTADO",
            ERROR = "ERROR", EXPIRADO = "EXPIRADO", REVOCADO = "REVOCADO";

    private final FamiliaRepository familias;
    private final MenorRepository menores;
    private final DispositivoRepository dispositivos;
    private final AplicacionRepository aplicaciones;
    private final SitioBloqueadoRepository sitios;
    private final PermisoTemporalRepository permisos;
    private final PermisoTemporalDispositivoRepository filas;
    private final EventoAuditoriaRepository eventos;
    private final AuditoriaAdministrativa auditoria;
    private Clock clock = Clock.systemUTC();

    public PermisoTemporalService(FamiliaRepository familias, MenorRepository menores, DispositivoRepository dispositivos,
                                  AplicacionRepository aplicaciones, SitioBloqueadoRepository sitios,
                                  PermisoTemporalRepository permisos, PermisoTemporalDispositivoRepository filas,
                                  EventoAuditoriaRepository eventos, AuditoriaAdministrativa auditoria) {
        this.familias = familias; this.menores = menores; this.dispositivos = dispositivos;
        this.aplicaciones = aplicaciones; this.sitios = sitios; this.permisos = permisos; this.filas = filas;
        this.eventos = eventos; this.auditoria = auditoria;
    }

    /** Solo para pruebas: permite simular el paso del tiempo. */
    @Autowired(required = false)
    void setClock(Clock clock) { if (clock != null) this.clock = clock; }

    public record Ejecucion(PermisoResponse permiso, boolean nuevo) { }
    private record Destino(Dispositivo dispositivo, Aplicacion aplicacion, SitioBloqueado sitio) { }

    // ------------------------------------------------------------------------------------------ Padre
    @Transactional
    public Ejecucion otorgar(UsuarioPrincipal principal, Request r) {
        validarRol(principal);
        Familia familia = familias.bloquearParaOperacion(principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Familia no encontrada"));
        String tipo = tipo(r.tipo());
        String huella = huella(tipo, r);
        var previa = permisos.findByFamilia_IdAndConcesionUuid(familia.getId(), r.concesionId().toString());
        if (previa.isPresent()) {
            if (!previa.get().getHuellaSolicitud().equals(huella))
                throw new ConflictException("El identificador del permiso ya se usó con otra solicitud");
            return new Ejecucion(respuesta(previa.get(), List.of(), clock.instant()), false);
        }
        Menor menor = menores.findByIdAndFamilia_IdAndActivoTrue(r.menorId(), familia.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Hijo no encontrado"));
        List<Dispositivo> equipos = new ArrayList<>();
        for (Long id : r.dispositivoIds().stream().distinct().toList()) {
            Dispositivo d = dispositivos.findByFamilia_IdAndId(familia.getId(), id)
                    .orElseThrow(() -> new ResourceNotFoundException("Equipo no encontrado"));
            if (!d.isActivo() || d.isAgenteRevocado() || d.getEstado() != EstadoDispositivo.AUTORIZADO)
                throw new IllegalArgumentException("El equipo «" + d.getNombre() + "» no está vinculado");
            if (d.getMenor() == null || !d.getMenor().getId().equals(menor.getId()))
                throw new IllegalArgumentException("El equipo «" + d.getNombre() + "» no pertenece a " + menor.getNombre());
            equipos.add(d);
        }
        Instant ahora = clock.instant();
        Integer minutos = null;
        Instant expira;
        if (TIEMPO_EXTRA.equals(tipo)) {
            minutos = r.minutosExtra();
            if (minutos == null || minutos < 1 || minutos > MAX_MINUTOS_EXTRA_DIA)
                throw new IllegalArgumentException("El tiempo extra debe estar entre 1 y " + MAX_MINUTOS_EXTRA_DIA + " minutos");
            expira = finDelDia(familia, ahora);
        } else {
            Integer duracion = r.duracionMinutos();
            if (duracion == null || duracion < MIN_DURACION || duracion > MAX_DURACION)
                throw new IllegalArgumentException("La duración debe estar entre " + MIN_DURACION + " minutos y 12 horas");
            expira = ahora.plusSeconds(duracion * 60L);
        }
        List<Destino> destinos = new ArrayList<>();
        List<String> omitidos = new ArrayList<>();
        String objetivo;
        if (PERMITIR_SITIO.equals(tipo)) {
            if (r.sitioId() == null) throw new IllegalArgumentException("Elige el sitio web que deseas permitir");
            SitioBloqueado sitio = sitios.findByIdAndFamilia_Id(r.sitioId(), familia.getId())
                    .filter(s -> s.getMenor() != null && s.getMenor().getId().equals(menor.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Regla web no encontrada"));
            objetivo = sitio.getDominio();
            equipos.forEach(d -> destinos.add(new Destino(d, null, sitio)));
        } else {
            if (r.aplicacionId() == null) throw new IllegalArgumentException("Elige la aplicación");
            Aplicacion referencia = aplicaciones.findById(r.aplicacionId())
                    .filter(a -> a.getFamilia().getId().equals(familia.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Aplicación no encontrada"));
            String ejecutable = OperacionMasivaService.ejecutable(referencia.getRutaEjecutable());
            objetivo = referencia.getNombre();
            for (Dispositivo d : equipos) {
                if (referencia.getDispositivo().getId().equals(d.getId())) { destinos.add(new Destino(d, referencia, null)); continue; }
                List<Aplicacion> iguales = aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(familia.getId(), d.getId())
                        .stream().filter(a -> ejecutable.equals(OperacionMasivaService.ejecutable(a.getRutaEjecutable()))).toList();
                if (iguales.size() == 1) destinos.add(new Destino(d, iguales.get(0), null));
                else omitidos.add(d.getNombre() + ": " + (iguales.isEmpty() ? "la aplicación no está registrada"
                        : "hay varias aplicaciones con el mismo ejecutable"));
            }
            if (destinos.isEmpty())
                throw new IllegalArgumentException("La aplicación no está registrada en ninguno de los equipos elegidos");
        }
        if (TIEMPO_EXTRA.equals(tipo)) for (Destino destino : destinos) {
            int otorgados = filas.findAllByFamiliaIdAndDispositivo_IdAndRevocadoEnIsNull(familia.getId(), destino.dispositivo().getId())
                    .stream().filter(f -> TIEMPO_EXTRA.equals(f.getPermiso().getTipo()) && f.getPermiso().getExpiraEn().isAfter(ahora))
                    .filter(f -> f.getAplicacion() != null && f.getAplicacion().getId().equals(destino.aplicacion().getId()))
                    .mapToInt(f -> f.getPermiso().getMinutosExtra()).sum();
            if (otorgados + minutos > MAX_MINUTOS_EXTRA_DIA)
                throw new IllegalArgumentException("Con este permiso, el tiempo extra de hoy en «" + destino.dispositivo().getNombre()
                        + "» superaría 4 horas (ya hay " + otorgados + " min)");
        }
        PermisoTemporal permiso = permisos.save(new PermisoTemporal(familia, menor, r.concesionId().toString(), huella, tipo,
                minutos, ahora, expira, principal.usuarioId(), limpiar(r.motivo())));
        List<PermisoTemporalDispositivo> nuevas = new ArrayList<>();
        for (Destino destino : destinos) {
            nuevas.add(new PermisoTemporalDispositivo(permiso, destino.dispositivo(), destino.aplicacion(), destino.sitio()));
            destino.dispositivo().incrementarRevisionDeseada();
        }
        filas.saveAll(nuevas);
        filas.flush();
        auditoria.registrar(principal, "PERMISO_TEMPORAL_OTORGADO", "PERMISO_TEMPORAL", permiso.getId(), null,
                descripcion(tipo, objetivo, minutos) + " para " + menor.getNombre() + " en " + destinos.size()
                        + (destinos.size() == 1 ? " equipo" : " equipos") + " hasta " + expira);
        return new Ejecucion(respuesta(permiso, nuevas, omitidos, ahora), true);
    }

    /** Cancela el permiso en todos sus equipos o solo en uno. Repetirlo no cambia nada. */
    @Transactional
    public PermisoResponse revocar(UsuarioPrincipal principal, Long id, Long dispositivoId) {
        validarRol(principal);
        familias.bloquearParaOperacion(principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Familia no encontrada"));
        PermisoTemporal permiso = permisos.findById(id).filter(p -> p.getFamilia().getId().equals(principal.familiaId()))
                .orElseThrow(() -> new ResourceNotFoundException("Permiso no encontrado"));
        Instant ahora = clock.instant();
        List<PermisoTemporalDispositivo> todas = filas.findAllByPermiso_IdOrderByIdAsc(permiso.getId());
        if (dispositivoId != null && todas.stream().noneMatch(f -> f.getDispositivo().getId().equals(dispositivoId)))
            throw new ResourceNotFoundException("El permiso no incluye ese equipo");
        int revocadas = 0;
        for (PermisoTemporalDispositivo fila : todas) {
            if (dispositivoId != null && !fila.getDispositivo().getId().equals(dispositivoId)) continue;
            if (fila.getRevocadoEn() != null || !permiso.getExpiraEn().isAfter(ahora)) continue;
            fila.revocar(principal.usuarioId(), ahora);
            fila.getDispositivo().incrementarRevisionDeseada();
            revocadas++;
        }
        if (revocadas > 0) {
            filas.flush();
            auditoria.registrar(principal, "PERMISO_TEMPORAL_REVOCADO", "PERMISO_TEMPORAL", permiso.getId(), dispositivoId,
                    "Permiso cancelado en " + revocadas + (revocadas == 1 ? " equipo" : " equipos"));
        }
        return respuesta(permiso, todas, List.of(), ahora);
    }

    @Transactional(readOnly = true)
    public List<PermisoResponse> listar(UsuarioPrincipal principal, boolean soloVigentes) {
        validarRol(principal);
        Instant ahora = clock.instant();
        Map<Long, PermisoTemporal> vistos = new LinkedHashMap<>();
        for (PermisoTemporalDispositivo fila : filas.findTop200ByFamiliaIdOrderByIdDesc(principal.familiaId()))
            vistos.putIfAbsent(fila.getPermiso().getId(), fila.getPermiso());
        return vistos.values().stream()
                .map(p -> respuesta(p, filas.findAllByPermiso_IdOrderByIdAsc(p.getId()), List.of(), ahora))
                .filter(p -> !soloVigentes || ACTIVO.equals(p.estado()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PermisoResponse obtener(UsuarioPrincipal principal, Long id) {
        validarRol(principal);
        PermisoTemporal permiso = permisos.findById(id).filter(p -> p.getFamilia().getId().equals(principal.familiaId()))
                .orElseThrow(() -> new ResourceNotFoundException("Permiso no encontrado"));
        return respuesta(permiso, filas.findAllByPermiso_IdOrderByIdAsc(id), List.of(), clock.instant());
    }

    // ----------------------------------------------------------------------------------------- Agente
    /** Solo permisos vigentes y no cancelados de este equipo, con la hora del servidor como referencia. */
    @Transactional
    public PermisosAgenteResponse permisosAgente(String token) {
        Dispositivo d = autenticarAgente(token);
        Instant ahora = clock.instant();
        List<PermisoAgente> vigentes = filas.findAllByFamiliaIdAndDispositivo_IdAndRevocadoEnIsNull(d.getFamilia().getId(), d.getId())
                .stream().filter(f -> f.getPermiso().getExpiraEn().isAfter(ahora))
                .map(f -> new PermisoAgente(f.getId(), f.getPermiso().getId(), f.getPermiso().getTipo(),
                        f.getAplicacion() == null ? null : f.getAplicacion().getId(),
                        f.getAplicacion() == null ? null : f.getAplicacion().getRutaEjecutable(),
                        f.getSitio() == null ? null : f.getSitio().getId(),
                        f.getSitio() == null ? null : f.getSitio().getDominio(),
                        f.getPermiso().getMinutosExtra(), f.getPermiso().getInicioEn(), f.getPermiso().getExpiraEn()))
                .toList();
        return new PermisosAgenteResponse(ahora, vigentes);
    }

    /** Estado confirmado por el Agente (aplicado, finalizado o error). Las filas de otros equipos se ignoran. */
    @Transactional
    public EventosPermisoResponse eventosAgente(String token, EventosPermisoRequest request) {
        Dispositivo d = autenticarAgente(token);
        Instant ahora = clock.instant();
        int aceptados = 0;
        for (EventoPermiso evento : request.eventos()) {
            String estado = evento.estado().strip().toUpperCase(java.util.Locale.ROOT);
            if (!Set.of(PermisoTemporalDispositivo.APLICADO, PermisoTemporalDispositivo.FINALIZADO,
                    PermisoTemporalDispositivo.ERROR).contains(estado))
                throw new IllegalArgumentException("Estado de permiso no válido");
            var fila = filas.findByIdAndFamiliaId(evento.filaId(), d.getFamilia().getId())
                    .filter(f -> f.getDispositivo().getId().equals(d.getId()));
            if (fila.isEmpty()) continue;
            Instant cuando = evento.ocurridoEn().isAfter(ahora.plusSeconds(300)) ? ahora : evento.ocurridoEn();
            String anterior = fila.get().getEstadoAgente();
            fila.get().informarAgente(estado, limpiar(evento.detalle()), cuando);
            if (!anterior.equals(fila.get().getEstadoAgente()) && !PermisoTemporalDispositivo.APLICADO.equals(estado)) {
                EventoAuditoria auditado = new EventoAuditoria(d.getFamilia(), "PERMISO_TEMPORAL_" + estado,
                        (PermisoTemporalDispositivo.ERROR.equals(estado) ? "No se pudo aplicar el permiso temporal en "
                                : "Terminó el permiso temporal en ") + d.getNombre(),
                        PermisoTemporalDispositivo.ERROR.equals(estado) ? ResultadoEvento.ERROR : ResultadoEvento.EXITO);
                auditado.setDispositivo(d); auditado.setTipoEntidad("PERMISO_TEMPORAL");
                auditado.setEntidadId(fila.get().getPermiso().getId()); auditado.setOcurridoEn(cuando);
                eventos.save(auditado);
            }
            aceptados++;
        }
        return new EventosPermisoResponse(aceptados);
    }

    // ---------------------------------------------------------------------------------------- Interno
    PermisoResponse respuesta(PermisoTemporal p, List<PermisoTemporalDispositivo> filasPermiso, Instant ahora) {
        return respuesta(p, filasPermiso.isEmpty() ? filas.findAllByPermiso_IdOrderByIdAsc(p.getId()) : filasPermiso, List.of(), ahora);
    }

    private PermisoResponse respuesta(PermisoTemporal p, List<PermisoTemporalDispositivo> filasPermiso, List<String> omitidos, Instant ahora) {
        List<PermisoTemporalDispositivo> lista = filasPermiso.isEmpty() ? filas.findAllByPermiso_IdOrderByIdAsc(p.getId()) : filasPermiso;
        List<EquipoEstado> equipos = lista.stream().map(f -> {
            boolean conectado = DashboardService.estaConectado(f.getDispositivo());
            String[] estado = estadoEquipo(p, f, conectado, ahora);
            return new EquipoEstado(f.getId(), f.getDispositivo().getId(), f.getDispositivo().getNombre(), conectado,
                    estado[0], estado[1], f.getAgenteActualizadoEn());
        }).toList();
        String objetivo = lista.isEmpty() ? "" : lista.get(0).getSitio() != null ? lista.get(0).getSitio().getDominio()
                : lista.get(0).getAplicacion().getNombre();
        String estado = estadoGeneral(equipos.stream().map(EquipoEstado::estado).toList());
        return new PermisoResponse(p.getId(), UUID.fromString(p.getConcesionUuid()), p.getMenor().getId(), p.getMenor().getNombre(),
                p.getTipo(), objetivo, p.getMinutosExtra(), p.getInicioEn(), p.getExpiraEn(), p.getMotivo(), estado,
                descripcion(p.getTipo(), objetivo, p.getMinutosExtra()), equipos, List.copyOf(omitidos));
    }

    /** Estado real de un equipo: cancelado y vencido tienen prioridad sobre lo que informó el Agente. */
    static String[] estadoEquipo(PermisoTemporal p, PermisoTemporalDispositivo f, boolean conectado, Instant ahora) {
        if (f.getRevocadoEn() != null) return new String[] {REVOCADO, "Cancelado por el padre"};
        if (!p.getExpiraEn().isAfter(ahora)) return new String[] {EXPIRADO,
                PermisoTemporalDispositivo.FINALIZADO.equals(f.getEstadoAgente()) ? "Terminó en el equipo" : "Venció"};
        return switch (f.getEstadoAgente()) {
            case PermisoTemporalDispositivo.APLICADO -> new String[] {ACTIVO, "Aplicado en el equipo"};
            case PermisoTemporalDispositivo.ERROR -> new String[] {ERROR,
                    f.getDetalleAgente() == null ? "El equipo no pudo aplicarlo" : f.getDetalleAgente()};
            case PermisoTemporalDispositivo.FINALIZADO -> new String[] {EXPIRADO, "Terminó en el equipo"};
            default -> conectado ? new String[] {PENDIENTE, "Enviando al equipo"}
                    : new String[] {DESCONECTADO, "Se aplicará cuando el equipo se conecte; vence igual a la hora indicada"};
        };
    }

    static String estadoGeneral(List<String> estados) {
        if (estados.isEmpty()) return EXPIRADO;
        if (estados.stream().allMatch(REVOCADO::equals)) return REVOCADO;
        if (estados.stream().allMatch(e -> REVOCADO.equals(e) || EXPIRADO.equals(e))) return EXPIRADO;
        return ACTIVO;
    }

    static String descripcion(String tipo, String objetivo, Integer minutos) {
        return switch (tipo) {
            case TIEMPO_EXTRA -> "+" + minutos + " min de " + objetivo + " (hoy)";
            case FUERA_HORARIO -> "Usar " + objetivo + " fuera de horario";
            case DESBLOQUEO_TEMPORAL -> "Desbloqueo temporal de " + objetivo;
            default -> "Permitir " + objetivo;
        };
    }

    /** Medianoche siguiente en la zona horaria de la familia. */
    static Instant finDelDia(Familia familia, Instant ahora) {
        ZoneId zona;
        try { zona = ZoneId.of(familia.getZonaHoraria()); } catch (Exception e) { zona = ZoneId.of("UTC"); }
        return LocalDate.ofInstant(ahora, zona).plusDays(1).atStartOfDay(zona).toInstant();
    }

    static String tipo(String valor) {
        String tipo = valor == null ? "" : valor.strip().toUpperCase(java.util.Locale.ROOT);
        if (!TIPOS.contains(tipo)) throw new IllegalArgumentException("Tipo de permiso no válido");
        return tipo;
    }

    static String huella(String tipo, Request r) {
        List<Long> ids = r.dispositivoIds().stream().filter(Objects::nonNull).distinct().sorted().toList();
        String texto = tipo + "|" + r.menorId() + "|" + ids + "|" + r.aplicacionId() + "|" + r.sitioId() + "|"
                + r.minutosExtra() + "|" + r.duracionMinutos() + "|" + Objects.toString(limpiar(r.motivo()), "");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String limpiar(String texto) {
        if (texto == null || texto.isBlank()) return null;
        String t = texto.strip();
        return t.length() <= 200 ? t : t.substring(0, 200);
    }

    private Dispositivo autenticarAgente(String token) {
        if (token == null || token.isBlank()) throw new UnauthorizedException("Agente no autorizado");
        return dispositivos.findByAgenteTokenHashAndAgenteRevocadoFalseAndActivoTrue(AutenticacionService.hashToken(token))
                .filter(x -> x.getEstado() == EstadoDispositivo.AUTORIZADO)
                .orElseThrow(() -> new UnauthorizedException("Agente no autorizado"));
    }

    private static void validarRol(UsuarioPrincipal principal) {
        if (principal.rol() != RolUsuario.PADRE && principal.rol() != RolUsuario.TUTOR)
            throw new ForbiddenException("Rol no autorizado");
    }
}
