package com.familyguard.familyguard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Vigencia por fechas de las restricciones de aplicaciones: se guarda en las columnas existentes
 * vigente_desde / vigente_hasta, se devuelve al Administrador y se envía al Agente (sin cambios de esquema).
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class RestrictionValidityApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired AplicacionRepository aplicaciones;

    @Test
    void guardaYDevuelveLaVigenciaYLaEnviaAlAgente() throws Exception {
        Escenario e = escenario();
        LocalDate desde = LocalDate.now(ZoneId.of("America/Lima")).plusDays(1), hasta = desde.plusDays(6);
        String creada = mvc.perform(post("/api/restrictions").header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body(e, "\"" + desde + "\"", "\"" + hasta + "\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vigenteDesde").value(desde.toString()))
                .andExpect(jsonPath("$.vigenteHasta").value(hasta.toString()))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(creada, "$.id")).longValue();

        mvc.perform(get("/api/restrictions").header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vigenteDesde").value(desde.toString()))
                .andExpect(jsonPath("$[0].vigenteHasta").value(hasta.toString()));
        mvc.perform(get("/api/agent/rules").header("X-Agent-Token", e.agentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reglas[0].vigenteDesde").value(desde.toString()))
                .andExpect(jsonPath("$.reglas[0].vigenteHasta").value(hasta.toString()));

        // Editar sin fechas quita la vigencia (rige siempre); clientes anteriores siguen funcionando.
        mvc.perform(put("/api/restrictions/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body(e, "null", "null")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vigenteDesde").doesNotExist())
                .andExpect(jsonPath("$.vigenteHasta").doesNotExist());
    }

    @Test
    void rechazaFechasInvertidasOFinYaPasado() throws Exception {
        Escenario e = escenario();
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Lima"));
        mvc.perform(post("/api/restrictions").header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(e, "\"" + hoy.plusDays(5) + "\"", "\"" + hoy.plusDays(1) + "\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/restrictions").header(HttpHeaders.AUTHORIZATION, "Bearer " + e.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(e, "\"" + hoy.minusDays(10) + "\"", "\"" + hoy.minusDays(3) + "\"")))
                .andExpect(status().isConflict());
    }

    private String body(Escenario e, String desde, String hasta) {
        return """
                {"menorId":%d,"aplicacionId":%d,"minutosDiarios":60,"bloqueoTotal":false,"advertenciaMinutos":5,
                 "horarios":[{"dia":"THURSDAY","inicio":"16:00","fin":"18:00","tipo":"BLOQUEAR"}],
                 "vigenteDesde":%s,"vigenteHasta":%s}
                """.formatted(e.childId(), e.appId(), desde, hasta);
    }

    private Escenario escenario() throws Exception {
        String email = "vigencia-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Padre","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"Familia Vigencia"}
                """.formatted(email, PASSWORD, PASSWORD))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(login, "$.accessToken");
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        Menor hijo = menores.save(new Menor(user.getFamilia(), "Hijo"));
        String code = JsonPath.read(mvc.perform(post("/api/children/{id}/device-link-codes", hijo.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.codigo");
        String linked = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","nombre":"PC","identificador":"vig-%s","sistemaOperativo":"Windows","versionSistema":"11"}
                        """.formatted(code, UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long deviceId = ((Number) JsonPath.read(linked, "$.dispositivoId")).longValue();
        String agentToken = JsonPath.read(linked, "$.agenteToken");
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", agentToken).contentType(MediaType.APPLICATION_JSON).content("""
                {"aplicaciones":[{"nombre":"Google Chrome","rutaEjecutable":"C:\\\\Apps\\\\chrome.exe",
                  "version":"1.0","editor":"Prueba","tipoInstalacion":"REGISTRY"}],
                 "procesos":[],"observadoEn":"2026-11-15T12:00:00Z"}
                """)).andExpect(status().isOk());
        long appId = aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(user.getFamilia().getId(), deviceId).get(0).getId();
        return new Escenario(token, agentToken, hijo.getId(), appId);
    }

    private record Escenario(String token, String agentToken, long childId, long appId) { }
}
