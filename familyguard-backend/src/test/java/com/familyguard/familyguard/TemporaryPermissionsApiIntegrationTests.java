package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.familyguard.familyguard.service.AlertasConexionService;
import com.familyguard.familyguard.service.PinCifrado;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Entrega final: permisos temporales con caducidad, PIN parental, autorizaciones de pausa/desinstalación y
 * avisos de conexión. H2 (perfil test), nunca la base MySQL real.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class TemporaryPermissionsApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    private static final String MINECRAFT = "C:\\\\Juegos\\\\Minecraft\\\\minecraft.exe";
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired DispositivoRepository dispositivos;
    @Autowired AplicacionRepository aplicaciones;
    @Autowired AlertasConexionService alertas;
    @Autowired JdbcTemplate jdbc;

    @Test
    void tiempoExtraEnVariosEquiposVenceAlTerminarElDiaYSoloLlegaAlAgenteMientrasEsVigente() throws Exception {
        Familia f = familia("Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz"), pc = vincular(f, "Luz", "PC Luz");
        inventario(laptop, MINECRAFT); inventario(pc, MINECRAFT);
        long revisionAntes = dispositivos.findById(pc.id()).orElseThrow().getRevisionDeseada();

        String r = otorgar(f, tiempoExtra(UUID.randomUUID(), f.hijo("Luz"), app(laptop), 30, laptop, pc))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("TIEMPO_EXTRA"))
                .andExpect(jsonPath("$.minutosExtra").value(30))
                .andExpect(jsonPath("$.estado").value("ACTIVO"))
                .andExpect(jsonPath("$.equipos.length()").value(2))
                .andExpect(jsonPath("$.equipos[0].estado").value("DESCONECTADO"))
                .andReturn().getResponse().getContentAsString();
        // «Tiempo extra» vence a la medianoche de la familia, nunca después.
        ZoneId zona = ZoneId.of(jdbc.queryForObject("select zona_horaria from familias where id=?", String.class, f.familiaId()));
        Instant finDelDia = LocalDate.now(zona).plusDays(1).atStartOfDay(zona).toInstant();
        assertThat(Instant.parse(JsonPath.read(r, "$.expiraEn"))).isBeforeOrEqualTo(finDelDia).isAfter(Instant.now());
        assertThat(dispositivos.findById(pc.id()).orElseThrow().getRevisionDeseada()).isEqualTo(revisionAntes + 1);

        String agente = permisosAgente(pc).andExpect(jsonPath("$.permisos.length()").value(1))
                .andExpect(jsonPath("$.permisos[0].tipo").value("TIEMPO_EXTRA"))
                .andExpect(jsonPath("$.permisos[0].minutosExtra").value(30))
                .andExpect(jsonPath("$.servidorEn").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(agente, "$.permisos[0].rutaEjecutable")).endsWith("minecraft.exe");

        // Caducado: el Agente ya no lo recibe y el padre lo ve vencido.
        long permiso = id(r);
        vencer(permiso);
        permisosAgente(pc).andExpect(jsonPath("$.permisos.length()").value(0));
        consultar(f, permiso).andExpect(jsonPath("$.estado").value("EXPIRADO"))
                .andExpect(jsonPath("$.equipos[0].estado").value("EXPIRADO"));
        // Cancelar algo vencido no cambia nada ni incrementa revisiones.
        long revision = dispositivos.findById(pc.id()).orElseThrow().getRevisionDeseada();
        revocar(f, permiso, null).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EXPIRADO"));
        assertThat(dispositivos.findById(pc.id()).orElseThrow().getRevisionDeseada()).isEqualTo(revision);
    }

    @Test
    void reintentoMismoIdentificadorEsIdempotenteYConOtroContenidoResponde409() throws Exception {
        Familia f = familia("Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz");
        inventario(laptop, MINECRAFT);
        UUID concesion = UUID.randomUUID();
        String body = tiempoExtra(concesion, f.hijo("Luz"), app(laptop), 20, laptop);
        long id = id(otorgar(f, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        otorgar(f, body).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        otorgar(f, tiempoExtra(concesion, f.hijo("Luz"), app(laptop), 25, laptop)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from permisos_temporales where familia_id=?", Integer.class, f.familiaId()))
                .isEqualTo(1);
    }

    @Test
    void tiempoExtraDiarioNoSuperaCuatroHorasYLasDuracionesSeValidan() throws Exception {
        Familia f = familia("Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz");
        inventario(laptop, MINECRAFT);
        otorgar(f, tiempoExtra(UUID.randomUUID(), f.hijo("Luz"), app(laptop), 200, laptop)).andExpect(status().isCreated());
        otorgar(f, tiempoExtra(UUID.randomUUID(), f.hijo("Luz"), app(laptop), 50, laptop)).andExpect(status().isBadRequest());
        otorgar(f, tiempoExtra(UUID.randomUUID(), f.hijo("Luz"), app(laptop), 241, laptop)).andExpect(status().isBadRequest());
        otorgar(f, porDuracion(UUID.randomUUID(), "FUERA_HORARIO", f.hijo("Luz"), app(laptop), null, 2, laptop))
                .andExpect(status().isBadRequest());
        String r = otorgar(f, porDuracion(UUID.randomUUID(), "FUERA_HORARIO", f.hijo("Luz"), app(laptop), null, 60, laptop))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Instant expira = Instant.parse(JsonPath.read(r, "$.expiraEn"));
        assertThat(expira).isBetween(Instant.now().plus(59, ChronoUnit.MINUTES), Instant.now().plus(61, ChronoUnit.MINUTES));
    }

    @Test
    void cancelarEnUnEquipoNoAfectaAlOtroYElAgenteConfirmaElEstadoReal() throws Exception {
        Familia f = familia("Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz"), pc = vincular(f, "Luz", "PC Luz");
        inventario(laptop, MINECRAFT); inventario(pc, MINECRAFT);
        heartbeat(laptop);
        String r = otorgar(f, porDuracion(UUID.randomUUID(), "DESBLOQUEO_TEMPORAL", f.hijo("Luz"), app(laptop), null, 30, laptop, pc))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long permiso = id(r);
        long filaLaptop = ((Number) JsonPath.read(permisosAgente(laptop).andReturn().getResponse().getContentAsString(),
                "$.permisos[0].filaId")).longValue();
        eventoAgente(laptop, filaLaptop, "APLICADO").andExpect(jsonPath("$.aceptados").value(1));
        String vista = consultar(f, permiso).andReturn().getResponse().getContentAsString();
        assertThat(estado(vista, laptop)).isEqualTo("ACTIVO");
        assertThat(estado(vista, pc)).isEqualTo("DESCONECTADO");

        revocar(f, permiso, pc.id()).andExpect(status().isOk());
        vista = consultar(f, permiso).andExpect(jsonPath("$.estado").value("ACTIVO")).andReturn().getResponse().getContentAsString();
        assertThat(estado(vista, pc)).isEqualTo("REVOCADO");
        assertThat(estado(vista, laptop)).isEqualTo("ACTIVO");
        permisosAgente(pc).andExpect(jsonPath("$.permisos.length()").value(0));
        permisosAgente(laptop).andExpect(jsonPath("$.permisos.length()").value(1));

        // El Agente informa que terminó; queda auditado para el padre y un evento antiguo no lo revierte.
        eventoAgente(laptop, filaLaptop, "FINALIZADO").andExpect(jsonPath("$.aceptados").value(1));
        assertThat(jdbc.queryForObject("select estado_agente from permisos_temporales_dispositivos where id=?", String.class, filaLaptop))
                .isEqualTo("FINALIZADO");
        eventoAgente(laptop, filaLaptop, "APLICADO");
        assertThat(jdbc.queryForObject("select estado_agente from permisos_temporales_dispositivos where id=?", String.class, filaLaptop))
                .isEqualTo("FINALIZADO");
        assertThat(jdbc.queryForObject("select count(*) from eventos_auditoria where familia_id=? and tipo_evento='PERMISO_TEMPORAL_FINALIZADO'",
                Integer.class, f.familiaId())).isEqualTo(1);
    }

    @Test
    void permitirSitioSoloParaReglasDelMismoHijo() throws Exception {
        Familia f = familia("Luz", "Mateo");
        Equipo luz = vincular(f, "Luz", "Laptop Luz");
        mvc.perform(post("/api/bulk-operations").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"operacionId":"%s","tipo":"BLOQUEAR_SITIO","dispositivoIds":[%d],"dominio":"youtube.com"}
                        """.formatted(UUID.randomUUID(), luz.id()))).andExpect(status().isCreated());
        long sitio = jdbc.queryForObject("select id from sitios_bloqueados where familia_id=? and menor_id=? and dominio='youtube.com'",
                Long.class, f.familiaId(), f.hijo("Luz"));
        otorgar(f, porDuracion(UUID.randomUUID(), "PERMITIR_SITIO", f.hijo("Luz"), null, sitio, 45, luz))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.objetivo").value("youtube.com"));
        permisosAgente(luz).andExpect(jsonPath("$.permisos[0].dominio").value("youtube.com"))
                .andExpect(jsonPath("$.permisos[0].sitioId").value(sitio));
        // Un equipo de Mateo no puede recibir un permiso pensado para Luz.
        Equipo mateo = vincular(f, "Mateo", "PC Mateo");
        otorgar(f, porDuracion(UUID.randomUUID(), "PERMITIR_SITIO", f.hijo("Luz"), null, sitio, 45, mateo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otraFamiliaNoPuedeVerNiModificarNiInformarPermisosAjenos() throws Exception {
        Familia a = familia("Luz"), b = familia("Ana");
        Equipo luz = vincular(a, "Luz", "Laptop Luz"), ana = vincular(b, "Ana", "Laptop Ana");
        inventario(luz, MINECRAFT); inventario(ana, MINECRAFT);
        long permiso = id(otorgar(a, tiempoExtra(UUID.randomUUID(), a.hijo("Luz"), app(luz), 15, luz))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long fila = ((Number) JsonPath.read(permisosAgente(luz).andReturn().getResponse().getContentAsString(),
                "$.permisos[0].filaId")).longValue();

        otorgar(b, tiempoExtra(UUID.randomUUID(), b.hijo("Ana"), app(ana), 15, luz)).andExpect(status().isNotFound());
        otorgar(b, tiempoExtra(UUID.randomUUID(), b.hijo("Ana"), app(luz), 15, ana)).andExpect(status().isNotFound());
        consultar(b, permiso).andExpect(status().isNotFound());
        revocar(b, permiso, null).andExpect(status().isNotFound());
        mvc.perform(get("/api/temporary-permissions").header(HttpHeaders.AUTHORIZATION, bearer(b.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        eventoAgente(ana, fila, "FINALIZADO").andExpect(jsonPath("$.aceptados").value(0));
        mvc.perform(get("/api/agent/permissions").header("X-Agent-Token", "token-falso")).andExpect(status().isUnauthorized());

        // La base de datos también impide mezclar familias aunque el código fallara.
        long menorB = b.hijo("Ana");
        assertThatThrownBy(() -> jdbc.update("""
                insert into permisos_temporales_dispositivos (permiso_id, familia_id, menor_id, dispositivo_id, aplicacion_id,
                  estado_agente, activo, creado_en, actualizado_en)
                values (?, ?, ?, ?, ?, 'PENDIENTE', true, current_timestamp, current_timestamp)""",
                permiso, a.familiaId(), a.hijo("Luz"), ana.id(), app(luz))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into permisos_temporales_dispositivos (permiso_id, familia_id, menor_id, dispositivo_id, aplicacion_id,
                  estado_agente, activo, creado_en, actualizado_en)
                values (?, ?, ?, ?, ?, 'PENDIENTE', true, current_timestamp, current_timestamp)""",
                permiso, b.familiaId(), menorB, ana.id(), app(ana))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pinParentalExigeContrasenaSeAuditaYSoloElVerificadorLlegaAlAgente() throws Exception {
        Familia f = familia("Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz");
        mvc.perform(get("/api/parental-pin").header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(jsonPath("$.configurado").value(false));
        cambiarPin(f, "otra-clave", "4826").andExpect(status().isBadRequest());
        cambiarPin(f, PASSWORD, "1234").andExpect(status().isBadRequest());
        cambiarPin(f, PASSWORD, "4826").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));

        verPin(f, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.pin").value("4826"))
                .andExpect(jsonPath("$.segundosVisible").value(30));
        assertThat(jdbc.queryForObject("select count(*) from eventos_auditoria where familia_id=? and tipo_evento='PIN_PARENTAL_CONSULTADO'",
                Integer.class, f.familiaId())).isEqualTo(1);
        String cifrado = jdbc.queryForObject("select pin_cifrado from pines_parentales where familia_id=?", String.class, f.familiaId());
        assertThat(cifrado).doesNotContain("4826");

        String agente = mvc.perform(get("/api/agent/parental-pin").header("X-Agent-Token", laptop.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pin").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String verificador = JsonPath.read(agente, "$.verificador");
        assertThat(PinCifrado.coincide("4826", verificador)).isTrue();
        assertThat(PinCifrado.coincide("4827", verificador)).isFalse();

        // Contraseñas incorrectas seguidas: bloqueo temporal, incluso con la correcta.
        for (int i = 0; i < 5; i++) verPin(f, "incorrecta-" + i).andExpect(status().isBadRequest());
        verPin(f, PASSWORD).andExpect(status().isConflict());
    }

    @Test
    void pausaAutorizadaSeUsaUnaSolaVezYPuedeCancelarse() throws Exception {
        Familia f = familia("Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz");
        String r = mvc.perform(post("/api/device-authorizations").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispositivoId\":%d,\"tipo\":\"PAUSA\",\"minutos\":30}".formatted(laptop.id())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("VIGENTE"))
                .andReturn().getResponse().getContentAsString();
        long id = id(r);
        mvc.perform(get("/api/agent/authorizations").header("X-Agent-Token", laptop.token()))
                .andExpect(jsonPath("$.autorizaciones[0].tipo").value("PAUSA"));
        mvc.perform(post("/api/agent/authorizations/{id}/use", id).header("X-Agent-Token", laptop.token())).andExpect(status().isOk());
        mvc.perform(get("/api/agent/authorizations").header("X-Agent-Token", laptop.token()))
                .andExpect(jsonPath("$.autorizaciones.length()").value(0));

        long desinstalar = id(mvc.perform(post("/api/device-authorizations").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispositivoId\":%d,\"tipo\":\"DESINSTALACION\"}".formatted(laptop.id())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/device-authorizations/{id}/cancel", desinstalar).header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(jsonPath("$.estado").value("CANCELADA"));
        mvc.perform(post("/api/agent/authorizations/{id}/use", desinstalar).header("X-Agent-Token", laptop.token()))
                .andExpect(status().isConflict());
    }

    @Test
    void avisoUnicoDeDesconexionYAvisoDeReconexion() throws Exception {
        Familia f = familia("Luz");
        Equipo nuevo = vincular(f, "Luz", "Laptop nueva"), laptop = vincular(f, "Luz", "Laptop Luz");
        heartbeat(laptop);
        jdbc.update("update dispositivos set ultimo_heartbeat=? where id=?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)), laptop.id());
        alertas.revisar(Instant.now());
        alertas.revisar(Instant.now());
        assertThat(avisos(laptop, AlertasConexionService.SIN_CONEXION)).isEqualTo(1);
        assertThat(avisos(nuevo, AlertasConexionService.SIN_CONEXION)).isZero();
        heartbeat(laptop);
        alertas.revisar(Instant.now());
        assertThat(avisos(laptop, AlertasConexionService.RECONECTADO)).isEqualTo(1);
        mvc.perform(post("/api/agent/local-events").header("X-Agent-Token", laptop.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventos\":[{\"eventoId\":\"%s\",\"tipo\":\"PIN_BLOQUEADO\",\"detalle\":null,\"ocurridoEn\":\"%s\"}]}"
                        .formatted(UUID.randomUUID(), Instant.now()))).andExpect(status().isOk());
        String avisosPadre = mvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(avisosPadre).contains("SIN_CONEXION", "RECONECTADO", "PIN_BLOQUEADO");
    }

    // --------------------------------------------------------------------------------------- Ayudas
    private int avisos(Equipo e, String tipo) {
        return jdbc.queryForObject("select count(*) from eventos_auditoria where dispositivo_id=? and tipo_evento=?", Integer.class, e.id(), tipo);
    }
    private void vencer(long permiso) {
        jdbc.update("update permisos_temporales set inicio_en=?, expira_en=? where id=?",
                java.sql.Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)), permiso);
    }
    private String estado(String json, Equipo e) {
        List<String> valores = JsonPath.read(json, "$.equipos[?(@.dispositivoId == " + e.id() + ")].estado");
        return valores.get(0);
    }
    private long app(Equipo e) {
        return aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(
                dispositivos.findById(e.id()).orElseThrow().getFamilia().getId(), e.id()).get(0).getId();
    }
    private static String tiempoExtra(UUID concesion, long menor, long app, int minutos, Equipo... equipos) {
        return "{\"concesionId\":\"%s\",\"menorId\":%d,\"dispositivoIds\":%s,\"tipo\":\"TIEMPO_EXTRA\",\"aplicacionId\":%d,\"minutosExtra\":%d}"
                .formatted(concesion, menor, ids(equipos), app, minutos);
    }
    private static String porDuracion(UUID concesion, String tipo, long menor, Long app, Long sitio, int minutos, Equipo... equipos) {
        return "{\"concesionId\":\"%s\",\"menorId\":%d,\"dispositivoIds\":%s,\"tipo\":\"%s\",\"aplicacionId\":%s,\"sitioId\":%s,\"duracionMinutos\":%d,\"motivo\":\"Tarea\"}"
                .formatted(concesion, menor, ids(equipos), tipo, app, sitio, minutos);
    }
    private static String ids(Equipo... equipos) {
        return java.util.Arrays.stream(equipos).map(e -> Long.toString(e.id())).collect(Collectors.joining(",", "[", "]"));
    }
    private static long id(String json) { return ((Number) JsonPath.read(json, "$.id")).longValue(); }
    private ResultActions otorgar(Familia f, String body) throws Exception {
        return mvc.perform(post("/api/temporary-permissions").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private ResultActions consultar(Familia f, long id) throws Exception {
        return mvc.perform(get("/api/temporary-permissions/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(f.token())));
    }
    private ResultActions revocar(Familia f, long id, Long dispositivo) throws Exception {
        var request = post("/api/temporary-permissions/{id}/revoke", id).header(HttpHeaders.AUTHORIZATION, bearer(f.token()));
        if (dispositivo != null) request = request.param("dispositivoId", dispositivo.toString());
        return mvc.perform(request);
    }
    private ResultActions permisosAgente(Equipo e) throws Exception {
        return mvc.perform(get("/api/agent/permissions").header("X-Agent-Token", e.token())).andExpect(status().isOk());
    }
    private ResultActions eventoAgente(Equipo e, long fila, String estado) throws Exception {
        return mvc.perform(post("/api/agent/permission-events").header("X-Agent-Token", e.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventos\":[{\"filaId\":%d,\"estado\":\"%s\",\"detalle\":\"Prueba\",\"ocurridoEn\":\"%s\"}]}"
                        .formatted(fila, estado, Instant.now()))).andExpect(status().isOk());
    }
    private ResultActions cambiarPin(Familia f, String password, String pin) throws Exception {
        return mvc.perform(put("/api/parental-pin").header(HttpHeaders.AUTHORIZATION, bearer(f.token())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"%s\",\"pin\":\"%s\",\"confirmarPin\":\"%s\"}".formatted(password, pin, pin)));
    }
    private ResultActions verPin(Familia f, String password) throws Exception {
        return mvc.perform(post("/api/parental-pin/reveal").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"%s\"}".formatted(password)));
    }
    private void heartbeat(Equipo e) throws Exception {
        long revision = dispositivos.findById(e.id()).orElseThrow().getRevisionAplicada();
        mvc.perform(post("/api/agent/heartbeat").header("X-Agent-Token", e.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enviadoEn\":\"%s\",\"intervaloSegundos\":30,\"usuarioLocal\":\"hijo\",\"versionAgente\":\"1.1\",\"revisionAplicada\":%d}"
                        .formatted(Instant.now(), revision))).andExpect(status().isOk());
    }
    private void inventario(Equipo e, String ruta) throws Exception {
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", e.token()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"aplicaciones":[{"nombre":"Minecraft","rutaEjecutable":"%s","version":"1.0","editor":"Prueba","tipoInstalacion":"REGISTRY"}],
                         "procesos":[],"observadoEn":"%s"}""".formatted(ruta, Instant.now())))
                .andExpect(status().isOk());
    }
    private Familia familia(String... hijos) throws Exception {
        String email = "permisos-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Heiner","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"Familia de prueba"}
                """.formatted(email, PASSWORD, PASSWORD))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String hijo : hijos) ids.put(hijo, menores.save(new Menor(user.getFamilia(), hijo)).getId());
        return new Familia(JsonPath.read(login, "$.accessToken"), user.getFamilia().getId(), ids);
    }
    private Equipo vincular(Familia f, String hijo, String nombre) throws Exception {
        String code = JsonPath.read(mvc.perform(post("/api/children/{id}/device-link-codes", f.hijo(hijo))
                        .header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.codigo");
        String linked = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","nombre":"%s","identificador":"%s","sistemaOperativo":"Windows","versionSistema":"11"}
                        """.formatted(code, nombre, "perm-" + UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Equipo(((Number) JsonPath.read(linked, "$.dispositivoId")).longValue(), JsonPath.read(linked, "$.agenteToken"));
    }
    private static String bearer(String token) { return "Bearer " + token; }
    private record Familia(String token, long familiaId, Map<String, Long> hijos) {
        long hijo(String nombre) { return hijos.get(nombre); }
    }
    private record Equipo(long id, String token) { }
}
