package com.familyguard.familyguard.service;

import static com.familyguard.familyguard.api.dto.OperacionMasivaDtos.*;

import com.familyguard.familyguard.domain.model.Aplicacion;
import com.familyguard.familyguard.domain.model.Dispositivo;
import com.familyguard.familyguard.domain.model.EstadoDispositivo;
import com.familyguard.familyguard.domain.model.Familia;
import com.familyguard.familyguard.domain.model.LimiteTiempo;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.model.OperacionMasiva;
import com.familyguard.familyguard.domain.model.OperacionMasivaDispositivo;
import com.familyguard.familyguard.domain.model.PeriodoLimite;
import com.familyguard.familyguard.domain.model.RolUsuario;
import com.familyguard.familyguard.domain.model.SitioBloqueado;
import com.familyguard.familyguard.domain.model.TipoReglaSitio;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.HorarioSitioRepository;
import com.familyguard.familyguard.domain.repository.LimiteTiempoRepository;
import com.familyguard.familyguard.domain.repository.OperacionMasivaDispositivoRepository;
import com.familyguard.familyguard.domain.repository.OperacionMasivaRepository;
import com.familyguard.familyguard.domain.repository.SitioBloqueadoRepository;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.exception.ConflictException;
import com.familyguard.familyguard.service.exception.ForbiddenException;
import com.familyguard.familyguard.service.exception.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restricciones masivas (Etapa 4).
 *
 * <p>Reglas de seguridad y consistencia:
 * <ul>
 *   <li>Todos los equipos se validan en el backend contra la familia de la sesión antes de modificar nada; si
 *   uno no pertenece a la familia o no está autorizado, se rechaza la operación completa.</li>
 *   <li>Las operaciones de una familia se serializan con un bloqueo de fila sobre la familia; el identificador
 *   de operación es único por familia: un reintento con la misma solicitud devuelve el resultado guardado sin
 *   volver a modificar ni a incrementar revisiones; con otra solicitud responde 409.</li>
 *   <li>Solo se añaden o retiran los equipos indicados: nunca se reemplazan asignaciones ni se borran otras
 *   reglas. Un equipo que ya tenía la restricción queda «Sin cambios», sin incrementar su revisión.</li>
 *   <li>El estado de cada equipo se calcula con datos reales (heartbeat, descarga, confirmaciones y eventos del
 *   Agente); nunca se marca «Aplicada» por adelantado.</li>
 * </ul>
 */
@Service
public class OperacionMasivaService {
    public static final String BLOQUEAR_SITIO = "BLOQUEAR_SITIO", RETIRAR_SITIO = "RETIRAR_SITIO",
            LIMITAR_APLICACION = "LIMITAR_APLICACION", RETIRAR_LIMITE = "RETIRAR_LIMITE";
    static final Set<String> TIPOS = Set.of(BLOQUEAR_SITIO, RETIRAR_SITIO, LIMITAR_APLICACION, RETIRAR_LIMITE);
    // Estados vivos por equipo.
    public static final String PENDIENTE_ENTREGA = "PENDIENTE_ENTREGA", DESCONECTADO = "DESCONECTADO",
            RECIBIDA = "RECIBIDA", APLICADA = "APLICADA", ERROR = "ERROR", REVOCADO = "REVOCADO", RETIRADA = "RETIRADA";
    private static final String MOTIVO = "Restricción masiva";

    private final FamiliaRepository familias;
    private final DispositivoRepository dispositivos;
    private final SitioBloqueadoRepository sitios;
    private final HorarioSitioRepository horariosSitio;
    private final AplicacionRepository aplicaciones;
    private final LimiteTiempoRepository limites;
    private final RestriccionService restricciones;
    private final OperacionMasivaRepository operaciones;
    private final OperacionMasivaDispositivoRepository resultados;
    private final JdbcTemplate jdbc;
    private final AuditoriaAdministrativa auditoria;

    public OperacionMasivaService(FamiliaRepository familias, DispositivoRepository dispositivos,
                                  SitioBloqueadoRepository sitios, HorarioSitioRepository horariosSitio,
                                  AplicacionRepository aplicaciones, LimiteTiempoRepository limites,
                                  RestriccionService restricciones, OperacionMasivaRepository operaciones,
                                  OperacionMasivaDispositivoRepository resultados, JdbcTemplate jdbc,
                                  AuditoriaAdministrativa auditoria) {
        this.auditoria = auditoria;
        this.familias = familias; this.dispositivos = dispositivos; this.sitios = sitios;
        this.horariosSitio = horariosSitio; this.aplicaciones = aplicaciones; this.limites = limites;
        this.restricciones = restricciones; this.operaciones = operaciones; this.resultados = resultados;
        this.jdbc = jdbc;
    }

