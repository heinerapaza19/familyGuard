package com.familyguard.familyguard.service;

import static com.familyguard.familyguard.api.dto.VinculacionDtos.*;

import com.familyguard.familyguard.api.dto.DispositivoResponse;
import com.familyguard.familyguard.domain.model.*;
import com.familyguard.familyguard.domain.repository.*;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.exception.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VinculacionAgenteService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(VinculacionAgenteService.class);
    private static final Duration CODIGO_DURACION = Duration.ofMinutes(10);
    private static final Duration MIN_SINCRONIZACION = Duration.ofSeconds(10);
    private static final char[] ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Instant> ultimaSolicitud = new ConcurrentHashMap<>();
    private final CodigoVinculacionRepository codigos;
    private final FamiliaRepository familias;
    private final MenorRepository menores;
    private final DispositivoRepository dispositivos;
    private final AplicacionRepository aplicaciones;
    private final ProcesoDetectadoRepository procesos;
    private final UsoAplicacionRepository usos;
    private final ConfirmacionSincronizacionRepository confirmaciones;
    private final IntentosVinculacionService intentos;
    private final EventoAuditoriaRepository auditoria;
    static final String EVENTO_VINCULADO = "DISPOSITIVO_VINCULADO";
    static final String ENTIDAD_CODIGO = "CODIGO_VINCULACION";
    static final int MAX_FALLOS_POR_IP = 20;

    private final AuditoriaAdministrativa administrativa;
    public VinculacionAgenteService(CodigoVinculacionRepository codigos, FamiliaRepository familias,
            MenorRepository menores, DispositivoRepository dispositivos,
            AplicacionRepository aplicaciones, ProcesoDetectadoRepository procesos,
            UsoAplicacionRepository usos, ConfirmacionSincronizacionRepository confirmaciones,
            IntentosVinculacionService intentos, EventoAuditoriaRepository auditoria,
            AuditoriaAdministrativa administrativa) {
        this.administrativa = administrativa;
        this.codigos = codigos; this.familias = familias; this.menores = menores;
        this.dispositivos = dispositivos; this.aplicaciones = aplicaciones; this.procesos = procesos;
        this.usos = usos;this.confirmaciones=confirmaciones;this.intentos=intentos;this.auditoria=auditoria;
    }

    @Transactional
    public CodigoResponse generarCodigo(UsuarioPrincipal principal, Long menorId) {
        validarFamiliar(principal);
        Menor menor = menores.findByIdAndFamilia_IdAndActivoTrue(menorId, principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Perfil no encontrado"));
        Instant ahora = Instant.now();
        // Generar otro código invalida el anterior que siga pendiente para el mismo hijo.
        codigos.findAllByFamilia_IdAndMenor_IdAndUsadoEnIsNullAndBloqueadoEnIsNullAndExpiraEnAfter(principal.familiaId(), menorId, ahora)
                .forEach(CodigoVinculacion::expirar);
        String codigo = nuevoCodigo();
        Instant expira = ahora.plus(CODIGO_DURACION);
        CodigoVinculacion nuevo = codigos.save(new CodigoVinculacion(familias.getReferenceById(principal.familiaId()), menor,
                AutenticacionService.hashToken(codigo), expira));
        return new CodigoResponse(codigo, expira, nuevo.getId());
    }

    /** Permite al Administrador saber, sin intervención del usuario, si el agente ya usó el código. */
    @Transactional(readOnly = true)
    public EstadoCodigoResponse estadoCodigo(UsuarioPrincipal principal, Long codigoId) {
        validarFamiliar(principal);
        CodigoVinculacion codigo = codigos.findByIdAndFamilia_Id(codigoId, principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Codigo no encontrado"));
        if (codigo.getUsadoEn() != null) {
            var evento = auditoria.findFirstByFamilia_IdAndTipoEventoAndTipoEntidadAndEntidadIdOrderByOcurridoEnDesc(
                    principal.familiaId(), EVENTO_VINCULADO, ENTIDAD_CODIGO, codigo.getId());
            Dispositivo d = evento.map(EventoAuditoria::getDispositivo).orElse(null);
            return new EstadoCodigoResponse(codigo.getId(), "VINCULADO", codigo.getExpiraEn(),
                    d == null ? null : d.getId(), d == null ? null : d.getNombre());
        }
        if (codigo.anulado())
            return new EstadoCodigoResponse(codigo.getId(), "REEMPLAZADO", codigo.getExpiraEn(), null, null);
        if (codigo.vigente(Instant.now()))
            return new EstadoCodigoResponse(codigo.getId(), "PENDIENTE", codigo.getExpiraEn(), null, null);
        return new EstadoCodigoResponse(codigo.getId(), "VENCIDO", codigo.getExpiraEn(), null, null);
    }

    @Transactional
    public VincularResponse vincular(VincularRequest request) { return vincular(request, null); }

    /**
     * Vinculación con doble límite de intentos: por identificador del equipo (5 fallos) y por la
     * dirección IP real del cliente (20 fallos), que el atacante no puede cambiar a voluntad.
     */
    @Transactional
    public VincularResponse vincular(VincularRequest request, String direccionIp) {
        String attemptKey=AutenticacionService.hashToken(request.identificador().strip().toLowerCase(Locale.ROOT));
        String ipKey = claveIp(direccionIp);
        if (ipKey != null && intentos.bloqueado(ipKey))
            throw new UnauthorizedException("Demasiados intentos de vinculación desde esta red; espere 15 minutos");
        if (intentos.bloqueado(attemptKey))
            throw new UnauthorizedException("Demasiados intentos de vinculación; espere 15 minutos o use otro equipo autorizado");
        String codigo = request.codigo().strip().toUpperCase(Locale.ROOT);
        String codigoHash = AutenticacionService.hashToken(codigo);
        Instant ahora = Instant.now();
        CodigoVinculacion vinculacion = codigos.findByCodigoHashAndUsadoEnIsNullAndBloqueadoEnIsNullAndExpiraEnAfter(
                codigoHash, ahora).orElse(null);
        // Doble verificación en memoria: un código anulado, usado o vencido nunca vincula.
        if (vinculacion != null && !vinculacion.vigente(ahora)) vinculacion = null;
        if (vinculacion == null) {
            boolean bloqueado = intentos.registrarFallo(attemptKey);
            if (ipKey != null) bloqueado |= intentos.registrarFallo(ipKey, MAX_FALLOS_POR_IP);
            if (bloqueado) throw new UnauthorizedException("Demasiados intentos de vinculación; espere 15 minutos");
            // Solo quien conoce el código exacto puede distinguir un código usado, vencido o reemplazado.
            CodigoVinculacion existente = codigos.findByCodigoHash(codigoHash).orElse(null);
            if (existente == null) throw new UnauthorizedException("Codigo invalido; revise los 8 caracteres");
            if (existente.getUsadoEn() != null)
                throw new UnauthorizedException("El codigo ya fue utilizado; solicite uno nuevo");
            throw new UnauthorizedException(existente.anulado()
                    ? "El codigo fue reemplazado por uno nuevo; use el ultimo codigo generado"
                    : "El codigo vencio; solicite uno nuevo");
        }
        Optional<Dispositivo> registrado = dispositivos.findByIdentificador(request.identificador().strip());
        if (registrado.isPresent()) {
            return revincular(registrado.get(), vinculacion, request, attemptKey);
        }
        String token = nuevoToken();
        Dispositivo dispositivo = new Dispositivo(vinculacion.getFamilia(), request.nombre().strip(),
                request.identificador().strip(), TipoDispositivo.WINDOWS);
        dispositivo.setMenor(vinculacion.getMenor());
        dispositivo.setSistemaOperativo(request.sistemaOperativo().strip());
        dispositivo.setVersionSistema(request.versionSistema().strip());
        dispositivo.setEstado(EstadoDispositivo.AUTORIZADO);
        dispositivo.setAgenteTokenHash(AutenticacionService.hashToken(token));
        dispositivo.setUltimaConexion(Instant.now());
        dispositivo.setUltimaSincronizacion(Instant.now());
        // Vincular no prueba que el agente esté en ejecución: el heartbeat queda vacío hasta la primera
        // señal real (POST /api/agent/heartbeat). Así el panel no muestra «Conectado» por la sola vinculación.
        dispositivo.setUsuarioLocal(limpiar(request.usuarioLocal()));dispositivo.setVersionAgente(limpiar(request.versionAgente()));
        dispositivos.save(dispositivo);
        vinculacion.usar();
        intentos.limpiar(attemptKey);
        registrarVinculacion(vinculacion, dispositivo, direccionIp, "Dispositivo vinculado: ");
        return new VincularResponse(dispositivo.getId(), token, dispositivo.getEstado().name());
    }

    /** Auditoría de la vinculación; también permite al Administrador mostrar qué equipo usó el código. */
    private void registrarVinculacion(CodigoVinculacion codigo, Dispositivo dispositivo, String ip, String prefijo) {
        EventoAuditoria evento = new EventoAuditoria(dispositivo.getFamilia(), EVENTO_VINCULADO,
                prefijo + dispositivo.getNombre(), ResultadoEvento.EXITO);
        evento.setDispositivo(dispositivo);
        evento.setTipoEntidad(ENTIDAD_CODIGO);
        evento.setEntidadId(codigo.getId());
        if (ip != null && ip.length() <= 45) evento.setDireccionIp(ip);
        auditoria.save(evento);
    }

    /** Las direcciones de bucle local no se limitan: en desarrollo todos los clientes comparten 127.0.0.1. */
    static String claveIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        String value = ip.strip();
        if (value.startsWith("127.") || value.equals("::1") || value.equals("0:0:0:0:0:0:0:1")) return null;
        return AutenticacionService.hashToken("ip:" + value.toLowerCase(Locale.ROOT));
    }

    /**
     * Revinculación de un equipo previamente revocado. Reutiliza el mismo registro (sin duplicados),
     * conserva historial, aplicaciones y restricciones, y solo procede si el código temporal pertenece
     * a la misma familia y al mismo perfil del dispositivo. Un equipo activo nunca se reemplaza.
     */
    private VincularResponse revincular(Dispositivo dispositivo, CodigoVinculacion vinculacion,
                                        VincularRequest request, String attemptKey) {
        boolean mismaFamilia = Objects.equals(dispositivo.getFamilia().getId(), vinculacion.getFamilia().getId());
        boolean revocado = dispositivo.isAgenteRevocado() && dispositivo.getEstado() == EstadoDispositivo.REVOCADO;
        if (!mismaFamilia || !revocado || !dispositivo.isActivo()) {
            // Mismo mensaje que antes: no revela si el equipo pertenece a otra familia.
            throw new ConflictException("El dispositivo ya esta registrado");
        }
        if (dispositivo.getMenor() != null
                && !Objects.equals(dispositivo.getMenor().getId(), vinculacion.getMenor().getId())) {
            throw new ConflictException("El codigo temporal corresponde a otro perfil; genere el codigo desde el perfil asignado a este equipo");
        }
        String token = nuevoToken();
        Instant ahora = Instant.now();
        if (dispositivo.getMenor() == null) dispositivo.setMenor(vinculacion.getMenor());
        dispositivo.setNombre(request.nombre().strip());
        dispositivo.setSistemaOperativo(request.sistemaOperativo().strip());
        dispositivo.setVersionSistema(request.versionSistema().strip());
        dispositivo.setUsuarioLocal(limpiar(request.usuarioLocal()));
        dispositivo.setVersionAgente(limpiar(request.versionAgente()));
        dispositivo.renovarCredencial(AutenticacionService.hashToken(token), ahora);
        dispositivos.save(dispositivo);
        vinculacion.usar();
        intentos.limpiar(attemptKey);
        registrarVinculacion(vinculacion, dispositivo, null, "Dispositivo revinculado: ");
        return new VincularResponse(dispositivo.getId(), token, dispositivo.getEstado().name());
    }

    @Transactional
    public EstadoAgenteResponse sincronizar(String token, InventarioRequest request) {
        Dispositivo dispositivo = autenticar(token);
        Instant anterior = ultimaSolicitud.put(dispositivo.getId(), Instant.now());
        if (anterior != null && anterior.plus(MIN_SINCRONIZACION).isAfter(Instant.now()))
            throw new ConflictException("Sincronizacion demasiado frecuente");

        Map<String, AplicacionItem> unicas = new LinkedHashMap<>();
        request.aplicaciones().forEach(item -> unicas.putIfAbsent(clave(item.nombre(), item.rutaEjecutable()), item));
        Map<String, Aplicacion> existentes = new HashMap<>();
        aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(dispositivo.getFamilia().getId(), dispositivo.getId())
                .forEach(a -> existentes.put(clave(a.getNombre(), a.getRutaEjecutable()), a));
        Map<String, ProcesoItem> procesosPorRuta = new HashMap<>();
        request.procesos().forEach(p -> procesosPorRuta.put(clave(p.nombre(), p.rutaEjecutable()), p));
        existentes.values().forEach(a -> { a.setEnEjecucion(false); a.setVentanaActiva(false); a.setUltimoPid(null); });
        for (AplicacionItem item : unicas.values()) {
            Aplicacion app = existentes.get(clave(item.nombre(), item.rutaEjecutable()));
            if (app == null) app = new Aplicacion(dispositivo.getFamilia(), dispositivo, item.nombre().strip(), limpiar(item.rutaEjecutable()));
            app.setVersion(limpiar(item.version())); app.setEditor(limpiar(item.editor()));
            app.setTipoInstalacion(limpiar(item.tipoInstalacion())); app.setUltimaDeteccion(request.observadoEn());
            ProcesoItem proceso = procesosPorRuta.get(clave(item.nombre(), item.rutaEjecutable()));
            if (proceso != null) { app.setEnEjecucion(true); app.setVentanaActiva(proceso.ventanaActiva()); app.setUltimoPid(proceso.pid()); }
            aplicaciones.save(app);
        }
        Set<Long> pids = new HashSet<>();
        for (ProcesoItem item : request.procesos()) {
            if (!pids.add(item.pid())) continue;
            ProcesoDetectado proceso = procesos.findByDispositivo_IdAndPid(dispositivo.getId(), item.pid())
                    .orElseGet(() -> new ProcesoDetectado(dispositivo.getFamilia(), dispositivo, item.pid()));
            proceso.actualizar(item.nombre().strip(), limpiar(item.rutaEjecutable()), item.ventanaActiva(), request.observadoEn());
            procesos.save(proceso);
        }
        procesos.findAllByDispositivo_Id(dispositivo.getId()).stream().filter(p -> !pids.contains(p.getPid()))
                .forEach(p -> p.detener(request.observadoEn()));
        dispositivo.setUltimaConexion(Instant.now()); dispositivo.setUltimaSincronizacion(request.observadoEn());
        return new EstadoAgenteResponse(dispositivo.getId(), "CONECTADO", dispositivo.getUltimaConexion());
    }

    @Transactional(readOnly = true)
    public EstadoAgenteResponse estado(String token) {
        Dispositivo d = autenticar(token);
        return new EstadoAgenteResponse(d.getId(), d.getEstado().name(), d.getUltimaConexion());
    }

    /**
     * Sincroniza un lote de intervalos de uso procesando cada registro de forma independiente.
     * Un registro inválido ya no rechaza el lote: se informa en {@code rechazados} y su UUID se
     * incluye en {@code confirmados}, que significa «procesado definitivamente; retirar de la cola».
     * Así los agentes antiguos, que solo leen {@code confirmados}, tampoco lo reenvían sin fin.
     */
    @Transactional
    public UsoResponse sincronizarUso(String token, UsoRequest request) {
        Dispositivo dispositivo = autenticar(token);
        if (dispositivo.getMenor() == null) {
            throw new ConflictException("El dispositivo no tiene un perfil supervisado asignado");
        }
        ZoneId zona;
        try { zona = ZoneId.of(dispositivo.getFamilia().getZonaHoraria()); }
        catch (Exception ignored) { zona = ZoneId.of("UTC"); }
        Instant ahora = Instant.now();
        List<UUID> confirmados = new ArrayList<>();
        List<UsoRechazado> rechazados = new ArrayList<>();
        Set<UUID> vistos = new HashSet<>();
        int aceptados = 0;
        int duplicados = 0;
        for (UsoItem item : request.registros()) {
            if (item == null || item.registroId() == null) {
                rechazados.add(new UsoRechazado(null, "REGISTRO_SIN_IDENTIFICADOR",
                        "El registro no contiene un identificador UUID"));
                continue;
            }
            UUID id = item.registroId();
            String uuid = id.toString();
            if (!vistos.add(id) || usos.existsByRegistroUuid(uuid)) {
                confirmados.add(id);
                duplicados++;
                continue;
            }
            Optional<UsoRechazado> rechazo = validarRegistro(item, ahora);
            if (rechazo.isPresent()) {
                rechazados.add(rechazo.get());
                confirmados.add(id);
                LOG.warn("Uso rechazado: dispositivo={} registro={} codigo={}",
                        dispositivo.getId(), uuid, rechazo.get().codigo());
                continue;
            }
            Aplicacion aplicacion = buscarOCrearAplicacion(dispositivo, item);
            UsoAplicacion uso = new UsoAplicacion(dispositivo.getFamilia(), dispositivo.getMenor(),
                    dispositivo, aplicacion, item.inicioEn(), item.finEn(), uuid,
                    item.inicioEn().atZone(zona).toLocalDate(), zona.getId());
            uso.setDuracionSegundos(item.duracionSegundos());
            usos.save(uso);
            confirmados.add(id);
            aceptados++;
        }
        dispositivo.setUltimaConexion(ahora);
        dispositivo.setUltimaSincronizacion(ahora);
        return new UsoResponse(aceptados, List.copyOf(confirmados), duplicados, List.copyOf(rechazados));
    }

    @Transactional
    public void revocar(UsuarioPrincipal principal, Long dispositivoId) {
        validarFamiliar(principal);
        Dispositivo d = dispositivos.findByFamilia_IdAndId(principal.familiaId(), dispositivoId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispositivo no encontrado"));
        d.setAgenteRevocado(true); d.setAgenteTokenHash(null); d.setEstado(EstadoDispositivo.REVOCADO);d.setDesvinculadoEn(Instant.now());d.incrementarRevisionDeseada();
        administrativa.registrar(principal, "DISPOSITIVO_REVOCADO", "DISPOSITIVO", d.getId(), d.getId(),
                "Dispositivo revocado: " + d.getNombre());
    }
    @Transactional public HeartbeatResponse heartbeat(String token,HeartbeatRequest r){Dispositivo d=autenticar(token);Instant now=Instant.now();d.setUltimoHeartbeat(now);d.setUltimaConexion(now);d.setIntervaloHeartbeatSegundos(r.intervaloSegundos());d.setUsuarioLocal(limpiar(r.usuarioLocal()));d.setVersionAgente(limpiar(r.versionAgente()));d.setUltimoErrorComunicacion(limpiar(r.ultimoError()));if(r.revisionAplicada()<=d.getRevisionDeseada()&&r.revisionAplicada()>d.getRevisionAplicada())d.setRevisionAplicada(r.revisionAplicada());return new HeartbeatResponse(d.getId(),"CONECTADO",now,d.getRevisionDeseada(),d.getRevisionAplicada(),d.getRevisionAplicada()<d.getRevisionDeseada(),d.getIntervaloHeartbeatSegundos());}
    @Transactional public ConfirmacionResponse confirmar(String token,ConfirmacionRequest r){Dispositivo d=autenticar(token);if(confirmaciones.existsByUuid(r.confirmacionId().toString()))return new ConfirmacionResponse(false,d.getRevisionAplicada(),d.getRevisionDeseada());if(r.revision()>d.getRevisionDeseada())throw new ConflictException("La confirmación supera la revisión autorizada");confirmaciones.save(new ConfirmacionSincronizacion(d.getFamilia(),d,r.confirmacionId().toString(),r.revision(),r.estado(),r.detalle(),r.confirmadoEn()));if("APLICADA".equals(r.estado())){if(r.revision()>d.getRevisionAplicada())d.setRevisionAplicada(r.revision());d.setUltimaSincronizacion(r.confirmadoEn());d.setUltimoErrorComunicacion(null);}else if("FALLIDA".equals(r.estado()))d.setUltimoErrorComunicacion(r.detalle());return new ConfirmacionResponse(true,d.getRevisionAplicada(),d.getRevisionDeseada());}

    /** Datos mínimos de un Agente autenticado para abrir su canal de avisos en tiempo real (Etapa 5). */
    public record SesionEventos(long dispositivoId, int versionCredencial, long revisionDeseada) { }

    /** Misma validación que el resto de la API del Agente: credencial vigente, equipo activo y autorizado. */
    @Transactional(readOnly = true)
    public SesionEventos sesionEventos(String token) {
        Dispositivo d = autenticar(token);
        return new SesionEventos(d.getId(), d.getVersionCredencial(), d.getRevisionDeseada());
    }

    private Dispositivo autenticar(String token) {
        if (token == null || token.isBlank()) throw new UnauthorizedException("Agente no autorizado");
        return dispositivos.findByAgenteTokenHashAndAgenteRevocadoFalseAndActivoTrue(AutenticacionService.hashToken(token))
                .filter(d -> d.getEstado() == EstadoDispositivo.AUTORIZADO)
                .orElseThrow(() -> new UnauthorizedException("Agente no autorizado"));
    }
    private Aplicacion buscarOCrearAplicacion(Dispositivo dispositivo, UsoItem item) {
        String ruta = limpiar(item.rutaEjecutable());
        Optional<Aplicacion> existente = ruta == null ? Optional.empty()
                : aplicaciones.findByDispositivo_IdAndRutaEjecutable(dispositivo.getId(), ruta);
        if (existente.isEmpty()) {
            existente = aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(
                            dispositivo.getFamilia().getId(), dispositivo.getId()).stream()
                    .filter(a -> a.getNombre().equalsIgnoreCase(item.aplicacionNombre().strip()))
                    .findFirst();
        }
        Aplicacion aplicacion = existente.orElseGet(() -> new Aplicacion(dispositivo.getFamilia(),
                dispositivo, item.aplicacionNombre().strip(), ruta));
        aplicacion.setEnEjecucion(true);
        aplicacion.setVentanaActiva(true);
        aplicacion.setUltimoPid(item.pid());
        aplicacion.setUltimaDeteccion(item.finEn());
        return aplicaciones.save(aplicacion);
    }
    /**
     * Valida un registro aislado. Cubre también las restricciones de columna (longitudes y
     * CHECK de MySQL) para que ningún registro pueda abortar la transacción del lote.
     */
    static Optional<UsoRechazado> validarRegistro(UsoItem item, Instant ahora) {
        UUID id = item.registroId();
        if (item.aplicacionNombre() == null || item.aplicacionNombre().isBlank() || item.aplicacionNombre().strip().length() > 180)
            return Optional.of(new UsoRechazado(id, "APLICACION_INVALIDA", "Nombre de aplicacion ausente o superior a 180 caracteres"));
        if (item.rutaEjecutable() != null && item.rutaEjecutable().strip().length() > 700)
            return Optional.of(new UsoRechazado(id, "RUTA_INVALIDA", "La ruta ejecutable supera 700 caracteres"));
        if (item.pid() <= 0)
            return Optional.of(new UsoRechazado(id, "PID_INVALIDO", "El identificador de proceso debe ser positivo"));
        if (item.inicioEn() == null || item.finEn() == null)
            return Optional.of(new UsoRechazado(id, "INTERVALO_INCOMPLETO", "Faltan las fechas de inicio o fin"));
        long calculada = Duration.between(item.inicioEn(), item.finEn()).getSeconds();
        if (calculada <= 0)
            return Optional.of(new UsoRechazado(id, "INTERVALO_INVALIDO", "La fecha de fin debe ser posterior al inicio"));
        if (calculada > 86400 || item.duracionSegundos() > 86400)
            return Optional.of(new UsoRechazado(id, "DURACION_EXCEDIDA", "Un intervalo no puede superar 24 horas"));
        if (Math.abs(calculada - item.duracionSegundos()) > 1)
            return Optional.of(new UsoRechazado(id, "DURACION_INCONSISTENTE", "La duracion no coincide con el intervalo"));
        if (item.finEn().isAfter(ahora.plusSeconds(300)))
            return Optional.of(new UsoRechazado(id, "INTERVALO_FUTURO", "El intervalo termina en el futuro; revise el reloj del equipo"));
        return Optional.empty();
    }
    private void validarFamiliar(UsuarioPrincipal p) {
        if (p.rol() != RolUsuario.PADRE && p.rol() != RolUsuario.TUTOR) throw new ForbiddenException("Rol no autorizado");
    }
    private String nuevoCodigo() { char[] value = new char[8]; for (int i=0;i<value.length;i++) value[i]=ALFABETO[random.nextInt(ALFABETO.length)]; return new String(value); }
    private String nuevoToken() { byte[] value = new byte[32]; random.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String clave(String nombre, String ruta) { return (limpiar(ruta) == null ? "nombre:" + nombre : "ruta:" + ruta).toLowerCase(Locale.ROOT); }
    private static String limpiar(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
