package com.familyguard.familyguard.service;

import static com.familyguard.familyguard.api.dto.ProteccionDtos.*;

import com.familyguard.familyguard.domain.model.AutorizacionDispositivo;
import com.familyguard.familyguard.domain.model.Dispositivo;
import com.familyguard.familyguard.domain.model.EstadoDispositivo;
import com.familyguard.familyguard.domain.model.EventoAuditoria;
import com.familyguard.familyguard.domain.model.Familia;
import com.familyguard.familyguard.domain.model.PinParental;
import com.familyguard.familyguard.domain.model.ResultadoEvento;
import com.familyguard.familyguard.domain.model.RolUsuario;
import com.familyguard.familyguard.domain.model.Usuario;
import com.familyguard.familyguard.domain.repository.AutorizacionDispositivoRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.EventoAuditoriaRepository;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.PinParentalRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.exception.ConflictException;
import com.familyguard.familyguard.service.exception.ForbiddenException;
import com.familyguard.familyguard.service.exception.ResourceNotFoundException;
import com.familyguard.familyguard.service.exception.UnauthorizedException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PIN parental y autorizaciones de pausa o desinstalación (V12).
 *
 * <ul>
 *   <li>Cambiar y ver el PIN exige la contraseña de la cuenta. «Ver PIN» se audita y el Administrador lo oculta a
 *   los 30 s. Tras 5 contraseñas incorrectas seguidas se bloquea 15 minutos.</li>
 *   <li>Los Agentes solo reciben el verificador PBKDF2; el PIN en claro nunca sale del servidor salvo hacia el
 *   padre autenticado.</li>
 *   <li>Las autorizaciones son de un equipo de la familia, caducan y se usan una sola vez.</li>
 * </ul>
 */
@Service
public class ProteccionService {
    public static final int SEGUNDOS_VISIBLE = 30, MAX_FALLOS = 5;
    static final Duration BLOQUEO = Duration.ofMinutes(15), VALIDEZ_DESINSTALACION = Duration.ofMinutes(60);
    static final Set<String> EVENTOS_LOCALES = Set.of("PIN_INCORRECTO", "PIN_BLOQUEADO", "PAUSA_LOCAL",
            "PAUSA_FINALIZADA", "DESINSTALACION_LOCAL", "DESINSTALACION_AUTORIZADA");

    private final FamiliaRepository familias;
    private final UsuarioRepository usuarios;
    private final DispositivoRepository dispositivos;
    private final PinParentalRepository pines;
    private final AutorizacionDispositivoRepository autorizaciones;
    private final EventoAuditoriaRepository eventos;
    private final AuditoriaAdministrativa auditoria;
    private final PasswordEncoder passwordEncoder;
    private final String claveConfigurada;
    private final Path archivoClave;
    private volatile PinCifrado cifrado;
    private final Map<Long, Fallos> fallos = new ConcurrentHashMap<>();

    private record Fallos(int cantidad, Instant bloqueadoHasta) { }

    public ProteccionService(FamiliaRepository familias, UsuarioRepository usuarios, DispositivoRepository dispositivos,
                             PinParentalRepository pines, AutorizacionDispositivoRepository autorizaciones,
                             EventoAuditoriaRepository eventos, AuditoriaAdministrativa auditoria,
                             PasswordEncoder passwordEncoder,
                             @Value("${familyguard.pin.clave:${FAMILYGUARD_PIN_KEY:}}") String claveConfigurada,
                             @Value("${familyguard.pin.archivo-clave:${user.home}/.familyguard/pin-servidor.key}") String archivoClave) {
        this.familias = familias; this.usuarios = usuarios; this.dispositivos = dispositivos; this.pines = pines;
        this.autorizaciones = autorizaciones; this.eventos = eventos; this.auditoria = auditoria;
        this.passwordEncoder = passwordEncoder; this.claveConfigurada = claveConfigurada;
        this.archivoClave = Path.of(archivoClave);
    }

    // -------------------------------------------------------------------------------------- PIN (padre)
    @Transactional(readOnly = true)
    public PinEstadoResponse estadoPin(UsuarioPrincipal principal) {
        validarRol(principal);
        return pines.findByFamilia_Id(principal.familiaId())
                .map(p -> new PinEstadoResponse(true, p.getVersion(), p.getActualizadoEn()))
                .orElse(new PinEstadoResponse(false, 0, null));
    }