    /** Resultado de registrar una operación: {@code nueva=false} si fue un reintento idempotente. */
    public record Ejecucion(OperacionResponse operacion, boolean nueva) { }

    private record Paso(Dispositivo dispositivo, String resultado, String detalle, SitioBloqueado sitio,
                        Aplicacion aplicacion, LimiteTiempo limite) { }

    private record Plan(String tipo, String objetivo, String parametros, List<Paso> pasos) { }

    // ------------------------------------------------------------------ API pública

    /** Vista previa sin modificar nada: qué ocurrirá en cada equipo y cuántos están conectados. */
    @Transactional(readOnly = true)
    public PreviewResponse previsualizar(UsuarioPrincipal principal, Request request) {
        validarRol(principal);
        Plan plan = planificar(principal.familiaId(), request);
        List<PreviewItem> items = plan.pasos().stream().map(p -> new PreviewItem(p.dispositivo().getId(),
                p.dispositivo().getNombre(), nombreMenor(p.dispositivo()), DashboardService.estaConectado(p.dispositivo()),
                p.resultado(), p.detalle())).toList();
        int conectados = (int) items.stream().filter(PreviewItem::conectado).count();
        int programados = contar(plan, OperacionMasivaDispositivo.PROGRAMADA);
        int sinCambios = contar(plan, OperacionMasivaDispositivo.SIN_CAMBIOS);
        int noAplicables = items.size() - programados - sinCambios;
        return new PreviewResponse(plan.tipo(), plan.objetivo(), items.size(), conectados, items.size() - conectados,
                programados, sinCambios, noAplicables,
                mensajeConfirmacion(plan.tipo(), plan.objetivo(), plan.parametros(), items.size(), conectados,
                        sinCambios, noAplicables), items);
    }

