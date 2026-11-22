package com.familyguard.familyguard.service;

import static com.familyguard.familyguard.api.dto.SolicitudDesbloqueoDtos.*;

import com.familyguard.familyguard.api.dto.PermisoTemporalDtos;
import com.familyguard.familyguard.domain.model.*;
import com.familyguard.familyguard.domain.repository.*;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.exception.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * «Justificar»: el hijo pide desde el Agente que se desbloquee una aplicación o un sitio, con un mensaje y una foto
 * opcional. El padre la ve en Alertas y la aprueba con un permiso temporal (se vuelve a bloquear sola) o la rechaza.
 */
@Service
public class SolicitudDesbloqueoService {
    public static final int MAX_FOTO_BYTES = 2 * 1024 * 1024;
    static final int MAX_POR_HORA = 10;
    public static final String EVENTO_SOLICITUD = "SOLICITUD_DESBLOQUEO", EVENTO_APROBADA = "SOLICITUD_APROBADA",
            EVENTO_RECHAZADA = "SOLICITUD_RECHAZADA";

    private final SolicitudDesbloqueoRepository solicitudes;
    private final DispositivoRepository dispositivos;
    private final AplicacionRepository aplicaciones;
    private final SitioBloqueadoRepository sitios;
    private final EventoAuditoriaRepository eventos;
    private final PermisoTemporalService permisos;
    private final JdbcTemplate jdbc;
    private Clock clock = Clock.systemUTC();

    public SolicitudDesbloqueoService(SolicitudDesbloqueoRepository solicitudes, DispositivoRepository dispositivos,
                                      AplicacionRepository aplicaciones, SitioBloqueadoRepository sitios,
                                      EventoAuditoriaRepository eventos, PermisoTemporalService permisos, JdbcTemplate jdbc) {
        this.solicitudes = solicitudes; this.dispositivos = dispositivos; this.aplicaciones = aplicaciones;
        this.sitios = sitios; this.eventos = eventos; this.permisos = permisos; this.jdbc = jdbc;
    }

    @Autowired(required = false) void setClock(Clock clock) { if (clock != null) this.clock = clock; }

    // --------------------------------------------------------------------------------------------- Agente (hijo)
    @Transactional
    public AgenteItem crear(String token, CrearRequest r) {
        Dispositivo d = autenticarAgente(token);
        if (d.getMenor() == null) throw new ConflictException("Este equipo no tiene un hijo asignado");
        String tipo = r.tipo() == null ? "" : r.tipo().strip().toUpperCase(Locale.ROOT);
        String mensaje = r.mensaje().strip();
        if (mensaje.length() < 3) throw new IllegalArgumentException("Escribe por qué necesitas el desbloqueo");
        Instant ahora = clock.instant();
        if (solicitudes.countByDispositivo_IdAndCreadoEnAfter(d.getId(), ahora.minus(Duration.ofHours(1))) >= MAX_POR_HORA)
            throw new ConflictException("Ya enviaste varias solicitudes en la última hora; espera la respuesta");
        Long appId = null, sitioId = null; String objetivo;
        if (SolicitudDesbloqueo.APP.equals(tipo)) {
            Aplicacion app = r.aplicacionId() == null ? null : aplicaciones.findById(r.aplicacionId())
                    .filter(a -> a.getDispositivo() != null && a.getDispositivo().getId().equals(d.getId())).orElse(null);
            if (app == null) throw new ResourceNotFoundException("Aplicación no encontrada en este equipo");
            if (solicitudes.existsByDispositivo_IdAndTipoAndAplicacionIdAndEstado(d.getId(), tipo, app.getId(), SolicitudDesbloqueo.PENDIENTE))
                throw new ConflictException("Ya hay una solicitud pendiente para " + app.getNombre());
            appId = app.getId(); objetivo = app.getNombre();
        } else if (SolicitudDesbloqueo.WEB.equals(tipo)) {
            SitioBloqueado sitio = r.sitioId() == null ? null : sitios.findByIdAndFamilia_Id(r.sitioId(), d.getFamilia().getId())
                    .filter(s -> s.getMenor() != null && s.getMenor().getId().equals(d.getMenor().getId())).orElse(null);
            if (sitio == null) throw new ResourceNotFoundException("Sitio web no encontrado para este equipo");
            if (solicitudes.existsByDispositivo_IdAndTipoAndSitioIdAndEstado(d.getId(), tipo, sitio.getId(), SolicitudDesbloqueo.PENDIENTE))
                throw new ConflictException("Ya hay una solicitud pendiente para " + sitio.getDominio());
            sitioId = sitio.getId(); objetivo = sitio.getDominio();
        } else throw new IllegalArgumentException("Tipo de solicitud no válido");
        Foto foto = foto(r.fotoBase64());
        SolicitudDesbloqueo s = new SolicitudDesbloqueo(d.getFamilia(), d.getMenor(), d, tipo, appId, sitioId,
                recortar(objetivo, 255), mensaje, ahora);
        if (foto != null) s.conFoto(foto.tipo());
        s = solicitudes.saveAndFlush(s);
        if (foto != null) jdbc.update("update solicitudes_desbloqueo set foto = ? where id = ?", foto.bytes(), s.getId());
        EventoAuditoria e = new EventoAuditoria(d.getFamilia(), EVENTO_SOLICITUD,
                recortar(d.getMenor().getNombre() + " pide desbloquear " + objetivo + ": «" + mensaje + "»"
                        + (foto == null ? "" : " (con foto)"), 2000), ResultadoEvento.INFORMATIVO);
        e.setDispositivo(d); e.setTipoEntidad("SOLICITUD_DESBLOQUEO"); e.setEntidadId(s.getId()); e.setOcurridoEn(ahora);
        eventos.save(e);
        return agente(s);
    }

