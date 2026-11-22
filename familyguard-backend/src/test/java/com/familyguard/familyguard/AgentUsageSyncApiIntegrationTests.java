package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.*;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Regresión del problema «un registro de uso inválido bloquea toda la sincronización»:
 * cada registro se procesa de forma independiente y el agente recibe aceptados, duplicados y rechazados.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AgentUsageSyncApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired UsoAplicacionRepository usos;

    @Test
    void aceptaRegistrosValidos() throws Exception {
        String token = agente();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        enviar(token, valido(a, 40), valido(b, 25))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aceptados").value(2))
                .andExpect(jsonPath("$.duplicados").value(0))
                .andExpect(jsonPath("$.rechazados", hasSize(0)))
                .andExpect(jsonPath("$.confirmados", containsInAnyOrder(a.toString(), b.toString())));
        assertThat(usos.existsByRegistroUuid(a.toString())).isTrue();
        assertThat(usos.existsByRegistroUuid(b.toString())).isTrue();
    }

    @Test
    void rechazaCadaTipoDeRegistroInvalidoSinGuardarlo() throws Exception {
        String token = agente();
        Instant fin = Instant.now().minusSeconds(10);
        UUID inconsistente = UUID.randomUUID(), invertido = UUID.randomUUID(), futuro = UUID.randomUUID(),
                largo = UUID.randomUUID(), sinNombre = UUID.randomUUID(), sinPid = UUID.randomUUID();
        enviar(token,
                registro(inconsistente, "word.exe", fin.minusSeconds(30), fin, 5, 10),
                registro(invertido, "word.exe", fin, fin.minusSeconds(30), 30, 10),
                registro(futuro, "word.exe", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(3660), 60, 10),
                registro(largo, "word.exe", fin.minusSeconds(90000), fin, 90000, 10),
                registro(sinNombre, " ", fin.minusSeconds(30), fin, 30, 10),
                registro(sinPid, "word.exe", fin.minusSeconds(30), fin, 30, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aceptados").value(0))
                .andExpect(jsonPath("$.rechazados", hasSize(6)))
                .andExpect(jsonPath("$.rechazados[?(@.registroId == '%s')].codigo".formatted(inconsistente)).value("DURACION_INCONSISTENTE"))
                .andExpect(jsonPath("$.rechazados[?(@.registroId == '%s')].codigo".formatted(invertido)).value("INTERVALO_INVALIDO"))
                .andExpect(jsonPath("$.rechazados[?(@.registroId == '%s')].codigo".formatted(futuro)).value("INTERVALO_FUTURO"))
                .andExpect(jsonPath("$.rechazados[?(@.registroId == '%s')].codigo".formatted(largo)).value("DURACION_EXCEDIDA"))
                .andExpect(jsonPath("$.rechazados[?(@.registroId == '%s')].codigo".formatted(sinNombre)).value("APLICACION_INVALIDA"))
                .andExpect(jsonPath("$.rechazados[?(@.registroId == '%s')].codigo".formatted(sinPid)).value("PID_INVALIDO"))
                // Todos se confirman como procesados para que el agente no los reenvíe sin fin.
                .andExpect(jsonPath("$.confirmados", hasSize(6)));
        for (UUID id : List.of(inconsistente, invertido, futuro, largo, sinNombre, sinPid))
            assertThat(usos.existsByRegistroUuid(id.toString())).isFalse();
    }

    @Test
    void duplicadosSeConfirmanSinVolverAGuardarse() throws Exception {
        String token = agente();
        UUID id = UUID.randomUUID();
        String registro = valido(id, 30);
        enviar(token, registro).andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(1));
        enviar(token, registro, registro)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aceptados").value(0))
                .andExpect(jsonPath("$.duplicados").value(2))
                .andExpect(jsonPath("$.rechazados", hasSize(0)))
                .andExpect(jsonPath("$.confirmados", hasSize(2)));
        assertThat(usos.findAll().stream().filter(u -> id.toString().equals(u.getRegistroUuid()))).hasSize(1);
    }

    @Test
    void loteMixtoGuardaLosValidosAunqueHayaInvalidosYDuplicados() throws Exception {
        String token = agente();
        UUID previo = UUID.randomUUID();
        enviar(token, valido(previo, 15)).andExpect(status().isOk());

        UUID valido1 = UUID.randomUUID(), valido2 = UUID.randomUUID(), invalido = UUID.randomUUID();
        Instant fin = Instant.now().minusSeconds(5);
        String body = lote(valido(valido1, 20),
                registro(invalido, "word.exe", fin.minusSeconds(30), fin, 3, 10),
                valido(previo, 15),
                valido(valido2, 45));
        String respuesta = mvc.perform(post("/api/agent/usage").header("X-Agent-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aceptados").value(2))
                .andExpect(jsonPath("$.duplicados").value(1))
                .andExpect(jsonPath("$.rechazados", hasSize(1)))
                .andExpect(jsonPath("$.rechazados[0].registroId").value(invalido.toString()))
                .andExpect(jsonPath("$.rechazados[0].codigo").value("DURACION_INCONSISTENTE"))
                .andExpect(jsonPath("$.rechazados[0].motivo").isNotEmpty())
                .andExpect(jsonPath("$.confirmados", containsInAnyOrder(
                        valido1.toString(), invalido.toString(), previo.toString(), valido2.toString())))
                .andReturn().getResponse().getContentAsString();
        assertThat(usos.existsByRegistroUuid(valido1.toString())).isTrue();
        assertThat(usos.existsByRegistroUuid(valido2.toString())).isTrue();
        assertThat(usos.existsByRegistroUuid(invalido.toString())).isFalse();

        // Reenviar el mismo lote (p. ej., si la respuesta se perdió) no duplica ni bloquea nada.
        mvc.perform(post("/api/agent/usage").header("X-Agent-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aceptados").value(0))
                .andExpect(jsonPath("$.duplicados").value(3))
                .andExpect(jsonPath("$.rechazados", hasSize(1)));
        assertThat(JsonPath.<List<String>>read(respuesta, "$.confirmados")).hasSize(4);
    }

    @Test
    void conservaLaAutenticacionDelAgente() throws Exception {
        enviar("token-invalido", valido(UUID.randomUUID(), 10)).andExpect(status().isUnauthorized());
    }

    private ResultActions enviar(String token, String... registros) throws Exception {
        return mvc.perform(post("/api/agent/usage").header("X-Agent-Token", token)
                .contentType(MediaType.APPLICATION_JSON).content(lote(registros)));
    }

    private static String lote(String... registros) {
        return "{\"registros\":[" + String.join(",", registros) + "]}";
    }

    private static String valido(UUID id, long segundos) {
        Instant fin = Instant.now().minusSeconds(5);
        return registro(id, "chrome.exe", fin.minusSeconds(segundos), fin, segundos, 4242);
    }

    private static String registro(UUID id, String app, Instant inicio, Instant fin, long duracion, long pid) {
        return """
                {"registroId":"%s","aplicacionNombre":"%s","rutaEjecutable":"C:\\\\Apps\\\\%s",
                 "pid":%d,"inicioEn":"%s","finEn":"%s","duracionSegundos":%d}
                """.formatted(id, app, app.isBlank() ? "app.exe" : app, pid, inicio, fin, duracion);
    }

    private String agente() throws Exception {
        String email = "uso-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Padre Uso","email":"%s","password":"%s",
                 "confirmarPassword":"%s","nombreFamilia":"Familia Uso"}
                """.formatted(email, PASSWORD, PASSWORD))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        Menor child = menores.save(new Menor(user.getFamilia(), "Hijo Uso"));
        String code = JsonPath.read(mvc.perform(post("/api/children/{id}/device-link-codes", child.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JsonPath.read(login, "$.accessToken")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.codigo");
        String linked = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","nombre":"PC Uso","identificador":"uso-%s",
                         "sistemaOperativo":"Windows","versionSistema":"11"}
                        """.formatted(code, UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(linked, "$.agenteToken");
    }
}