    @Transactional
    public PinEstadoResponse cambiarPin(UsuarioPrincipal principal, CambiarPinRequest request) {
        validarRol(principal);
        verificarPassword(principal, request.password());
        if (!request.pin().equals(request.confirmarPin())) throw new IllegalArgumentException("La confirmación del PIN no coincide");
        PinCifrado.validar(request.pin());
        Familia familia = familias.bloquearParaOperacion(principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Familia no encontrada"));
        PinParental pin = pines.findByFamilia_Id(familia.getId()).orElseGet(() -> new PinParental(familia));
        pin.cambiar(cifrado().cifrar(request.pin()), PinCifrado.verificador(request.pin()), principal.usuarioId());
        pines.saveAndFlush(pin);
        dispositivos.findAllByFamilia_IdAndActivoTrue(familia.getId()).stream()
                .filter(d -> !d.isAgenteRevocado() && d.getEstado() == EstadoDispositivo.AUTORIZADO)
                .forEach(Dispositivo::incrementarRevisionDeseada);
        auditoria.registrar(principal, "PIN_PARENTAL_CAMBIADO", "PIN_PARENTAL", pin.getId(), null,
                "PIN parental " + (pin.getVersion() == 1 ? "configurado" : "cambiado") + " (versión " + pin.getVersion() + ")");
        return new PinEstadoResponse(true, pin.getVersion(), pin.getActualizadoEn());
    }

    @Transactional
    public PinVisibleResponse verPin(UsuarioPrincipal principal, VerPinRequest request) {
        validarRol(principal);
        verificarPassword(principal, request.password());
        PinParental pin = pines.findByFamilia_Id(principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Todavía no has configurado el PIN parental"));
        String valor = cifrado().descifrar(pin.getPinCifrado());
        auditoria.registrar(principal, "PIN_PARENTAL_CONSULTADO", "PIN_PARENTAL", pin.getId(), null,
                "Se mostró el PIN parental durante " + SEGUNDOS_VISIBLE + " segundos");
        return new PinVisibleResponse(valor, pin.getVersion(), SEGUNDOS_VISIBLE);
    }

    // ---------------------------------------------------------------------------- Autorizaciones (padre)
    @Transactional
    public AutorizacionResponse autorizar(UsuarioPrincipal principal, AutorizacionRequest request) {
        validarRol(principal);
        String tipo = request.tipo() == null ? "" : request.tipo().strip().toUpperCase(Locale.ROOT);
        if (!AutorizacionDispositivo.PAUSA.equals(tipo) && !AutorizacionDispositivo.DESINSTALACION.equals(tipo))
            throw new IllegalArgumentException("Tipo de autorización no válido");
        familias.bloquearParaOperacion(principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Familia no encontrada"));
        Dispositivo d = dispositivos.findByFamilia_IdAndId(principal.familiaId(), request.dispositivoId())
                .orElseThrow(() -> new ResourceNotFoundException("Equipo no encontrado"));
        if (!d.isActivo() || d.isAgenteRevocado() || d.getEstado() != EstadoDispositivo.AUTORIZADO)
            throw new IllegalArgumentException("El equipo no está vinculado");
        Instant ahora = Instant.now();
        Instant expira;
        if (AutorizacionDispositivo.PAUSA.equals(tipo)) {
            int minutos = request.minutos() == null ? 30 : request.minutos();
            if (minutos < 5 || minutos > 240) throw new IllegalArgumentException("La pausa debe durar entre 5 minutos y 4 horas");
            expira = ahora.plus(Duration.ofMinutes(minutos));
        } else expira = ahora.plus(VALIDEZ_DESINSTALACION);
        AutorizacionDispositivo a = autorizaciones.save(new AutorizacionDispositivo(d, tipo, principal.usuarioId(), ahora, expira));
        d.incrementarRevisionDeseada();
        auditoria.registrar(principal, AutorizacionDispositivo.PAUSA.equals(tipo) ? "PAUSA_AUTORIZADA" : "DESINSTALACION_AUTORIZADA",
                "AUTORIZACION_DISPOSITIVO", a.getId(), d.getId(),
                (AutorizacionDispositivo.PAUSA.equals(tipo) ? "Pausa de la supervisión autorizada hasta " : "Desinstalación autorizada hasta ") + expira);
        return respuesta(a, ahora);
    }

    @Transactional
    public AutorizacionResponse cancelar(UsuarioPrincipal principal, Long id) {
        validarRol(principal);
        AutorizacionDispositivo a = autorizaciones.findByIdAndFamiliaId(id, principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Autorización no encontrada"));
        Instant ahora = Instant.now();
        if (a.getCanceladaEn() == null && a.getExpiraEn().isAfter(ahora)) {
            a.cancelar(principal.usuarioId(), ahora);
            a.getDispositivo().incrementarRevisionDeseada();
            autorizaciones.flush();
            auditoria.registrar(principal, "AUTORIZACION_CANCELADA", "AUTORIZACION_DISPOSITIVO", a.getId(),
                    a.getDispositivo().getId(), "Autorización de " + a.getTipo().toLowerCase(Locale.ROOT) + " cancelada");
        }
        return respuesta(a, ahora);
    }

    @Transactional(readOnly = true)
    public List<AutorizacionResponse> listar(UsuarioPrincipal principal, Long dispositivoId) {
        validarRol(principal);
        dispositivos.findByFamilia_IdAndId(principal.familiaId(), dispositivoId)
                .orElseThrow(() -> new ResourceNotFoundException("Equipo no encontrado"));
        Instant ahora = Instant.now();
        return autorizaciones.findTop50ByFamiliaIdAndDispositivo_IdOrderByIdDesc(principal.familiaId(), dispositivoId)
                .stream().map(a -> respuesta(a, ahora)).toList();
    }

    // ------------------------------------------------------------------------------------------ Agente
    @Transactional(readOnly = true)
    public PinAgenteResponse pinAgente(String token) {
        Dispositivo d = autenticarAgente(token);
        return pines.findByFamilia_Id(d.getFamilia().getId())
                .map(p -> new PinAgenteResponse(true, p.getVersion(), p.getVerificador()))
                .orElse(new PinAgenteResponse(false, 0, null));
    }

    @Transactional(readOnly = true)
    public AutorizacionesAgenteResponse autorizacionesAgente(String token) {
        Dispositivo d = autenticarAgente(token);
        Instant ahora = Instant.now();
        return new AutorizacionesAgenteResponse(ahora,
                autorizaciones.findTop50ByFamiliaIdAndDispositivo_IdOrderByIdDesc(d.getFamilia().getId(), d.getId()).stream()
                        .filter(a -> a.vigente(ahora)).map(a -> new AutorizacionAgente(a.getId(), a.getTipo(), a.getExpiraEn())).toList());
    }

    /** El Agente marca la autorización como usada; una autorización vencida, cancelada o ajena se rechaza. */
    @Transactional
    public AutorizacionAgente usarAutorizacion(String token, Long id) {
        Dispositivo d = autenticarAgente(token);
        AutorizacionDispositivo a = autorizaciones.findByIdAndFamiliaId(id, d.getFamilia().getId())
                .filter(x -> x.getDispositivo().getId().equals(d.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Autorización no encontrada"));
        Instant ahora = Instant.now();
        if (a.getUsadaEn() != null) return new AutorizacionAgente(a.getId(), a.getTipo(), a.getExpiraEn());
        if (!a.vigente(ahora)) throw new ConflictException("La autorización ya no está vigente");
        a.usar(ahora);
        EventoAuditoria evento = new EventoAuditoria(d.getFamilia(), AutorizacionDispositivo.PAUSA.equals(a.getTipo())
                ? "PAUSA_INICIADA" : "DESINSTALACION_INICIADA",
                (AutorizacionDispositivo.PAUSA.equals(a.getTipo()) ? "Comenzó la pausa autorizada en "
                        : "Comenzó la desinstalación autorizada en ") + d.getNombre(), ResultadoEvento.EXITO);
        evento.setDispositivo(d); evento.setTipoEntidad("AUTORIZACION_DISPOSITIVO"); evento.setEntidadId(a.getId());
        eventos.save(evento);
        return new AutorizacionAgente(a.getId(), a.getTipo(), a.getExpiraEn());
    }

    /** Eventos de protección local (PIN incorrecto, bloqueo, pausa con PIN…). Idempotente por identificador. */
    @Transactional
    public EventosLocalesResponse eventosLocales(String token, EventosLocalesRequest request) {
        Dispositivo d = autenticarAgente(token);
        Instant ahora = Instant.now();
        List<UUID> confirmados = new ArrayList<>();
        for (EventoLocal item : request.eventos()) {
            String tipo = item.tipo().strip().toUpperCase(Locale.ROOT);
            if (!EVENTOS_LOCALES.contains(tipo)) throw new IllegalArgumentException("Tipo de evento no válido");
            String uuid = item.eventoId().toString();
            if (!eventos.existsByEventoUuid(uuid)) {
                EventoAuditoria evento = new EventoAuditoria(d.getFamilia(), tipo,
                        texto(tipo, d.getNombre()) + (item.detalle() == null || item.detalle().isBlank() ? "" : " — " + item.detalle().strip()),
                        tipo.startsWith("PIN_") ? ResultadoEvento.ERROR : ResultadoEvento.EXITO);
                evento.setDispositivo(d); evento.setTipoEntidad("PROTECCION_LOCAL"); evento.setEventoUuid(uuid);
                evento.setOcurridoEn(item.ocurridoEn().isAfter(ahora.plusSeconds(300)) ? ahora : item.ocurridoEn());
                eventos.save(evento);
            }
            confirmados.add(item.eventoId());
        }
        return new EventosLocalesResponse(List.copyOf(confirmados));
    }

    // ----------------------------------------------------------------------------------------- Interno
    static String texto(String tipo, String equipo) {
        return switch (tipo) {
            case "PIN_INCORRECTO" -> "PIN incorrecto en " + equipo;
            case "PIN_BLOQUEADO" -> "Demasiados PIN incorrectos en " + equipo + "; bloqueado temporalmente";
            case "PAUSA_LOCAL" -> "Supervisión pausada con el PIN en " + equipo;
            case "PAUSA_FINALIZADA" -> "Terminó la pausa en " + equipo;
            case "DESINSTALACION_LOCAL" -> "Desinstalación iniciada con el PIN en " + equipo;
            default -> "Desinstalación autorizada usada en " + equipo;
        };
    }

    private AutorizacionResponse respuesta(AutorizacionDispositivo a, Instant ahora) {
        String estado = a.getCanceladaEn() != null ? "CANCELADA" : a.getUsadaEn() != null
                ? (AutorizacionDispositivo.PAUSA.equals(a.getTipo()) && a.getExpiraEn().isAfter(ahora) ? "EN_CURSO" : "USADA")
                : a.getExpiraEn().isAfter(ahora) ? "VIGENTE" : "VENCIDA";
        return new AutorizacionResponse(a.getId(), a.getDispositivo().getId(), a.getDispositivo().getNombre(), a.getTipo(),
                a.getAutorizadoEn(), a.getExpiraEn(), a.getUsadaEn(), a.getCanceladaEn(), estado);
    }

    private void verificarPassword(UsuarioPrincipal principal, String password) {
        Instant ahora = Instant.now();
        Fallos previos = fallos.get(principal.usuarioId());
        if (previos != null && previos.bloqueadoHasta() != null && previos.bloqueadoHasta().isAfter(ahora))
            throw new ConflictException("Demasiados intentos. Vuelve a intentarlo en unos minutos");
        Usuario usuario = usuarios.findById(principal.usuarioId())
                .filter(u -> u.isActivo() && u.getFamilia().getId().equals(principal.familiaId()))
                .orElseThrow(() -> new UnauthorizedException("Sesión no válida"));
        if (password == null || !passwordEncoder.matches(password, usuario.getPasswordHash())) {
            int cantidad = (previos == null || (previos.bloqueadoHasta() != null && !previos.bloqueadoHasta().isAfter(ahora)))
                    ? 1 : previos.cantidad() + 1;
            fallos.put(principal.usuarioId(), new Fallos(cantidad, cantidad >= MAX_FALLOS ? ahora.plus(BLOQUEO) : null));
            // 400 (no 401): una contraseña mal escrita no debe cerrar la sesión del Administrador.
            throw new IllegalArgumentException("La contraseña no es correcta");
        }
        fallos.remove(principal.usuarioId());
    }

    private PinCifrado cifrado() {
        PinCifrado actual = cifrado;
        if (actual == null) synchronized (this) {
            if (cifrado == null) cifrado = PinCifrado.desde(claveConfigurada, archivoClave);
            actual = cifrado;
        }
        return actual;
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