    @Transactional
    public Ejecucion ejecutar(UsuarioPrincipal principal, Request request) {
        validarRol(principal);
        // Primera sentencia de la transacción: serializa reintentos y solicitudes simultáneas de la familia.
        Familia familia = familias.bloquearParaOperacion(principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Familia no encontrada"));
        String tipo = tipo(request.tipo());
        String huella = huella(tipo, request);
        Optional<OperacionMasiva> previa = operaciones.findByFamilia_IdAndOperacionUuid(familia.getId(),
                request.operacionId().toString());
        if (previa.isPresent()) {
            if (!previa.get().getHuellaSolicitud().equals(huella))
                throw new ConflictException("El identificador de operación ya se usó con otra solicitud");
            return new Ejecucion(respuesta(previa.get(), true), false);
        }
        Plan plan = planificar(familia.getId(), request);
        OperacionMasiva operacion = operaciones.save(new OperacionMasiva(familia, principal.usuarioId(),
                request.operacionId().toString(), huella, tipo, plan.objetivo(), plan.parametros(), plan.pasos().size()));
        List<OperacionMasivaDispositivo> filas = switch (tipo) {
            case BLOQUEAR_SITIO -> bloquearSitio(operacion, familia, plan);
            case RETIRAR_SITIO -> retirarSitio(operacion, plan);
            case LIMITAR_APLICACION -> limitarAplicacion(operacion, familia, plan, request.minutosDiarios());
            default -> retirarLimite(operacion, principal, plan);
        };
        resultados.saveAll(filas);
        resultados.flush();
        auditoria.registrar(principal, "OPERACION_MASIVA_REGISTRADA", "OPERACION_MASIVA", operacion.getId(), null,
                tipo + " " + plan.objetivo() + " en " + plan.pasos().size() + " equipos ("
                        + contar(plan, OperacionMasivaDispositivo.PROGRAMADA) + " programados)");
        return new Ejecucion(respuesta(operacion, true), true);
    }

    @Transactional(readOnly = true)
    public OperacionResponse obtener(UsuarioPrincipal principal, Long id) {
        validarRol(principal);
        return respuesta(buscar(principal.familiaId(), id), true);
    }

    @Transactional(readOnly = true)
    public List<OperacionResponse> listar(UsuarioPrincipal principal) {
        validarRol(principal);
        return operaciones.findTop50ByFamilia_IdOrderByCreadoEnDescIdDesc(principal.familiaId()).stream()
                .map(o -> respuesta(o, false)).toList();
    }

    /**
     * Cancela un bloqueo masivo de sitio en los equipos que aún no lo aplicaron. Los equipos donde ya se aplicó
     * lo conservan (para quitarlo se usa «Retirar restricción»). Repetir la cancelación no vuelve a modificar nada.
     */
    @Transactional
    public OperacionResponse cancelar(UsuarioPrincipal principal, Long id) {
        validarRol(principal);
        familias.bloquearParaOperacion(principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Familia no encontrada"));
        OperacionMasiva operacion = buscar(principal.familiaId(), id);
        if (OperacionMasiva.CANCELADA.equals(operacion.getEstado())) return respuesta(operacion, true);
        if (!BLOQUEAR_SITIO.equals(operacion.getTipo()))
            throw new ConflictException("Solo se pueden cancelar bloqueos de sitios; use «Retirar restricción»");
        for (OperacionMasivaDispositivo fila : filas(operacion)) {
            if (!OperacionMasivaDispositivo.PROGRAMADA.equals(fila.getResultado())) continue;
            String estado = estadoVivo(operacion, fila)[0];
            if (APLICADA.equals(estado) || RETIRADA.equals(estado)) continue;
            SitioBloqueado sitio = sitios.findByIdAndFamilia_Id(fila.getSitioId(), principal.familiaId()).orElse(null);
            Dispositivo d = fila.getDispositivo();
            if (sitio != null && quitarAsignacion(sitio, d)) {
                d.incrementarRevisionDeseada();
                if (asignaciones(sitio) == 0 && sitio.isActivo()) { sitio.setActivo(false); sitio.incrementarRevision(); }
                sitios.saveAndFlush(sitio);
            }
            fila.cancelar("Cancelada antes de aplicarse en el equipo");
        }
        operacion.cancelar(Instant.now());
        resultados.flush();
        auditoria.registrar(principal, "OPERACION_MASIVA_CANCELADA", "OPERACION_MASIVA", operacion.getId(), null,
                operacion.getTipo() + " " + operacion.getObjetivo());
        return respuesta(operacion, true);
    }

    // ------------------------------------------------------------------ Planificación (sin efectos)

    private Plan planificar(Long familiaId, Request request) {
        String tipo = tipo(request.tipo());
        List<Dispositivo> equipos = validarDispositivos(familiaId, request.dispositivoIds());
        return switch (tipo) {
            case BLOQUEAR_SITIO, RETIRAR_SITIO -> planSitio(familiaId, tipo, request, equipos);
            default -> planAplicacion(familiaId, tipo, request, equipos);
        };
    }

    /** Valida TODOS los equipos antes de modificar nada. Un equipo de otra familia responde 404 (sin revelar que existe). */
    private List<Dispositivo> validarDispositivos(Long familiaId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new ConflictException("Seleccione al menos un dispositivo");
        Set<Long> unicos = new LinkedHashSet<>(ids);
        if (unicos.size() > MAX_DISPOSITIVOS)
            throw new ConflictException("Una operación admite como máximo " + MAX_DISPOSITIVOS + " dispositivos");
        List<Dispositivo> equipos = new ArrayList<>();
        for (Long id : unicos) {
            Dispositivo d = dispositivos.findByFamilia_IdAndId(familiaId, id).filter(Dispositivo::isActivo)
                    .orElseThrow(() -> new ResourceNotFoundException("Dispositivo no encontrado"));
            if (d.getEstado() != EstadoDispositivo.AUTORIZADO || d.isAgenteRevocado() || d.getMenor() == null)
                throw new ConflictException("El dispositivo «" + d.getNombre()
                        + "» no está autorizado (revocado o sin vincular); quítelo de la selección");
            equipos.add(d);
        }
        return equipos;
    }

    private Plan planSitio(Long familiaId, String tipo, Request request, List<Dispositivo> equipos) {
        if (request.dominio() == null || request.dominio().isBlank()) throw new ConflictException("Indique el dominio");
        String dominio = SitioService.normalize(request.dominio());
        Map<Long, Optional<SitioBloqueado>> porHijo = new LinkedHashMap<>();
        for (Dispositivo d : equipos)
            porHijo.computeIfAbsent(d.getMenor().getId(),
                    menor -> sitios.findByFamilia_IdAndMenor_IdAndDominio(familiaId, menor, dominio));
        if (BLOQUEAR_SITIO.equals(tipo)) {
            // Una regla activa con horario, vigencia o modo «permitir» no se altera en silencio: se rechaza todo.
            for (Map.Entry<Long, Optional<SitioBloqueado>> e : porHijo.entrySet()) {
                SitioBloqueado s = e.getValue().orElse(null);
                if (s != null && s.isActivo() && (s.getModo() != TipoReglaSitio.BLOQUEAR || !s.isPermanente()
                        || !horariosSitio.findAllBySitio_IdAndActivoTrue(s.getId()).isEmpty()))
                    throw new ConflictException("La regla de " + dominio + " para " + s.getMenor().getNombre()
                            + " tiene horario, vigencia o modo permitir; modifíquela desde «Sitios web»");
            }
        }
        List<Paso> pasos = new ArrayList<>();
        for (Dispositivo d : equipos) {
            SitioBloqueado s = porHijo.get(d.getMenor().getId()).orElse(null);
            boolean asignado = s != null && s.isActivo() && s.getDispositivos().stream().anyMatch(x -> x.getId().equals(d.getId()));
            if (BLOQUEAR_SITIO.equals(tipo))
                pasos.add(asignado ? new Paso(d, OperacionMasivaDispositivo.SIN_CAMBIOS, "El equipo ya tenía este bloqueo", s, null, null)
                        : new Paso(d, OperacionMasivaDispositivo.PROGRAMADA, null, s, null, null));
            else
                pasos.add(asignado ? new Paso(d, OperacionMasivaDispositivo.PROGRAMADA, null, s, null, null)
                        : new Paso(d, OperacionMasivaDispositivo.SIN_CAMBIOS, "El equipo no tenía este bloqueo", s, null, null));
        }
        return new Plan(tipo, dominio, null, pasos);
    }

    /**
     * Identificación segura de aplicaciones: el padre elige una aplicación detectada como referencia y en cada
     * equipo se busca una aplicación detectada por el Agente con el mismo nombre de ejecutable. Si no existe,
     * o hay varias rutas distintas, no se crea ninguna regla en ese equipo: nunca se inventan rutas.
     */
    private Plan planAplicacion(Long familiaId, String tipo, Request request, List<Dispositivo> equipos) {
        if (request.aplicacionId() == null) throw new ConflictException("Seleccione la aplicación de referencia");
        Aplicacion referencia = aplicaciones.findById(request.aplicacionId())
                .filter(a -> a.getFamilia().getId().equals(familiaId) && a.isActivo())
                .orElseThrow(() -> new ResourceNotFoundException("Aplicación no encontrada"));
        String ejecutable = ejecutable(referencia.getRutaEjecutable());
        if (ejecutable.isBlank()) throw new ConflictException("La aplicación de referencia no tiene un ejecutable verificable");
        Integer minutos = request.minutosDiarios();
        if (LIMITAR_APLICACION.equals(tipo) && (minutos == null || minutos < 1 || minutos > 1440))
            throw new ConflictException("Indique un límite diario entre 1 y 1440 minutos");
        List<Paso> pasos = new ArrayList<>();
        for (Dispositivo d : equipos) {
            List<Aplicacion> candidatas = aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(familiaId, d.getId())
                    .stream().filter(a -> ejecutable.equals(ejecutable(a.getRutaEjecutable()))).toList();
            if (candidatas.isEmpty()) {
                pasos.add(new Paso(d, OperacionMasivaDispositivo.APLICACION_NO_REGISTRADA, ejecutable
                        + " no fue detectado en este equipo. Abra la aplicación una vez en el equipo o espere el próximo"
                        + " inventario y vuelva a aplicar la operación.", null, null, null));
                continue;
            }
            if (candidatas.size() > 1) {
                pasos.add(new Paso(d, OperacionMasivaDispositivo.APLICACION_AMBIGUA, "Hay " + candidatas.size()
                        + " ejecutables " + ejecutable + " con rutas distintas; configure el límite desde «Aplicaciones»"
                        + " eligiendo la ruta correcta.", null, null, null));
                continue;
            }
            Aplicacion app = candidatas.get(0);
            LimiteTiempo limite = limites.findByFamilia_IdAndMenor_IdAndAplicacion_IdAndPeriodo(familiaId,
                    d.getMenor().getId(), app.getId(), PeriodoLimite.DIARIO).orElse(null);
            boolean activo = limite != null && limite.isActivo();
            if (RETIRAR_LIMITE.equals(tipo)) {
                pasos.add(activo ? new Paso(d, OperacionMasivaDispositivo.PROGRAMADA, null, null, app, limite)
                        : new Paso(d, OperacionMasivaDispositivo.SIN_CAMBIOS, "El equipo no tenía límite para "
                        + ejecutable, null, app, limite));
            } else if (activo && limite.isBloqueoTotal()) {
                pasos.add(new Paso(d, OperacionMasivaDispositivo.SIN_CAMBIOS,
                        "La aplicación ya está bloqueada por completo; no se reduce la restricción", null, app, limite));
            } else if (activo && limite.getMinutosPermitidos() == minutos) {
                pasos.add(new Paso(d, OperacionMasivaDispositivo.SIN_CAMBIOS, "El equipo ya tenía este límite", null, app, limite));
            } else {
                pasos.add(new Paso(d, OperacionMasivaDispositivo.PROGRAMADA, activo
                        ? "Límite actualizado de " + duracion(limite.getMinutosPermitidos()) + " a " + duracion(minutos)
                        : "Límite nuevo de " + duracion(minutos), null, app, limite));
            }
        }
        return new Plan(tipo, ejecutable, LIMITAR_APLICACION.equals(tipo) ? "minutosDiarios=" + minutos : null, pasos);
    }

    // ------------------------------------------------------------------ Ejecución

    private List<OperacionMasivaDispositivo> bloquearSitio(OperacionMasiva op, Familia familia, Plan plan) {
        Map<Long, List<Paso>> porHijo = new LinkedHashMap<>();
        plan.pasos().forEach(p -> porHijo.computeIfAbsent(p.dispositivo().getMenor().getId(), k -> new ArrayList<>()).add(p));
        List<OperacionMasivaDispositivo> filas = new ArrayList<>();
        for (List<Paso> pasos : porHijo.values()) {
            List<Paso> nuevos = pasos.stream().filter(p -> OperacionMasivaDispositivo.PROGRAMADA.equals(p.resultado())).toList();
            SitioBloqueado sitio = pasos.get(0).sitio();
            if (!nuevos.isEmpty()) {
                Menor menor = nuevos.get(0).dispositivo().getMenor();
                if (sitio == null) {
                    sitio = new SitioBloqueado(familia, menor, plan.objetivo());
                    configurarBloqueo(sitio);
                    for (Paso p : nuevos) agregar(sitio, p);
                    sitio.incrementarRevision();
                    sitio = sitios.saveAndFlush(sitio);
                } else if (!sitio.isActivo()) {
                    // Reactivación: la regla vuelve a ser un bloqueo permanente solo en los equipos elegidos.
                    horariosSitio.deleteAll(horariosSitio.findAllBySitio_Id(sitio.getId()));
                    horariosSitio.flush();
                    configurarBloqueo(sitio);
                    sitio.getDispositivos().clear();
                    for (Paso p : nuevos) agregar(sitio, p);
                    sitio.incrementarRevision();
                    sitio = sitios.saveAndFlush(sitio);
                    jdbc.update("update sitios_dispositivos set estado_aplicacion='PENDIENTE',detalle_aplicacion=null,"
                            + "aplicado_en=null,revision_aplicada=null where sitio_id=?", sitio.getId());
                } else {
                    // Regla activa: solo se añaden equipos; los que ya la tenían conservan su estado y revisión.
                    for (Paso p : nuevos) agregar(sitio, p);
                    sitio = sitios.saveAndFlush(sitio);
                }
                nuevos.forEach(p -> p.dispositivo().incrementarRevisionDeseada());
            }
            for (Paso p : pasos)
                filas.add(new OperacionMasivaDispositivo(op, p.dispositivo(), sitio == null ? null : sitio.getId(), null,
                        sitio == null ? null : sitio.getRevision(), p.resultado(), p.detalle()));
        }
        return filas;
    }

    private static void configurarBloqueo(SitioBloqueado sitio) {
        sitio.setIncluirSubdominios(true);
        sitio.setModo(TipoReglaSitio.BLOQUEAR);
        sitio.setPermanente(true);
        sitio.setVigenteDesde(null);
        sitio.setVigenteHasta(null);
        if (sitio.getMotivo() == null || sitio.getMotivo().isBlank()) sitio.setMotivo(MOTIVO);
        sitio.setActivo(true);
    }

    private static void agregar(SitioBloqueado sitio, Paso p) { sitio.getDispositivos().add(p.dispositivo()); }

    private List<OperacionMasivaDispositivo> retirarSitio(OperacionMasiva op, Plan plan) {
        List<OperacionMasivaDispositivo> filas = new ArrayList<>();
        Set<SitioBloqueado> modificados = new LinkedHashSet<>();
        for (Paso p : plan.pasos()) {
            Dispositivo d = p.dispositivo();
            if (OperacionMasivaDispositivo.PROGRAMADA.equals(p.resultado())) {
                // Solo se quita este equipo; la regla sigue vigente en los demás.
                if (quitarAsignacion(p.sitio(), d)) d.incrementarRevisionDeseada();
                modificados.add(p.sitio());
            }
            filas.add(new OperacionMasivaDispositivo(op, d, p.sitio() == null ? null : p.sitio().getId(), null,
                    OperacionMasivaDispositivo.PROGRAMADA.equals(p.resultado()) ? d.getRevisionDeseada() : null,
                    p.resultado(), p.detalle()));
        }
        for (SitioBloqueado s : modificados) {
            if (asignaciones(s) == 0) { s.setActivo(false); s.incrementarRevision(); }
            sitios.saveAndFlush(s);
        }
        return filas;
    }

    /**
     * Quita solo la asociación regla–equipo. Con Hibernate 7, eliminar un elemento suelto de
     * {@code SitioBloqueado.dispositivos} falla («Unable to locate parameter sitios_dispositivos.dispositivo_id»)
     * porque la unión al dispositivo usa dos columnas (dispositivo_id, familia_id) y una de ellas no es la
     * clave primaria. Por eso se borra la fila exacta con SQL explícito, limitado a la regla, el equipo y la
     * familia, sin modificar la colección JPA (que queda sin cambios y no genera SQL al confirmar).
     */
    private boolean quitarAsignacion(SitioBloqueado sitio, Dispositivo d) {
        return jdbc.update("delete from sitios_dispositivos where sitio_id = ? and dispositivo_id = ? and familia_id = ?",
                sitio.getId(), d.getId(), sitio.getFamilia().getId()) > 0;
    }

    private int asignaciones(SitioBloqueado sitio) {
        Integer total = jdbc.queryForObject("select count(*) from sitios_dispositivos where sitio_id = ? and familia_id = ?",
                Integer.class, sitio.getId(), sitio.getFamilia().getId());
        return total == null ? 0 : total;
    }

    private List<OperacionMasivaDispositivo> limitarAplicacion(OperacionMasiva op, Familia familia, Plan plan, Integer minutos) {
        List<OperacionMasivaDispositivo> filas = new ArrayList<>();
        for (Paso p : plan.pasos()) {
            Dispositivo d = p.dispositivo();
            Long revision = null;
            if (OperacionMasivaDispositivo.PROGRAMADA.equals(p.resultado())) {
                // Se conservan los horarios y la advertencia de un límite existente; solo cambian los minutos.
                LimiteTiempo limite = p.limite() != null ? p.limite()
                        : new LimiteTiempo(familia, d.getMenor(), p.aplicacion(), PeriodoLimite.DIARIO, minutos);
                limite.setMinutosPermitidos(minutos);
                limite.setActivo(true);
                limite.incrementarRevision();
                limites.save(limite);
                d.incrementarRevisionDeseada();
                revision = d.getRevisionDeseada();
            }
            filas.add(new OperacionMasivaDispositivo(op, d, null, p.aplicacion() == null ? null : p.aplicacion().getId(),
                    revision, p.resultado(), p.detalle()));
        }
        limites.flush();
        return filas;
    }

    private List<OperacionMasivaDispositivo> retirarLimite(OperacionMasiva op, UsuarioPrincipal principal, Plan plan) {
        List<OperacionMasivaDispositivo> filas = new ArrayList<>();
        for (Paso p : plan.pasos()) {
            Dispositivo d = p.dispositivo();
            Long revision = null;
            if (OperacionMasivaDispositivo.PROGRAMADA.equals(p.resultado())) {
                // Mismo retiro que la pantalla «Aplicaciones»: elimina el límite y sus horarios y queda auditado.
                restricciones.desactivar(principal, p.limite().getId());
                revision = d.getRevisionDeseada();
            }
            filas.add(new OperacionMasivaDispositivo(op, d, null, p.aplicacion() == null ? null : p.aplicacion().getId(),
                    revision, p.resultado(), p.detalle()));
        }
        return filas;
    }

    // ------------------------------------------------------------------ Estados verificables

    private OperacionResponse respuesta(OperacionMasiva op, boolean conDetalle) {
        List<ResultadoDispositivo> items = new ArrayList<>();
        Map<String, Integer> resumen = new LinkedHashMap<>();
        for (OperacionMasivaDispositivo fila : filas(op)) {
            Dispositivo d = fila.getDispositivo();
            String[] estado = estadoVivo(op, fila);
            resumen.merge(estado[0], 1, Integer::sum);
            items.add(new ResultadoDispositivo(d.getId(), d.getNombre(), nombreMenor(d), DashboardService.estaConectado(d),
                    fila.getResultado(), estado[0], estado[1], fila.getRevisionObjetivo()));
        }
        int total = items.size();
        int listos = resumen.getOrDefault(APLICADA, 0) + resumen.getOrDefault(OperacionMasivaDispositivo.SIN_CAMBIOS, 0);
        boolean completa = total > 0 && listos == total;
        return new OperacionResponse(op.getId(), UUID.fromString(op.getOperacionUuid()), op.getTipo(), op.getObjetivo(),
                op.getParametros(), op.getEstado(), op.getCreadoEn(), op.getCanceladaEn(), total, resumen, completa,
                mensajeResumen(resumen, total, completa), conDetalle ? items : List.of());
    }

    /** [estado, detalle]. Nunca se presenta como aplicada una operación sin confirmación real del Agente. */
    private String[] estadoVivo(OperacionMasiva op, OperacionMasivaDispositivo fila) {
        if (!OperacionMasivaDispositivo.PROGRAMADA.equals(fila.getResultado()))
            return new String[] {fila.getResultado(), fila.getDetalle()};
        Dispositivo d = fila.getDispositivo();
        boolean conectado = DashboardService.estaConectado(d);
        boolean revocado = d.isAgenteRevocado() || d.getEstado() != EstadoDispositivo.AUTORIZADO;
        String[] estado = BLOQUEAR_SITIO.equals(op.getTipo()) ? estadoBloqueoWeb(op, fila, conectado) : estadoRevision(fila, conectado);
        if (revocado && !APLICADA.equals(estado[0]) && !RETIRADA.equals(estado[0]))
            return new String[] {REVOCADO, "El equipo fue revocado; recibirá la política si se vuelve a vincular"};
        return estado;
    }

    private String[] estadoBloqueoWeb(OperacionMasiva op, OperacionMasivaDispositivo fila, boolean conectado) {
        List<Object[]> filas = jdbc.query("select sd.estado_aplicacion, sd.detalle_aplicacion, sd.sincronizado_en, s.activo"
                        + " from sitios_dispositivos sd join sitios_bloqueados s on s.id = sd.sitio_id"
                        + " where sd.sitio_id = ? and sd.dispositivo_id = ? and sd.familia_id = ?",
                (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getTimestamp(3), rs.getBoolean(4)},
                fila.getSitioId(), fila.getDispositivo().getId(), fila.getFamiliaId());
        if (filas.isEmpty() || !(Boolean) filas.get(0)[3])
            return new String[] {RETIRADA, "El bloqueo se retiró de este equipo después de la operación"};
        Object[] f = filas.get(0);
        String estado = (String) f[0], detalle = (String) f[1];
        java.sql.Timestamp descarga = (java.sql.Timestamp) f[2];
        if (SitioService.APLICADO.equals(estado)) return new String[] {APLICADA, detalle};
        if (SitioService.ERROR.equals(estado)) return new String[] {ERROR, detalle};
        if (descarga != null && !descarga.toInstant().isBefore(op.getCreadoEn()))
            return new String[] {RECIBIDA, detalle == null ? "El Agente descargó la regla; falta su confirmación" : detalle};
        return conectado ? new String[] {PENDIENTE_ENTREGA, "El equipo está conectado; recibirá la regla en su próximo ciclo"}
                : new String[] {DESCONECTADO, "Desconectado; recibirá la regla al volver a comunicarse"};
    }

    private String[] estadoRevision(OperacionMasivaDispositivo fila, boolean conectado) {
        Dispositivo d = fila.getDispositivo();
        long objetivo = fila.getRevisionObjetivo() == null ? 0 : fila.getRevisionObjetivo();
        List<String[]> confirmacion = jdbc.query("select estado, detalle from confirmaciones_sincronizacion"
                        + " where familia_id = ? and dispositivo_id = ? and revision >= ? order by confirmado_en desc, id desc limit 1",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, fila.getFamiliaId(), d.getId(), objetivo);
        String detalle = confirmacion.isEmpty() ? null : confirmacion.get(0)[1];
        if (d.getRevisionAplicada() >= objetivo) return new String[] {APLICADA, detalle};
        if (!confirmacion.isEmpty() && "FALLIDA".equals(confirmacion.get(0)[0])) return new String[] {ERROR, detalle};
        if (d.getRevisionRecibida() >= objetivo)
            return new String[] {RECIBIDA, "El Agente descargó la política; falta su confirmación de aplicación"};
        return conectado ? new String[] {PENDIENTE_ENTREGA, "El equipo está conectado; recibirá la política en su próximo ciclo"}
                : new String[] {DESCONECTADO, "Desconectado; recibirá la política al volver a comunicarse"};
    }

    // ------------------------------------------------------------------ Utilidades

    private OperacionMasiva buscar(Long familiaId, Long id) {
        return operaciones.findByIdAndFamilia_Id(id, familiaId)
                .orElseThrow(() -> new ResourceNotFoundException("Operación no encontrada"));
    }

    private List<OperacionMasivaDispositivo> filas(OperacionMasiva op) {
        return resultados.findAllByOperacion_IdAndFamiliaIdOrderByIdAsc(op.getId(), op.getFamilia().getId());
    }

    private static String tipo(String valor) {
        String tipo = valor == null ? "" : valor.strip().toUpperCase(Locale.ROOT);
        if (!TIPOS.contains(tipo)) throw new ConflictException("Tipo de operación no válido");
        return tipo;
    }

    /** Huella de la solicitud normalizada: detecta un mismo identificador reutilizado con otro contenido. */
    static String huella(String tipo, Request r) {
        String objetivo = BLOQUEAR_SITIO.equals(tipo) || RETIRAR_SITIO.equals(tipo)
                ? (r.dominio() == null ? "" : SitioService.normalize(r.dominio())) : "app:" + r.aplicacionId();
        List<Long> ids = r.dispositivoIds().stream().filter(Objects::nonNull).distinct().sorted().toList();
        String texto = tipo + "|" + objetivo + "|" + (LIMITAR_APLICACION.equals(tipo) ? r.minutosDiarios() : "") + "|" + ids;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Nombre del ejecutable en minúsculas (C:\...\RobloxPlayerBeta.exe → robloxplayerbeta.exe). */
    static String ejecutable(String ruta) {
        if (ruta == null) return "";
        String limpio = ruta.strip();
        int corte = Math.max(limpio.lastIndexOf('\\'), limpio.lastIndexOf('/'));
        return limpio.substring(corte + 1).toLowerCase(Locale.ROOT);
    }

    static String duracion(int minutos) {
        int h = minutos / 60, m = minutos % 60;
        return h == 0 ? m + " min" : m == 0 ? h + " h" : h + " h " + m + " min";
    }

    static String mensajeConfirmacion(String tipo, String objetivo, String parametros, int total, int conectados,
                                      int sinCambios, int noAplicables) {
        String accion = switch (tipo) {
            case BLOQUEAR_SITIO -> "bloquear " + objetivo;
            case RETIRAR_SITIO -> "retirar el bloqueo de " + objetivo;
            case LIMITAR_APLICACION -> "limitar " + objetivo + " a "
                    + duracion(Integer.parseInt(parametros.substring(parametros.indexOf('=') + 1))) + " diarios";
            default -> "retirar el límite de " + objetivo;
        };
        int desconectados = total - conectados;
        StringBuilder texto = new StringBuilder("Vas a ").append(accion).append(" en ").append(total)
                .append(total == 1 ? " computadora" : " computadoras").append(" (").append(conectados)
                .append(conectados == 1 ? " conectada, " : " conectadas, ").append(desconectados)
                .append(desconectados == 1 ? " desconectada)." : " desconectadas).");
        if (desconectados > 0) texto.append(" Las desconectadas quedarán pendientes hasta que vuelvan a comunicarse.");
        if (sinCambios > 0) texto.append(" ").append(sinCambios).append(sinCambios == 1 ? " ya estaba así." : " ya estaban así.");
        if (noAplicables > 0) texto.append(" En ").append(noAplicables)
                .append(noAplicables == 1 ? " no se puede aplicar (consulte el detalle)." : " no se puede aplicar (consulte el detalle).");
        return texto.append(" ¿Deseas continuar?").toString();
    }

    static String mensajeResumen(Map<String, Integer> r, int total, boolean completa) {
        if (completa) return "Aplicada en los " + total + " equipos seleccionados.";
        int aplicadas = r.getOrDefault(APLICADA, 0) + r.getOrDefault(OperacionMasivaDispositivo.SIN_CAMBIOS, 0);
        int pendientes = r.getOrDefault(PENDIENTE_ENTREGA, 0) + r.getOrDefault(DESCONECTADO, 0) + r.getOrDefault(RECIBIDA, 0);
        int errores = r.getOrDefault(ERROR, 0);
        int canceladas = r.getOrDefault(OperacionMasivaDispositivo.CANCELADA, 0);
        int otros = total - aplicadas - pendientes - errores - canceladas;
        StringBuilder texto = new StringBuilder("Aplicada en ").append(aplicadas).append(" de ").append(total).append(" equipos");
        if (pendientes > 0) texto.append("; ").append(pendientes).append(" pendientes");
        if (errores > 0) texto.append("; ").append(errores).append(" con error");
        if (canceladas > 0) texto.append("; ").append(canceladas).append(" canceladas");
        if (otros > 0) texto.append("; ").append(otros).append(" sin aplicar (no registrada, ambigua, revocado o retirada)");
        return texto.append(".").toString();
    }

    private static int contar(Plan plan, String resultado) {
        return (int) plan.pasos().stream().filter(p -> resultado.equals(p.resultado())).count();
    }

    private static String nombreMenor(Dispositivo d) { return d.getMenor() == null ? "Sin asignar" : d.getMenor().getNombre(); }

    private static void validarRol(UsuarioPrincipal principal) {
        if (principal.rol() != RolUsuario.PADRE && principal.rol() != RolUsuario.TUTOR)
            throw new ForbiddenException("Rol no autorizado");
    }
}
