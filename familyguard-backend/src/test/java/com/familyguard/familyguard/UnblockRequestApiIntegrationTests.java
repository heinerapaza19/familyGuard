package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** «Justificar»: el hijo pide desbloquear con mensaje y foto; el padre lo ve en Alertas y aprueba con permiso temporal. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class UnblockRequestApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    /** PNG mínimo real (1×1 px). */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired AplicacionRepository aplicaciones;

    @Test
    void elHijoJustificaConFotoYElPadreApruebaConPermisoTemporal() throws Exception {
        Escenario e = escenario();
        String body = """
                {"tipo":"APP","aplicacionId":%d,"mensaje":"Necesito Chrome para la tarea de ciencias","fotoBase64":"%s"}
                """.formatted(e.appId(), Base64.getEncoder().encodeToString(PNG));
        String created = mvc.perform(post("/api/agent/unblock-requests").header("X-Agent-Token", e.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.objetivo").value("Google Chrome"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        // Una segunda solicitud para la misma aplicación mientras la primera está pendiente se rechaza.
        mvc.perform(post("/api/agent/unblock-requests").header("X-Agent-Token", e.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        // El padre la ve (con foto), en Alertas y en el contador de pendientes.
        mvc.perform(get("/api/unblock-requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].tieneFoto").value(true))
                .andExpect(jsonPath("$[0].mensaje").value("Necesito Chrome para la tarea de ciencias"))
                .andExpect(jsonPath("$[0].menor").value("Hijo"));
        mvc.perform(get("/api/unblock-requests/pending").header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendientes").value(1));
        mvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tipo").value("SOLICITUD_DESBLOQUEO"));
        byte[] photo = mvc.perform(get("/api/unblock-requests/{id}/photo", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(photo).isEqualTo(PNG);

        // Aprobar: permiso temporal de 30 minutos que el Agente recibe en sus permisos.
        mvc.perform(post("/api/unblock-requests/{id}/approve", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minutos\":30,\"respuesta\":\"Solo para la tarea\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.minutosAprobados").value(30));
        mvc.perform(get("/api/agent/unblock-requests").header("X-Agent-Token", e.agentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].estado").value("APROBADA"))
                .andExpect(jsonPath("$[0].respuesta").value("Solo para la tarea"));
        mvc.perform(get("/api/agent/permissions").header("X-Agent-Token", e.agentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permisos[0].tipo").value("DESBLOQUEO_TEMPORAL"))
                .andExpect(jsonPath("$.permisos[0].aplicacionId").value(e.appId()));
        // Ya respondida: no se puede volver a responder.
        mvc.perform(post("/api/unblock-requests/{id}/reject", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/unblock-requests/pending").header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token()))
                .andExpect(jsonPath("$.pendientes").value(0));
    }

    @Test
    void rechazaFotosQueNoSonImagenesYAislaLasFamilias() throws Exception {
        Escenario e = escenario();
        mvc.perform(post("/api/agent/unblock-requests").header("X-Agent-Token", e.agentToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"APP\",\"aplicacionId\":%d,\"mensaje\":\"Por favor\",\"fotoBase64\":\"%s\"}"
                                .formatted(e.appId(), Base64.getEncoder().encodeToString("no es imagen".getBytes()))))
                .andExpect(status().isBadRequest());
        String created = mvc.perform(post("/api/agent/unblock-requests").header("X-Agent-Token", e.agentToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"APP\",\"aplicacionId\":%d,\"mensaje\":\"Sin foto, por favor\"}".formatted(e.appId())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        Escenario otra = escenario();
        mvc.perform(post("/api/unblock-requests/{id}/reject", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + otra.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/unblock-requests/{id}/reject", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"respuesta\":\"Hoy no\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("RECHAZADA"));
        mvc.perform(get("/api/unblock-requests/{id}/photo", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token()))
                .andExpect(status().isNotFound());
    }

    private Escenario escenario() throws Exception {
        String user = "justifica-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Padre","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"Familia Justifica"}
                """.formatted(user, PASSWORD, PASSWORD))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(login, "$.accessToken");
        var padre = usuarios.findByEmailIgnoreCase(user).orElseThrow();
        Menor hijo = menores.save(new Menor(padre.getFamilia(), "Hijo"));
        String code = JsonPath.read(mvc.perform(post("/api/children/{id}/device-link-codes", hijo.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.codigo");
        String linked = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","nombre":"PC","identificador":"jus-%s","sistemaOperativo":"Windows","versionSistema":"11"}
                        """.formatted(code, UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long deviceId = ((Number) JsonPath.read(linked, "$.dispositivoId")).longValue();
        String agentToken = JsonPath.read(linked, "$.agenteToken");
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", agentToken).contentType(MediaType.APPLICATION_JSON).content("""
                {"aplicaciones":[{"nombre":"Google Chrome","rutaEjecutable":"C:\\\\Apps\\\\chrome.exe",
                  "version":"1.0","editor":"Prueba","tipoInstalacion":"REGISTRY"}],
                 "procesos":[],"observadoEn":"2026-11-15T12:00:00Z"}
                """)).andExpect(status().isOk());
        long appId = aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(padre.getFamilia().getId(), deviceId).get(0).getId();
        return new Escenario(token, agentToken, appId);
    }

    private record Escenario(String token, String agentToken, long appId) { }
}
