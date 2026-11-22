package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.EstadoDispositivo;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.SitioBloqueadoRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * Etapa 6: revisión de seguridad. Barrido de aislamiento entre familias sobre todos los recursos con
 * identificador, rutas protegidas sin sesión y registro de acciones administrativas sin secretos.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class SecurityReviewIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    private static final String MARCA = "ZZPrivadoA";
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired DispositivoRepository dispositivos;
    @Autowired SitioBloqueadoRepository sitios;
    @Autowired AplicacionRepository aplicaciones;
    @Autowired JdbcTemplate jdbc;

    /** Recursos de la familia A que la familia B intentará usar. */
    private record RecursosA(Familia familia, long hijo, long dispositivo, String tokenAgente, long regla, long limite,
                             long aplicacion, long operacion, long codigo) { }

    @Test
    void otraFamiliaNoPuedeLeerModificarNiBorrarRecursosAjenos() throws Exception {
        RecursosA a = recursosDeA();
        Familia b = familia("Familia Intrusa", "Mateo");
        long hijoB = b.hijo();
        // Cuerpos válidos con recursos propios de B: así la petición pasa la validación y llega a la
        // comprobación de familia (un 400 de validación no demostraría el aislamiento).
        long equipoB = vincular(b, "IntrusoLaptop");
        String reglaB = """
                {"menorId":%d,"dispositivoIds":[%d],"dominio":"intruso.com","incluirSubdominios":true,"categoria":"PERSONALIZADO",
                 "modo":"BLOQUEAR","permanente":true,"vigenteHasta":null,"motivo":"x","horarios":[]}""".formatted(hijoB, equipoB);
        String limiteB = """
                {"menorId":%d,"aplicacionId":%d,"minutosDiarios":1,"bloqueoTotal":true,"advertenciaMinutos":5,"horarios":[]}"""
                .formatted(hijoB, a.aplicacion());
        String operacionB = "{\"operacionId\":\"%s\",\"tipo\":\"RETIRAR_SITIO\",\"dispositivoIds\":[%d],\"dominio\":\"%s.com\"}"
                .formatted(UUID.randomUUID(), a.dispositivo(), MARCA.toLowerCase());

        List<RequestBuilder> ataques = List.of(
                get("/api/children/{id}", a.hijo()),
                put("/api/children/{id}", a.hijo()).contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"Cambiado\"}"),
                patch("/api/children/{id}/active", a.hijo()).param("value", "false"),
                delete("/api/children/{id}", a.hijo()),
                post("/api/children/{id}/device-link-codes", a.hijo()),
                get("/api/device-link-codes/{id}", a.codigo()),
                get("/api/devices/{id}/detail", a.dispositivo()),
                delete("/api/devices/{id}", a.dispositivo()),
                put("/api/web-restrictions/{id}", a.regla()).contentType(MediaType.APPLICATION_JSON).content(reglaB),
                patch("/api/web-restrictions/{id}/active", a.regla()).param("value", "false"),
                delete("/api/web-restrictions/{id}", a.regla()),
                put("/api/restrictions/{id}", a.limite()).contentType(MediaType.APPLICATION_JSON).content(limiteB),
                patch("/api/restrictions/{id}/active", a.limite()).param("value", "false"),
                delete("/api/restrictions/{id}", a.limite()),
                post("/api/restrictions").contentType(MediaType.APPLICATION_JSON).content(limiteB),
                get("/api/bulk-operations/{id}", a.operacion()),
                post("/api/bulk-operations/{id}/cancel", a.operacion()),
                post("/api/bulk-operations").contentType(MediaType.APPLICATION_JSON).content(operacionB),
                post("/api/bulk-operations/preview").contentType(MediaType.APPLICATION_JSON).content(operacionB));
        for (RequestBuilder ataque : ataques) {
            MvcResult r = mvc.perform(conSesion(ataque, b)).andReturn();
            assertThat(r.getResponse().getStatus()).as(descripcion(r)).isIn(403, 404);
            assertThat(r.getResponse().getContentAsString()).as(descripcion(r)).doesNotContain(MARCA);
        }

        // Listados filtrados con identificadores ajenos: nunca devuelven datos de A.
        for (RequestBuilder listado : List.<RequestBuilder>of(
                get("/api/devices").param("menorId", Long.toString(a.hijo())),
                get("/api/devices/panel").param("menorId", Long.toString(a.hijo())),
                get("/api/restrictions").param("dispositivoId", Long.toString(a.dispositivo())),
                get("/api/restrictions").param("menorId", Long.toString(a.hijo())),
                get("/api/applications").param("menorId", Long.toString(a.hijo())),
                get("/api/web-restrictions").param("menorId", Long.toString(a.hijo())),
                get("/api/bulk-operations"), get("/api/children"), get("/api/web-events"))) {
            MvcResult r = mvc.perform(conSesion(listado, b)).andReturn();
            assertThat(r.getResponse().getStatus()).as(descripcion(r)).isIn(200, 403, 404);
            assertThat(r.getResponse().getContentAsString()).as(descripcion(r)).doesNotContain(MARCA);
        }

        // Todo lo de A sigue intacto.
        assertThat(menores.findById(a.hijo()).orElseThrow().isActivo()).isTrue();
        assertThat(dispositivos.findById(a.dispositivo()).orElseThrow().getEstado()).isEqualTo(EstadoDispositivo.AUTORIZADO);
        assertThat(sitios.findById(a.regla()).orElseThrow().isActivo()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from limites_tiempo where id=? and activo=true", Integer.class, a.limite())).isEqualTo(1);
        mvc.perform(get("/api/bulk-operations/{id}", a.operacion()).header(HttpHeaders.AUTHORIZATION, bearer(a.familia().token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("REGISTRADA"));
        // La credencial del Agente de A sigue siendo válida (B no pudo revocarla).
        mvc.perform(get("/api/agent/web-rules").header("X-Agent-Token", a.tokenAgente())).andExpect(status().isOk());
    }

    @Test
    void rutasDelPadreExigenSesionYLasDelAgenteSuCredencial() throws Exception {
        for (String ruta : List.of("/api/devices", "/api/devices/panel", "/api/children", "/api/restrictions",
                "/api/web-restrictions", "/api/bulk-operations", "/api/applications", "/api/dashboard", "/api/alerts",
                "/api/auth/me", "/api/web-events", "/actuator/info"))
            mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/bulk-operations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        for (String ruta : List.of("/api/agent/rules", "/api/agent/web-rules", "/api/agent/status")) {
            int sinCabecera = mvc.perform(get(ruta)).andReturn().getResponse().getStatus();
            assertThat(sinCabecera).as(ruta).isIn(400, 401);
            mvc.perform(get(ruta).header("X-Agent-Token", "credencial-falsa")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void accionesAdministrativasQuedanRegistradasSinSecretos() throws Exception {
        RecursosA a = recursosDeA();
        mvc.perform(delete("/api/web-restrictions/{id}", a.regla()).header(HttpHeaders.AUTHORIZATION, bearer(a.familia().token())))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/bulk-operations/{id}/cancel", a.operacion()).header(HttpHeaders.AUTHORIZATION, bearer(a.familia().token())))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/devices/{id}", a.dispositivo()).header(HttpHeaders.AUTHORIZATION, bearer(a.familia().token())))
                .andExpect(status().isNoContent());

        List<String> tipos = jdbc.queryForList("select tipo_evento from eventos_auditoria where familia_id=? and usuario_id is not null",
                String.class, a.familia().familiaId());
        assertThat(tipos).contains("SITIO_WEB_CREADO", "SITIO_WEB_RETIRADO", "RESTRICCION_CREADA",
                "OPERACION_MASIVA_REGISTRADA", "OPERACION_MASIVA_CANCELADA", "DISPOSITIVO_REVOCADO");
        List<String> textos = jdbc.queryForList("select descripcion from eventos_auditoria where familia_id=?",
                String.class, a.familia().familiaId());
        assertThat(String.join("\n", textos)).doesNotContain(a.tokenAgente()).doesNotContain(PASSWORD)
                .doesNotContain(a.familia().token());
        assertThat(jdbc.queryForObject("select count(*) from eventos_auditoria where familia_id=? and tipo_evento='DISPOSITIVO_REVOCADO'"
                + " and dispositivo_id=?", Integer.class, a.familia().familiaId(), a.dispositivo())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ utilidades

    private RecursosA recursosDeA() throws Exception {
        Familia a = familia("Familia " + MARCA, MARCA + "Luz");
        String codigoJson = mvc.perform(post("/api/children/{id}/device-link-codes", a.hijo())
                        .header(HttpHeaders.AUTHORIZATION, bearer(a.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String linked = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","nombre":"%sLaptop","identificador":"seg-%s","sistemaOperativo":"Windows","versionSistema":"11"}
                        """.formatted(JsonPath.<String>read(codigoJson, "$.codigo"), MARCA, UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long dispositivo = ((Number) JsonPath.read(linked, "$.dispositivoId")).longValue();
        String token = JsonPath.read(linked, "$.agenteToken");
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", token).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"aplicaciones":[{"nombre":"%sJuego","rutaEjecutable":"C:\\\\Juegos\\\\%s.exe","version":"1.0",
                          "editor":"Prueba","tipoInstalacion":"REGISTRY"}],"procesos":[],"observadoEn":"%s"}
                        """.formatted(MARCA, MARCA, Instant.now()))).andExpect(status().isOk());
        long app = aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(a.familiaId(), dispositivo).get(0).getId();
        String limite = mvc.perform(post("/api/restrictions").header(HttpHeaders.AUTHORIZATION, bearer(a.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"menorId":%d,"aplicacionId":%d,"minutosDiarios":60,"bloqueoTotal":false,"advertenciaMinutos":5,"horarios":[]}
                                """.formatted(a.hijo(), app)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String regla = mvc.perform(post("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION, bearer(a.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"menorId":%d,"dispositivoIds":[%d],"dominio":"%s.com","incluirSubdominios":true,"categoria":"PERSONALIZADO",
                                 "modo":"BLOQUEAR","permanente":true,"vigenteHasta":null,"motivo":"%s","horarios":[]}
                                """.formatted(a.hijo(), dispositivo, MARCA.toLowerCase(), MARCA)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String operacion = mvc.perform(post("/api/bulk-operations").header(HttpHeaders.AUTHORIZATION, bearer(a.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(
                                "{\"operacionId\":\"%s\",\"tipo\":\"BLOQUEAR_SITIO\",\"dispositivoIds\":[%d],\"dominio\":\"%s-masivo.com\"}"
                                        .formatted(UUID.randomUUID(), dispositivo, MARCA.toLowerCase())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new RecursosA(a, a.hijo(), dispositivo, token, id(regla), id(limite), app, id(operacion),
                ((Number) JsonPath.read(codigoJson, "$.codigoId")).longValue());
    }

    /** Vincula una laptop a la familia y devuelve su identificador. */
    private long vincular(Familia f, String nombre) throws Exception {
        String codigo = JsonPath.read(mvc.perform(post("/api/children/{id}/device-link-codes", f.hijo())
                        .header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.codigo");
        String linked = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","nombre":"%s","identificador":"seg-%s","sistemaOperativo":"Windows","versionSistema":"11"}
                        """.formatted(codigo, nombre, UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(linked, "$.dispositivoId")).longValue();
    }

    private static long id(String json) { return ((Number) JsonPath.read(json, "$.id")).longValue(); }

    private static RequestBuilder conSesion(RequestBuilder builder, Familia f) {
        return request -> {
            var r = builder.buildRequest(request);
            r.addHeader(HttpHeaders.AUTHORIZATION, bearer(f.token()));
            return r;
        };
    }

    private static String descripcion(MvcResult r) {
        return r.getRequest().getMethod() + " " + r.getRequest().getRequestURI() + " → " + r.getResponse().getStatus();
    }

    private Familia familia(String nombre, String hijo) throws Exception {
        String email = "seg-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Heiner","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"%s"}
                """.formatted(email, PASSWORD, PASSWORD, nombre))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        long menor = menores.save(new Menor(user.getFamilia(), hijo)).getId();
        return new Familia(JsonPath.read(login, "$.accessToken"), user.getFamilia().getId(), menor);
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Familia(String token, long familiaId, long hijo) { }
}