    @Transactional(readOnly = true)
    public List<AgenteItem> listarAgente(String token) {
        Dispositivo d = autenticarAgente(token);
        return solicitudes.findAllByDispositivo_IdOrderByCreadoEnDesc(d.getId(), PageRequest.of(0, 30)).stream().map(this::agente).toList();
    }

    // --------------------------------------------------------------------------------------------- Padre
    @Transactional(readOnly = true)
    public List<Response> listar(UsuarioPrincipal principal) {
        validarRol(principal);
        return solicitudes.findAllByFamilia_IdOrderByCreadoEnDesc(principal.familiaId(), PageRequest.of(0, 100))
                .stream().map(this::respuesta).toList();
    }

    @Transactional(readOnly = true)
    public PendientesResponse pendientes(UsuarioPrincipal principal) {
        validarRol(principal);
        long count = solicitudes.countByFamilia_IdAndEstado(principal.familiaId(), SolicitudDesbloqueo.PENDIENTE);
        Long ultima = solicitudes.findAllByFamilia_IdOrderByCreadoEnDesc(principal.familiaId(), PageRequest.of(0, 1))
                .stream().findFirst().map(SolicitudDesbloqueo::getId).orElse(null);
        return new PendientesResponse(count, ultima);
    }

    public record FotoArchivo(byte[] bytes, String tipo) { }

    @Transactional(readOnly = true)
    public FotoArchivo foto(UsuarioPrincipal principal, Long id) {
        validarRol(principal);
        List<FotoArchivo> found = jdbc.query("select foto, foto_tipo from solicitudes_desbloqueo where id = ? and familia_id = ?",
                (rs, n) -> new FotoArchivo(rs.getBytes(1), rs.getString(2)), id, principal.familiaId());
        if (found.isEmpty() || found.get(0).bytes() == null) throw new ResourceNotFoundException("La solicitud no tiene foto");
        return found.get(0);
    }

    @Transactional
    public Response aprobar(UsuarioPrincipal principal, Long id, AprobarRequest r) {
        validarRol(principal);
        SolicitudDesbloqueo s = pendiente(principal, id);
        boolean app = SolicitudDesbloqueo.APP.equals(s.getTipo());
        if (app ? s.getAplicacionId() == null : s.getSitioId() == null)
            throw new ConflictException("La aplicación o el sitio ya no existe; no se puede aprobar");
        // Permiso temporal: se desbloquea por los minutos elegidos y vuelve a bloquearse solo.
        var permiso = permisos.otorgar(principal, new PermisoTemporalDtos.Request(UUID.randomUUID(), s.getMenor().getId(),
                List.of(s.getDispositivo().getId()), app ? PermisoTemporal.DESBLOQUEO_TEMPORAL : PermisoTemporal.PERMITIR_SITIO,
                app ? s.getAplicacionId() : null, app ? null : s.getSitioId(), null, r.minutos(),
                recortar("Solicitud del hijo: " + s.getMensaje(), 200))).permiso();
        Instant ahora = clock.instant();
        s.aprobar(principal.usuarioId(), r.minutos(), permiso.id(), limpio(r.respuesta()), ahora);
        registrar(s, EVENTO_APROBADA, "Solicitud aprobada: " + s.getObjetivo() + " desbloqueado por " + r.minutos() + " min", ResultadoEvento.EXITO, ahora);
        return respuesta(s);
    }

