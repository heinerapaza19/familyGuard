package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.familyguard.familyguard.realtime.AgentEventHub;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Etapa 5: avisos en tiempo real (SSE) para los Agentes. Los avisos se escriben de forma síncrona después del
 * commit de la petición que modifica la política, por lo que las pruebas son deterministas (sin esperas).
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AgentEventsApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    private static final Pattern AVISO = Pattern.compile("\"dispositivoId\":(\\d+),\"revision\":(\\d+)");
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired DispositivoRepository dispositivos;
    @Autowired AgentEventHub hub;

    // 1. Conexión autenticada: recibe de inmediato la revisión vigente de su equipo, sin datos sensibles.
    @Test
    void conexionAutenticadaRecibeLaRevisionActual() throws Exception {
        Familia f = familia("Familia SSE", "Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz");
        MvcResult sse = conectar(laptop);
        assertThat(sse.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(contenido(sse)).contains("event:POLICY_CHANGED").doesNotContain(laptop.token());
        assertThat(avisos(sse)).containsExactly(revisionDeseada(laptop));
        assertThat(dispositivosAvisados(sse)).containsOnly(laptop.id());
        assertThat(hub.conexiones(laptop.id())).isEqualTo(1);
    }

    // 2. Rechazo de credenciales inválidas (sin token en la URL: solo la cabecera).
    @Test
    void rechazaCredencialesInvalidas() throws Exception {
        mvc.perform(get("/api/agent/events").accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/events").header("X-Agent-Token", "no-valido").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
        Familia f = familia("Familia SSE Token", "Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz");
        mvc.perform(get("/api/agent/events").param("token", laptop.token()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
        assertThat(hub.conexiones(laptop.id())).isZero();
    }

    // 3 y 4. Aislamiento entre familias y varios equipos conectados a la vez.
    @Test
    void cadaEquipoRecibeSoloSusAvisosYNuncaLosDeOtraFamilia() throws Exception {
        Familia a = familia("Familia SSE A", "Luz");
        Familia b = familia("Familia SSE B", "Mateo");
        Equipo luz1 = vincular(a, "Luz", "Laptop Luz 1");
        Equipo luz2 = vincular(a, "Luz", "Laptop Luz 2");
        Equipo mateo = vincular(b, "Mateo", "PC Mateo");
        MvcResult s1 = conectar(luz1), s2 = conectar(luz2), s3 = conectar(mateo);

        reglaWeb(a, "Luz", "tiktok.com", luz1);
        assertThat(avisos(s1)).hasSize(2).last().isEqualTo(revisionDeseada(luz1));
        assertThat(avisos(s2)).hasSize(1);
        assertThat(avisos(s3)).hasSize(1);

        reglaWeb(b, "Mateo", "tiktok.com", mateo);
        assertThat(avisos(s3)).hasSize(2).last().isEqualTo(revisionDeseada(mateo));
        assertThat(avisos(s1)).hasSize(2);
        assertThat(dispositivosAvisados(s1)).containsOnly(luz1.id());
        assertThat(dispositivosAvisados(s2)).containsOnly(luz2.id());
        assertThat(dispositivosAvisados(s3)).containsOnly(mateo.id());
    }

    // 5 y 6. Modificar y retirar restricciones web notifica al equipo afectado.
    @Test
    void modificarYRetirarRestriccionesWebNotifica() throws Exception {
        Familia f = familia("Familia SSE Web", "Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz");
        MvcResult sse = conectar(laptop);
        long regla = reglaWeb(f, "Luz", "tiktok.com", laptop);
        int antes = avisos(sse).size();

        mvc.perform(put("/api/web-restrictions/{id}", regla).header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(reglaBody(f, "Luz", "tiktok.com", laptop)))
                .andExpect(status().isOk());
        assertThat(avisos(sse)).hasSize(antes + 1).last().isEqualTo(revisionDeseada(laptop));

        masiva(f, "RETIRAR_SITIO", "tiktok.com", laptop).andExpect(status().isCreated());
        assertThat(avisos(sse)).hasSize(antes + 2).last().isEqualTo(revisionDeseada(laptop));
    }

    // 7 y 11. Operaciones masivas: cada equipo conectado recibe su aviso; el desconectado recupera al conectarse.
    @Test
    void operacionMasivaAvisaACadaEquipoYElDesconectadoRecuperaAlConectarse() throws Exception {
        Familia f = familia("Familia SSE Masiva", "Luz");
        Equipo l1 = vincular(f, "Luz", "Laptop Luz 1"), l2 = vincular(f, "Luz", "Laptop Luz 2"), l3 = vincular(f, "Luz", "Laptop Luz 3");
        MvcResult s1 = conectar(l1), s2 = conectar(l2);
        masiva(f, "BLOQUEAR_SITIO", "tiktok.com", l1, l2, l3).andExpect(status().isCreated());
        assertThat(avisos(s1)).last().isEqualTo(revisionDeseada(l1));
        assertThat(avisos(s2)).last().isEqualTo(revisionDeseada(l2));
        assertThat(dispositivosAvisados(s1)).containsOnly(l1.id());

        // Laptop 3 estaba desconectada: al conectarse recibe la revisión vigente y descarga la política.
        MvcResult s3 = conectar(l3);
        assertThat(avisos(s3)).containsExactly(revisionDeseada(l3));
        String reglas = mvc.perform(get("/api/agent/web-rules").header("X-Agent-Token", l3.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(reglas, "$.reglas[*].dominio")).contains("tiktok.com");
    }

    // 10 y 13. Sin avisos duplicados; un aviso o la conexión no equivalen a «Aplicada».
    @Test
    void sinAvisosDuplicadosYElAvisoNoEsUnaConfirmacion() throws Exception {
        Familia f = familia("Familia SSE Duplicados", "Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz");
        MvcResult sse = conectar(laptop);
        UUID operacion = UUID.randomUUID();
        String op = masivaBody(operacion, "BLOQUEAR_SITIO", "tiktok.com", laptop);
        long id = ((Number) JsonPath.read(ejecutar(f, op).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "$.id")).longValue();
        int despues = avisos(sse).size();

        ejecutar(f, op).andExpect(status().isOk());              // reintento idempotente
        heartbeat(laptop);                                        // actualiza el equipo sin cambiar la política
        assertThat(avisos(sse)).hasSize(despues);

        consultar(f, id).andExpect(jsonPath("$.dispositivos[0].estado").value(org.hamcrest.Matchers.not("APLICADA")));
        // Solo la confirmación real del Agente cambia el estado a «Aplicada».
        mvc.perform(get("/api/agent/web-rules").header("X-Agent-Token", laptop.token())).andExpect(status().isOk());
        String regla = mvc.perform(get("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andReturn().getResponse().getContentAsString();
        long reglaId = ((Number) JsonPath.<List<Object>>read(regla, "$[?(@.dominio=='tiktok.com')].id").get(0)).longValue();
        long revision = ((Number) JsonPath.<List<Object>>read(regla, "$[?(@.dominio=='tiktok.com')].revision").get(0)).longValue();
        mvc.perform(post("/api/agent/web-events").header("X-Agent-Token", laptop.token()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventos":[{"eventoId":"%s","reglaId":%d,"dominio":"tiktok.com","tipo":"FILTRO_APLICADO",
                          "detalle":"Sitio bloqueado","ocurridoEn":"%s","exitoso":true,"revision":%d}]}
                        """.formatted(UUID.randomUUID(), reglaId, Instant.now().minusSeconds(1), revision)))
                .andExpect(status().isOk());
        consultar(f, id).andExpect(jsonPath("$.dispositivos[0].estado").value("APLICADA"));
        assertThat(avisos(sse)).hasSize(despues);
    }

    // 8. Revocación: se cierran las conexiones y la credencial antigua ya no puede reconectarse.
    @Test
    void revocarCierraLasConexionesDelDispositivo() throws Exception {
        Familia f = familia("Familia SSE Revocar", "Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz");
        conectar(laptop);
        assertThat(hub.conexiones(laptop.id())).isEqualTo(1);
        mvc.perform(delete("/api/devices/{id}", laptop.id()).header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isNoContent());
        assertThat(hub.conexiones(laptop.id())).isZero();
        mvc.perform(get("/api/agent/events").header("X-Agent-Token", laptop.token()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }

    // 9 y 15. Desconexión y reconexión: límite por equipo, cierre de la conexión antigua y recuperación.
    @Test
    void reconexionCierraLaConexionAntiguaYRecuperaLosCambios() throws Exception {
        Familia f = familia("Familia SSE Reconexion", "Luz");
        Equipo laptop = vincular(f, "Luz", "Laptop Luz");
        MvcResult antigua = conectar(laptop);
        conectar(laptop);
        MvcResult nueva = conectar(laptop);
        assertThat(hub.conexiones(laptop.id())).isEqualTo(AgentEventHub.MAX_POR_DISPOSITIVO);

        reglaWeb(f, "Luz", "tiktok.com", laptop);
        assertThat(avisos(antigua)).hasSize(1);           // cerrada antes del cambio: no recibe más avisos
        assertThat(avisos(nueva)).last().isEqualTo(revisionDeseada(laptop));

        // Cambios mientras no hay conexiones: al reconectarse se recibe la revisión vigente.
        hub.cambio(laptop.id(), revisionDeseada(laptop), 0, false);   // simula la caída de todas las conexiones
        assertThat(hub.conexiones(laptop.id())).isZero();
        reglaWeb(f, "Luz", "instagram.com", laptop);
        assertThat(avisos(conectar(laptop))).containsExactly(revisionDeseada(laptop));
    }

    // ------------------------------------------------------------------ utilidades

    private MvcResult conectar(Equipo e) throws Exception {
        return mvc.perform(get("/api/agent/events").header("X-Agent-Token", e.token()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
    }
    private static String contenido(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }
    private static List<Long> avisos(MvcResult r) throws Exception {
        List<Long> valores = new ArrayList<>();
        Matcher m = AVISO.matcher(contenido(r));
        while (m.find()) valores.add(Long.parseLong(m.group(2)));
        return valores;
    }
    private static List<Long> dispositivosAvisados(MvcResult r) throws Exception {
        List<Long> valores = new ArrayList<>();
        Matcher m = AVISO.matcher(contenido(r));
        while (m.find()) valores.add(Long.parseLong(m.group(1)));
        return valores;
    }
    private long revisionDeseada(Equipo e) { return dispositivos.findById(e.id()).orElseThrow().getRevisionDeseada(); }

    private long reglaWeb(Familia f, String hijo, String dominio, Equipo e) throws Exception {
        String json = mvc.perform(post("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(reglaBody(f, hijo, dominio, e)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
    private static String reglaBody(Familia f, String hijo, String dominio, Equipo e) {
        return """
                {"menorId":%d,"dispositivoIds":[%d],"dominio":"%s","incluirSubdominios":true,"categoria":"PERSONALIZADO",
                 "modo":"BLOQUEAR","permanente":true,"vigenteHasta":null,"motivo":"estudio","horarios":[]}
                """.formatted(f.hijo(hijo), e.id(), dominio);
    }
    private org.springframework.test.web.servlet.ResultActions masiva(Familia f, String tipo, String dominio, Equipo... equipos)
            throws Exception {
        return ejecutar(f, masivaBody(UUID.randomUUID(), tipo, dominio, equipos));
    }
    private static String masivaBody(UUID operacion, String tipo, String dominio, Equipo... equipos) {
        String ids = java.util.Arrays.stream(equipos).map(e -> Long.toString(e.id())).collect(Collectors.joining(",", "[", "]"));
        return "{\"operacionId\":\"%s\",\"tipo\":\"%s\",\"dispositivoIds\":%s,\"dominio\":\"%s\"}".formatted(operacion, tipo, ids, dominio);
    }
    private org.springframework.test.web.servlet.ResultActions ejecutar(Familia f, String body) throws Exception {
        return mvc.perform(post("/api/bulk-operations").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private org.springframework.test.web.servlet.ResultActions consultar(Familia f, long id) throws Exception {
        return mvc.perform(get("/api/bulk-operations/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isOk());
    }
    private void heartbeat(Equipo e) throws Exception {
        mvc.perform(post("/api/agent/heartbeat").header("X-Agent-Token", e.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enviadoEn\":\"%s\",\"intervaloSegundos\":30,\"usuarioLocal\":\"hijo\",\"versionAgente\":\"1.0\",\"revisionAplicada\":0}"
                        .formatted(Instant.now()))).andExpect(status().isOk());
    }

    private Familia familia(String nombre, String... hijos) throws Exception {
        String email = "sse-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Heiner","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"%s"}
                """.formatted(email, PASSWORD, PASSWORD, nombre))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String hijo : hijos) ids.put(hijo, menores.save(new Menor(user.getFamilia(), hijo)).getId());
        return new Familia(JsonPath.read(login, "$.accessToken"), ids);
    }
    private Equipo vincular(Familia f, String hijo, String nombre) throws Exception {
        String code = JsonPath.read(mvc.perform(post("/api/children/{id}/device-link-codes", f.hijo(hijo))
                        .header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.codigo");
        String linked = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","nombre":"%s","identificador":"sse-%s","sistemaOperativo":"Windows","versionSistema":"11"}
                        """.formatted(code, nombre, UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Equipo(((Number) JsonPath.read(linked, "$.dispositivoId")).longValue(), JsonPath.read(linked, "$.agenteToken"));
    }
    private static String bearer(String token) { return "Bearer " + token; }
    private record Familia(String token, Map<String, Long> hijos) { long hijo(String n) { return hijos.get(n); } }
    private record Equipo(long id, String token) { }
}
