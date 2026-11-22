package com.familyguard.familyguard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.*;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Regresión del problema N.º 3 en el backend: el administrador ve el estado real del bloqueo web
 * (Aplicado, Pendiente o Error), independiente de la sincronización de políticas de aplicaciones.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class WebFilterStateApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    private static final String PERMISOS = "Bloqueo web pendiente: requiere permisos de administrador";
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;

    @Test
    void reglaNuevaQuedaPendienteHastaQueElAgenteConfirmeLaAplicacionReal() throws Exception {
        Escenario e = escenario();
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"));
        // Descargar las reglas ya no equivale a haberlas aplicado.
        mvc.perform(get("/api/agent/web-rules").header("X-Agent-Token", e.agentToken())).andExpect(status().isOk());
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"));

        evento(e, "FILTRO_PENDIENTE_PERMISOS", PERMISOS, false, Instant.now().minusSeconds(30), e.revision());
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"))
                .andExpect(jsonPath("$[0].detalleAplicacion").value(PERMISOS));
        evento(e, "FILTRO_ERROR", "No se pudo actualizar el filtro DNS local: IOException", false, Instant.now().minusSeconds(20), e.revision());
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("ERROR"));
        evento(e, "FILTRO_APLICADO", "Sitio bloqueado en este equipo", true, Instant.now().minusSeconds(10), e.revision());
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("APLICADO"))
                .andExpect(jsonPath("$[0].detalleAplicacion").value("Sitio bloqueado en este equipo"));
    }

    @Test
    void modificarLaReglaExigeNuevaConfirmacionYDescartaEventosDeRevisionAnterior() throws Exception {
        Escenario e = escenario();
        evento(e, "FILTRO_APLICADO", "Sitio bloqueado en este equipo", true, Instant.now().minusSeconds(60), e.revision());
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("APLICADO"));

        String editada = mvc.perform(put("/api/web-restrictions/{id}", e.ruleId()).header(HttpHeaders.AUTHORIZATION, bearer(e.parentToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(reglaBody(e, "example.net")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(e.revision() + 1))
                .andExpect(jsonPath("$.estadoAplicacion").value("PENDIENTE"))
                .andReturn().getResponse().getContentAsString();
        long nueva = ((Number) JsonPath.read(editada, "$.revision")).longValue();

        evento(e, "FILTRO_APLICADO", "Sitio bloqueado en este equipo", true, Instant.now().minusSeconds(5), e.revision());
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"));
        evento(e, "FILTRO_APLICADO", "Sitio bloqueado en este equipo", true, Instant.now().minusSeconds(4), nueva);
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("APLICADO"));
    }

    @Test
    void unEventoAntiguoNoSobrescribeUnEstadoMasReciente() throws Exception {
        Escenario e = escenario();
        evento(e, "FILTRO_APLICADO", "Sitio bloqueado en este equipo", true, Instant.now().minusSeconds(5), e.revision());
        evento(e, "FILTRO_PENDIENTE_PERMISOS", PERMISOS, false, Instant.now().minusSeconds(120), e.revision());
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("APLICADO"));
    }

    @Test
    void sincronizacionParcialAplicaPoliticasAunqueElBloqueoWebEstePendiente() throws Exception {
        Escenario e = escenario();
        String heartbeat = mvc.perform(post("/api/agent/heartbeat").header("X-Agent-Token", e.agentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enviadoEn\":\"%s\",\"intervaloSegundos\":30,\"revisionAplicada\":0}".formatted(Instant.now())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cambiosPendientes").value(true))
                .andReturn().getResponse().getContentAsString();
        long deseada = ((Number) JsonPath.read(heartbeat, "$.revisionDeseada")).longValue();
        mvc.perform(post("/api/agent/sync-confirmations").header("X-Agent-Token", e.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"confirmacionId":"%s","revision":%d,"estado":"APLICADA",
                                 "detalle":"Reglas de aplicaciones aplicadas; bloqueo web: %s","confirmadoEn":"%s"}
                                """.formatted(UUID.randomUUID(), deseada, PERMISOS, Instant.now())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptada").value(true));
        evento(e, "FILTRO_PENDIENTE_PERMISOS", PERMISOS, false, Instant.now(), e.revision());

        mvc.perform(get("/api/devices").header(HttpHeaders.AUTHORIZATION, bearer(e.parentToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].estadoSincronizacion".formatted(e.deviceId())).value("APLICADA"));
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"))
                .andExpect(jsonPath("$[0].detalleAplicacion").value(PERMISOS));
    }

    @Test
    void retirarUnaReglaQuedaPendienteHastaQueElAgenteConfirmaLaRetirada() throws Exception {
        Escenario e = escenario();
        evento(e, "FILTRO_APLICADO", "Sitio bloqueado en este equipo", true, Instant.now().minusSeconds(60), e.revision());
        mvc.perform(delete("/api/web-restrictions/{id}", e.ruleId()).header(HttpHeaders.AUTHORIZATION, bearer(e.parentToken())))
                .andExpect(status().isNoContent());
        estado(e).andExpect(jsonPath("$[0].activo").value(false)).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"));

        evento(e, "FILTRO_RETIRADO", PERMISOS, false, Instant.now().minusSeconds(10), e.revision());
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"));
        evento(e, "FILTRO_RETIRADO", "Regla retirada del equipo", true, Instant.now().minusSeconds(5), e.revision());
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("APLICADO"))
                .andExpect(jsonPath("$[0].detalleAplicacion").value("Regla retirada del equipo"));
    }

    @Test
    void eventoDeAgenteAnteriorSinRevisionSeInterpretaSinFalsasConfirmaciones() throws Exception {
        Escenario e = escenario();
        evento(e, "FILTRO_APLICADO", "Se requieren permisos de administrador para actualizar el filtro DNS: AccessDeniedException",
                false, Instant.now().minusSeconds(10), null);
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"));
    }

    @Test
    void eventoDeUnaReglaNoAsignadaSeConfirmaSinBloquearLaCola() throws Exception {
        Escenario e = escenario();
        Escenario ajeno = escenario();
        String body = """
                {"eventos":[{"eventoId":"%s","reglaId":%d,"dominio":"example.org","tipo":"FILTRO_APLICADO",
                  "detalle":"x","ocurridoEn":"%s","exitoso":true,"revision":1}]}
                """.formatted(UUID.randomUUID(), ajeno.ruleId(), Instant.now());
        mvc.perform(post("/api/agent/web-events").header("X-Agent-Token", e.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(0))
                .andExpect(jsonPath("$.confirmados.length()").value(1));
        estado(ajeno).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"));
    }

    @Test
    void confirmacionConLaRevisionDescargadaPorElAgenteSeReflejaComoAplicada() throws Exception {
        Escenario e = escenario();
        String reglas = mvc.perform(get("/api/agent/web-rules").header("X-Agent-Token", e.agentToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long descargada = ((Number) JsonPath.read(reglas, "$.reglas[0].revision")).longValue();
        org.assertj.core.api.Assertions.assertThat(descargada).isEqualTo(e.revision());
        evento(e, "FILTRO_APLICADO", "Sitio bloqueado en este equipo", true, Instant.now().minusSeconds(2), descargada);
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("APLICADO"));
    }

    @Test
    void eventoConRevisionInexistenteNoConfirmaLaRegla() throws Exception {
        Escenario e = escenario();
        evento(e, "FILTRO_APLICADO", "Sitio bloqueado en este equipo", true, Instant.now().minusSeconds(2), e.revision() + 5);
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"));
        evento(e, "FILTRO_RETIRADO", "Regla retirada del equipo", true, Instant.now().minusSeconds(1), e.revision() + 5);
        estado(e).andExpect(jsonPath("$[0].estadoAplicacion").value("PENDIENTE"));
    }

    /** Cada escenario es una familia nueva con una sola regla, por eso la regla es {@code $[0]}. */
    private ResultActions estado(Escenario e) throws Exception {
        return mvc.perform(get("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION, bearer(e.parentToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(e.ruleId()));
    }

    private void evento(Escenario e, String tipo, String detalle, boolean exitoso, Instant at, Long revision) throws Exception {
        String revisionJson = revision == null ? "" : ",\"revision\":" + revision;
        mvc.perform(post("/api/agent/web-events").header("X-Agent-Token", e.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"eventos":[{"eventoId":"%s","reglaId":%d,"dominio":"example.org","tipo":"%s",
                                  "detalle":"%s","ocurridoEn":"%s","exitoso":%s%s}]}
                                """.formatted(UUID.randomUUID(), e.ruleId(), tipo, detalle, at, exitoso, revisionJson)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(1));
    }

    private Escenario escenario() throws Exception {
        String email = "web-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Padre Web","email":"%s","password":"%s",
                 "confirmarPassword":"%s","nombreFamilia":"Familia Web"}
                """.formatted(email, PASSWORD, PASSWORD))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String parent = JsonPath.read(login, "$.accessToken");
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        Menor child = menores.save(new Menor(user.getFamilia(), "Hijo Web"));
        String code = JsonPath.read(mvc.perform(post("/api/children/{id}/device-link-codes", child.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(parent)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.codigo");
        String linked = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","nombre":"PC Web","identificador":"web-%s",
                         "sistemaOperativo":"Windows","versionSistema":"11"}
                        """.formatted(code, UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long deviceId = ((Number) JsonPath.read(linked, "$.dispositivoId")).longValue();
        Escenario base = new Escenario(parent, child.getId(), deviceId, JsonPath.read(linked, "$.agenteToken"), 0, 0);
        String created = mvc.perform(post("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION, bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON).content(reglaBody(base, "example.org")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Escenario(parent, child.getId(), deviceId, base.agentToken(), ((Number) JsonPath.read(created, "$.id")).longValue(),
                ((Number) JsonPath.read(created, "$.revision")).longValue());
    }

    private static String reglaBody(Escenario e, String domain) {
        return """
                {"menorId":%d,"dispositivoIds":[%d],"dominio":"%s","incluirSubdominios":true,"categoria":"PERSONALIZADO",
                 "modo":"BLOQUEAR","permanente":true,"vigenteHasta":null,"motivo":"estudio","horarios":[]}
                """.formatted(e.childId(), e.deviceId(), domain);
    }
    private static String bearer(String token) { return "Bearer " + token; }
    /** {@code revision}: revisión real de la regla tras crearla (la que descargará el agente). */
    private record Escenario(String parentToken, long childId, long deviceId, String agentToken, long ruleId, long revision) { }
}