    @Transactional
    public Response rechazar(UsuarioPrincipal principal, Long id, RechazarRequest r) {
        validarRol(principal);
        SolicitudDesbloqueo s = pendiente(principal, id);
        Instant ahora = clock.instant();
        s.rechazar(principal.usuarioId(), limpio(r == null ? null : r.respuesta()), ahora);
        registrar(s, EVENTO_RECHAZADA, "Solicitud rechazada: " + s.getObjetivo(), ResultadoEvento.RECHAZADO, ahora);
        return respuesta(s);
    }

    // --------------------------------------------------------------------------------------------- Apoyo
    record Foto(byte[] bytes, String tipo) { }

    /** Decodifica y valida la foto: PNG o JPG reales (por su contenido, no por el nombre) y como máximo 2 MB. */
    static Foto foto(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        String data = base64.strip();
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma > 0) data = data.substring(comma + 1);
        byte[] bytes;
        try { bytes = Base64.getMimeDecoder().decode(data); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("La foto no es válida"); }
        if (bytes.length == 0) return null;
        if (bytes.length > MAX_FOTO_BYTES) throw new IllegalArgumentException("La foto supera 2 MB");
        if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return new Foto(bytes, "image/png");
        if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) return new Foto(bytes, "image/jpeg");
        throw new IllegalArgumentException("La foto debe ser PNG o JPG");
    }

    private SolicitudDesbloqueo pendiente(UsuarioPrincipal principal, Long id) {
        SolicitudDesbloqueo s = solicitudes.findByIdAndFamilia_Id(id, principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada"));
        if (!SolicitudDesbloqueo.PENDIENTE.equals(s.getEstado())) throw new ConflictException("La solicitud ya fue respondida");
        return s;
    }

    private void registrar(SolicitudDesbloqueo s, String tipo, String descripcion, ResultadoEvento resultado, Instant ahora) {
        EventoAuditoria e = new EventoAuditoria(s.getFamilia(), tipo, recortar(descripcion, 2000), resultado);
        e.setDispositivo(s.getDispositivo()); e.setTipoEntidad("SOLICITUD_DESBLOQUEO"); e.setEntidadId(s.getId()); e.setOcurridoEn(ahora);
        eventos.save(e);
    }

    private AgenteItem agente(SolicitudDesbloqueo s) {
        return new AgenteItem(s.getId(), s.getTipo(), s.getAplicacionId(), s.getSitioId(), s.getObjetivo(), s.getEstado(),
                s.getRespuesta(), s.getMinutosAprobados(), s.getCreadoEn(), s.getResueltoEn());
    }

    private Response respuesta(SolicitudDesbloqueo s) {
        return new Response(s.getId(), s.getTipo(), s.getObjetivo(), s.getMenor().getId(), s.getMenor().getNombre(),
                s.getDispositivo().getId(), s.getDispositivo().getNombre(), s.getMensaje(), s.getFotoTipo() != null,
                s.getEstado(), s.getRespuesta(), s.getMinutosAprobados(), s.getCreadoEn(), s.getResueltoEn());
    }

    private Dispositivo autenticarAgente(String token) {
        if (token == null || token.isBlank()) throw new UnauthorizedException("Agente no autorizado");
        return dispositivos.findByAgenteTokenHashAndAgenteRevocadoFalseAndActivoTrue(AutenticacionService.hashToken(token))
                .filter(x -> x.getEstado() == EstadoDispositivo.AUTORIZADO)
                .orElseThrow(() -> new UnauthorizedException("Agente no autorizado"));
    }

    private static void validarRol(UsuarioPrincipal principal) {
        if (principal.rol() != RolUsuario.PADRE && principal.rol() != RolUsuario.TUTOR) throw new ForbiddenException("Rol no autorizado");
    }

    private static String limpio(String text) { return text == null || text.isBlank() ? null : recortar(text.strip(), 300); }
    private static String recortar(String text, int max) { return text.length() <= max ? text : text.substring(0, max); }
}
