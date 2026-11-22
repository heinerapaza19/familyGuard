package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Etapa 3: panel «Mis dispositivos». Escenario: Luz tiene una laptop en Cusco y una PC en Juliaca;
 * Mateo tiene una PC en la misma casa. Comprueba que conexión, descarga y aplicación real se informan
 * por separado y que nunca se muestran equipos de otra familia.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class DevicePanelApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired AplicacionRepository aplicaciones;
    @Autowired DispositivoRepository dispositivos;

    @Test
    void panelListaVariosHijosYEquiposConConexionReal() throws Exception {
        Familia f = familia("Familia Panel");
        Equipo cusco = vincular(f, f.luz(), "Laptop Luz Cusco");
        Equipo juliaca = vincular(f, f.luz(), "PC Luz Juliaca");
        Equipo mateo = vincular(f, f.mateo(), "PC Mateo");
        heartbeat(cusco, 0);

        String panel = panel(f.token(), null);
        assertThat(JsonPath.<List<Object>>read(panel, "$")).hasSize(3);
        assertThat(JsonPath.<List<Boolean>>read(panel, "$[?(@.dispositivo.id==" + cusco.id() + ")].dispositivo.conectado")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(panel, "$[?(@.dispositivo.id==" + juliaca.id() + ")].dispositivo.conectado")).containsExactly(false);
        assertThat(JsonPath.<List<String>>read(panel, "$[?(@.dispositivo.id==" + mateo.id() + ")].dispositivo.menorNombre")).containsExactly("Mateo");
        assertThat(JsonPath.<List<String>>read(panel, "$[*].estadoAplicaciones")).containsOnly("SIN_RESTRICCIONES");
        assertThat(JsonPath.<List<String>>read(panel, "$[*].estadoBloqueoWeb")).containsOnly("SIN_REGLAS");

        String soloLuz = panel(f.token(), f.luz());
        assertThat(JsonPath.<List<Integer>>read(soloLuz, "$[*].dispositivo.id"))
                .containsExactlyInAnyOrder((int) cusco.id(), (int) juliaca.id());
    }

    @Test
    void equipoRecienVinculadoNoFiguraConectadoHastaSuPrimerHeartbeat() throws Exception {
        Familia f = familia("Familia Recien Vinculada");
        Equipo equipo = vincular(f, f.luz(), "Laptop nueva");
        // Regresión: la vinculación sola no equivale a una señal del agente.
        assertThat(dispositivos.findById(equipo.id()).orElseThrow().getUltimoHeartbeat()).isNull();
        detalle(f.token(), equipo.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.dispositivo.conectado").value(false))
                .andExpect(jsonPath("$.dispositivo.ultimoHeartbeat").isEmpty());
        mvc.perform(get("/api/devices").header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].conectado").value(false));

        heartbeat(equipo, 0);
        detalle(f.token(), equipo.id()).andExpect(jsonPath("$.dispositivo.conectado").value(true))
                .andExpect(jsonPath("$.dispositivo.ultimoHeartbeat").exists());
    }

    @Test
    void equipoRevinculadoNoHeredaElHeartbeatDeLaCredencialAnterior() throws Exception {
        Familia f = familia("Familia Revinculada");
        String identificador = "panel-relink-" + UUID.randomUUID();
        Equipo inicial = vincular(f, f.luz(), "Laptop Luz Cusco", identificador);
        heartbeat(inicial, 0);
        detalle(f.token(), inicial.id()).andExpect(jsonPath("$.dispositivo.conectado").value(true));

        mvc.perform(delete("/api/devices/{id}", inicial.id()).header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isNoContent());
        Equipo renovado = vincular(f, f.luz(), "Laptop Luz Cusco", identificador);
        assertThat(renovado.id()).isEqualTo(inicial.id());
        detalle(f.token(), renovado.id()).andExpect(jsonPath("$.dispositivo.conectado").value(false));

        heartbeat(renovado, 0);
        detalle(f.token(), renovado.id()).andExpect(jsonPath("$.dispositivo.conectado").value(true));
    }

    @Test
    void heartbeatAntiguoNoSeMuestraComoConectado() throws Exception {
        Familia f = familia("Familia Heartbeat");
        Equipo equipo = vincular(f, f.luz(), "Laptop sin señal");
        heartbeat(equipo, 0);
        var d = dispositivos.findById(equipo.id()).orElseThrow();
        d.setUltimoHeartbeat(Instant.now().minusSeconds(600)); d.setIntervaloHeartbeatSegundos(30);
        dispositivos.saveAndFlush(d);
        detalle(f.token(), equipo.id()).andExpect(jsonPath("$.dispositivo.conectado").value(false));
    }

    @Test
    void restriccionDeAplicacionPasaDePendienteAErrorYAplicadaSoloConConfirmacionDelAgente() throws Exception {
        Familia f = familia("Familia Restricciones");
        Equipo equipo = vincular(f, f.luz(), "Laptop Luz Cusco");
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", equipo.token())
                .contentType(MediaType.APPLICATION_JSON).content(inventoryBody())).andExpect(status().isOk());
        long appId = aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(f.familiaId(), equipo.id()).get(0).getId();
        mvc.perform(post("/api/restrictions").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(restrictionBody(f.luz(), appId)))
                .andExpect(status().isCreated());

        String pendiente = detalle(f.token(), equipo.id())
                .andExpect(jsonPath("$.estadoAplicaciones").value("PENDIENTE"))
                .andExpect(jsonPath("$.restriccionesAplicaciones").value(1))
                .andExpect(jsonPath("$.restriccionesPendientes").value(1))
                .andReturn().getResponse().getContentAsString();
        long deseada = ((Number) JsonPath.read(pendiente, "$.dispositivo.revisionDeseada")).longValue();

        // Descargar las reglas no equivale a aplicarlas.
        mvc.perform(get("/api/agent/rules").header("X-Agent-Token", equipo.token())).andExpect(status().isOk());
        detalle(f.token(), equipo.id()).andExpect(jsonPath("$.estadoAplicaciones").value("PENDIENTE"));

        confirmar(equipo, deseada, "FALLIDA", "No se pudo escribir la caché local");
        detalle(f.token(), equipo.id()).andExpect(jsonPath("$.estadoAplicaciones").value("ERROR"))
                .andExpect(jsonPath("$.restriccionesConError").value(1));

        confirmar(equipo, deseada, "APLICADA", "Reglas aplicadas");
        detalle(f.token(), equipo.id()).andExpect(jsonPath("$.estadoAplicaciones").value("APLICADA"))
                .andExpect(jsonPath("$.restriccionesAplicadas").value(1))
                .andExpect(jsonPath("$.restriccionesPendientes").value(0))
                .andExpect(jsonPath("$.restriccionesConError").value(0));
    }

    @Test
    void bloqueoWebSeInformaPorEquipoSegunConfirmacionReal() throws Exception {
        Familia f = familia("Familia Web Panel");
        Equipo cusco = vincular(f, f.luz(), "Laptop Luz Cusco");
        Equipo juliaca = vincular(f, f.luz(), "PC Luz Juliaca");
        String regla = mvc.perform(post("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"menorId":%d,"dispositivoIds":[%d,%d],"dominio":"example.org","incluirSubdominios":true,
                                 "categoria":"PERSONALIZADO","modo":"BLOQUEAR","permanente":true,"vigenteHasta":null,
                                 "motivo":"estudio","horarios":[]}
                                """.formatted(f.luz(), cusco.id(), juliaca.id())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long reglaId = ((Number) JsonPath.read(regla, "$.id")).longValue();
        long revision = ((Number) JsonPath.read(regla, "$.revision")).longValue();

        detalle(f.token(), cusco.id()).andExpect(jsonPath("$.estadoBloqueoWeb").value("PENDIENTE"))
                .andExpect(jsonPath("$.reglasWeb").value(1));
        mvc.perform(post("/api/agent/web-events").header("X-Agent-Token", cusco.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"eventos":[{"eventoId":"%s","reglaId":%d,"dominio":"example.org","tipo":"FILTRO_APLICADO",
                                  "detalle":"Sitio bloqueado en este equipo","ocurridoEn":"%s","exitoso":true,"revision":%d}]}
                                """.formatted(UUID.randomUUID(), reglaId, Instant.now().minusSeconds(5), revision)))
                .andExpect(status().isOk());
        detalle(f.token(), cusco.id()).andExpect(jsonPath("$.estadoBloqueoWeb").value("APLICADO"))
                .andExpect(jsonPath("$.reglasWebAplicadas").value(1));
        // La confirmación de Cusco no se extiende a Juliaca.
        detalle(f.token(), juliaca.id()).andExpect(jsonPath("$.estadoBloqueoWeb").value("PENDIENTE"))
                .andExpect(jsonPath("$.reglasWebPendientes").value(1));
    }

    @Test
    void aislamientoFamiliarEnPanelYDetalle() throws Exception {
        Familia a = familia("Familia A");
        Familia b = familia("Familia B");
        Equipo deA = vincular(a, a.luz(), "Laptop de A");
        Equipo deB = vincular(b, b.luz(), "Laptop de B");

        String panelB = panel(b.token(), null);
        assertThat(JsonPath.<List<Integer>>read(panelB, "$[*].dispositivo.id")).containsExactly((int) deB.id());
        // Filtrar por un hijo de otra familia no expone sus equipos.
        assertThat(JsonPath.<List<Object>>read(panel(b.token(), a.luz()), "$")).isEmpty();
        detalle(b.token(), deA.id()).andExpect(status().isNotFound());
        detalle(a.token(), 999_999_999L).andExpect(status().isNotFound());
        mvc.perform(get("/api/devices/panel")).andExpect(status().isUnauthorized());
    }

    // --- utilidades ---

    private String panel(String token, Long menorId) throws Exception {
        var request = get("/api/devices/panel").header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (menorId != null) request = request.param("menorId", menorId.toString());
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions detalle(String token, long id) throws Exception {
        return mvc.perform(get("/api/devices/{id}/detail", id).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private void heartbeat(Equipo equipo, long revision) throws Exception {
        mvc.perform(post("/api/agent/heartbeat").header("X-Agent-Token", equipo.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enviadoEn\":\"%s\",\"intervaloSegundos\":30,\"usuarioLocal\":\"luz\",\"versionAgente\":\"1.0\",\"revisionAplicada\":%d}"
                        .formatted(Instant.now(), revision))).andExpect(status().isOk());
    }

    private void confirmar(Equipo equipo, long revision, String estado, String detalle) throws Exception {
        mvc.perform(post("/api/agent/sync-confirmations").header("X-Agent-Token", equipo.token())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"confirmacionId":"%s","revision":%d,"estado":"%s","detalle":"%s","confirmadoEn":"%s"}
                        """.formatted(UUID.randomUUID(), revision, estado, detalle, Instant.now())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptada").value(true));
    }

    private Familia familia(String nombre) throws Exception {
        String email = "panel-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Heiner","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"%s"}
                """.formatted(email, PASSWORD, PASSWORD, nombre))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        Menor luz = menores.save(new Menor(user.getFamilia(), "Luz"));
        Menor mateo = menores.save(new Menor(user.getFamilia(), "Mateo"));
        return new Familia(JsonPath.read(login, "$.accessToken"), user.getFamilia().getId(), luz.getId(), mateo.getId());
    }

    private Equipo vincular(Familia f, long childId, String nombre) throws Exception {
        return vincular(f, childId, nombre, "panel-" + UUID.randomUUID());
    }

    private Equipo vincular(Familia f, long childId, String nombre, String identificador) throws Exception {
        String code = JsonPath.read(mvc.perform(post("/api/children/{id}/device-link-codes", childId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.codigo");
        String linked = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","nombre":"%s","identificador":"%s","sistemaOperativo":"Windows","versionSistema":"11"}
                        """.formatted(code, nombre, identificador)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Equipo(((Number) JsonPath.read(linked, "$.dispositivoId")).longValue(), JsonPath.read(linked, "$.agenteToken"));
    }

    private static String inventoryBody() {
        return """
                {"aplicaciones":[{"nombre":"Editor Real","rutaEjecutable":"C:\\\\Apps\\\\editor.exe",
                  "version":"1.0","editor":"FamilyGuard Test","tipoInstalacion":"REGISTRY"}],
                 "procesos":[],"observadoEn":"2026-11-15T12:00:00Z"}
                """;
    }

    private static String restrictionBody(long child, long app) {
        return """
                {"menorId":%d,"aplicacionId":%d,"minutosDiarios":60,"bloqueoTotal":false,"advertenciaMinutos":5,
                 "horarios":[{"dia":"MONDAY","inicio":"08:00","fin":"10:00","tipo":"BLOQUEAR"}]}
                """.formatted(child, app);
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Familia(String token, long familiaId, long luz, long mateo) { }
    private record Equipo(long id, String token) { }
}
