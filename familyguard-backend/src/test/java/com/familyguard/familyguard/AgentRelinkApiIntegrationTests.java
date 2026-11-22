package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Dispositivo;
import com.familyguard.familyguard.domain.model.EstadoDispositivo;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.*;
import com.familyguard.familyguard.service.AutenticacionService;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Regresión del problema «Renovar vinculación»: un equipo revocado debe poder vincularse de nuevo
 * con un código temporal válido de su misma familia y perfil, reutilizando su registro y conservando
 * su historial, sin que el token anterior vuelva a funcionar.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AgentRelinkApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired CodigoVinculacionRepository codigos;
    @Autowired DispositivoRepository dispositivos;
    @Autowired AplicacionRepository aplicaciones;
    @Autowired UsoAplicacionRepository usos;

    @Test
    void revinculaEquipoRevocadoReutilizandoRegistroYConservandoHistorial() throws Exception {
        Cuenta cuenta = cuenta();
        String identificador = "relink-" + UUID.randomUUID();

        // 1. Vinculación inicial.
        Vinculo inicial = vincular(codigo(cuenta, cuenta.childId()), identificador);
        mvc.perform(get("/api/agent/status").header("X-Agent-Token", inicial.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("AUTORIZADO"));

        // Datos asociados que deben sobrevivir: inventario, restricción y uso registrado.
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", inicial.token())
                .contentType(MediaType.APPLICATION_JSON).content(inventoryBody())).andExpect(status().isOk());
        long appId = aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(
                cuenta.familiaId(), inicial.deviceId()).get(0).getId();
        String restriccion = mvc.perform(post("/api/restrictions").header(HttpHeaders.AUTHORIZATION, bearer(cuenta.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(restrictionBody(cuenta.childId(), appId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long restriccionId = ((Number) JsonPath.read(restriccion, "$.id")).longValue();
        mvc.perform(post("/api/agent/usage").header("X-Agent-Token", inicial.token())
                .contentType(MediaType.APPLICATION_JSON).content(usageBody(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(1));
        int usosAntes = usosDe(cuenta, inicial.deviceId());

        // 2. Revocación: el token deja de ser válido.
        mvc.perform(delete("/api/devices/{id}", inicial.deviceId()).header(HttpHeaders.AUTHORIZATION, bearer(cuenta.accessToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/agent/status").header("X-Agent-Token", inicial.token()))
                .andExpect(status().isUnauthorized());

        // 3. Revinculación con el mismo identificador y un código nuevo del mismo hijo.
        String codigoNuevo = codigo(cuenta, cuenta.childId());
        Vinculo renovado = vincular(codigoNuevo, identificador);

        assertThat(renovado.deviceId()).isEqualTo(inicial.deviceId());
        assertThat(renovado.token()).isNotEqualTo(inicial.token()).hasSizeGreaterThan(32);
        assertThat(dispositivos.findAll().stream().filter(d -> identificador.equals(d.getIdentificador())).count())
                .as("no debe crearse un registro duplicado").isEqualTo(1);
        Dispositivo guardado = dispositivos.findByFamilia_IdAndId(cuenta.familiaId(), inicial.deviceId()).orElseThrow();
        assertThat(guardado.getEstado()).isEqualTo(EstadoDispositivo.AUTORIZADO);
        assertThat(guardado.isAgenteRevocado()).isFalse();
        assertThat(guardado.getDesvinculadoEn()).isNull();
        assertThat(guardado.getVersionCredencial()).isEqualTo(2);
        assertThat(guardado.getAgenteTokenHash()).isEqualTo(AutenticacionService.hashToken(renovado.token()));

        // 4. Token anterior rechazado definitivamente; el nuevo funciona.
        mvc.perform(get("/api/agent/status").header("X-Agent-Token", inicial.token()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/heartbeat").header("X-Agent-Token", inicial.token())
                .contentType(MediaType.APPLICATION_JSON).content(heartbeatBody())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/rules").header("X-Agent-Token", inicial.token()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/status").header("X-Agent-Token", renovado.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dispositivoId").value(inicial.deviceId()));
        mvc.perform(post("/api/agent/heartbeat").header("X-Agent-Token", renovado.token())
                .contentType(MediaType.APPLICATION_JSON).content(heartbeatBody())).andExpect(status().isOk());

        // 5. Historial, aplicaciones y restricciones conservados.
        assertThat(usosDe(cuenta, inicial.deviceId())).isEqualTo(usosAntes).isPositive();
        assertThat(aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(cuenta.familiaId(), inicial.deviceId()))
                .isNotEmpty();
        mvc.perform(get("/api/restrictions").header(HttpHeaders.AUTHORIZATION, bearer(cuenta.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == %s)]".formatted(restriccionId)).isNotEmpty());
        mvc.perform(get("/api/agent/rules").header("X-Agent-Token", renovado.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reglas[0].restriccionId").value(restriccionId));

        // 6. El código temporal es de un solo uso.
        mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON)
                .content(linkBody(codigoNuevo, "otro-" + UUID.randomUUID()))).andExpect(status().isUnauthorized());
    }

    @Test
    void noReemplazaLaCredencialDeUnEquipoActivo() throws Exception {
        Cuenta cuenta = cuenta();
        String identificador = "activo-" + UUID.randomUUID();
        Vinculo inicial = vincular(codigo(cuenta, cuenta.childId()), identificador);
        String codigo = codigo(cuenta, cuenta.childId());

        mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content(linkBody(codigo, identificador)))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/agent/status").header("X-Agent-Token", inicial.token())).andExpect(status().isOk());
        assertThat(dispositivos.findByFamilia_IdAndId(cuenta.familiaId(), inicial.deviceId()).orElseThrow()
                .getVersionCredencial()).isEqualTo(1);
        assertThat(codigoDisponible(codigo)).as("un intento rechazado no consume el código").isTrue();
    }

    @Test
    void otraFamiliaNoPuedeReactivarNiRevocarUnEquipoAjeno() throws Exception {
        Cuenta propietaria = cuenta();
        Cuenta ajena = cuenta();
        String identificador = "ajeno-" + UUID.randomUUID();
        Vinculo inicial = vincular(codigo(propietaria, propietaria.childId()), identificador);

        mvc.perform(delete("/api/devices/{id}", inicial.deviceId()).header(HttpHeaders.AUTHORIZATION, bearer(ajena.accessToken())))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/devices/{id}", inicial.deviceId()).header(HttpHeaders.AUTHORIZATION, bearer(propietaria.accessToken())))
                .andExpect(status().isNoContent());

        String codigoAjeno = codigo(ajena, ajena.childId());
        mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content(linkBody(codigoAjeno, identificador)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El dispositivo ya esta registrado"));

        Dispositivo guardado = dispositivos.findByFamilia_IdAndId(propietaria.familiaId(), inicial.deviceId()).orElseThrow();
        assertThat(guardado.getEstado()).isEqualTo(EstadoDispositivo.REVOCADO);
        assertThat(guardado.isAgenteRevocado()).isTrue();
        assertThat(guardado.getAgenteTokenHash()).isNull();
        assertThat(guardado.getVersionCredencial()).isEqualTo(1);
        assertThat(dispositivos.findByFamilia_IdAndId(ajena.familiaId(), inicial.deviceId())).isEmpty();
        assertThat(dispositivos.findAllByFamilia_IdAndActivoTrue(ajena.familiaId())).isEmpty();
        assertThat(codigoDisponible(codigoAjeno)).isTrue();
    }

    @Test
    void rechazaRevinculacionConCodigoDeOtroHijoDeLaMismaFamilia() throws Exception {
        Cuenta cuenta = cuenta();
        var familia = usuarios.findByEmailIgnoreCase(cuenta.email()).orElseThrow().getFamilia();
        Long otroHijo = menores.save(new Menor(familia, "Otro Hijo")).getId();
        String identificador = "hijo-" + UUID.randomUUID();
        Vinculo inicial = vincular(codigo(cuenta, cuenta.childId()), identificador);
        mvc.perform(delete("/api/devices/{id}", inicial.deviceId()).header(HttpHeaders.AUTHORIZATION, bearer(cuenta.accessToken())))
                .andExpect(status().isNoContent());

        String codigoOtroHijo = codigo(cuenta, otroHijo);
        mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content(linkBody(codigoOtroHijo, identificador)))
                .andExpect(status().isConflict());

        Dispositivo guardado = dispositivos.findByFamilia_IdAndId(cuenta.familiaId(), inicial.deviceId()).orElseThrow();
        assertThat(guardado.getEstado()).isEqualTo(EstadoDispositivo.REVOCADO);
        assertThat(guardado.getVersionCredencial()).isEqualTo(1);

        // Con un código del hijo correcto sí se autoriza.
        Vinculo renovado = vincular(codigo(cuenta, cuenta.childId()), identificador);
        assertThat(renovado.deviceId()).isEqualTo(inicial.deviceId());
    }

    @Test
    void codigoInvalidoNoReactivaUnEquipoRevocado() throws Exception {
        Cuenta cuenta = cuenta();
        String identificador = "invalido-" + UUID.randomUUID();
        Vinculo inicial = vincular(codigo(cuenta, cuenta.childId()), identificador);
        mvc.perform(delete("/api/devices/{id}", inicial.deviceId()).header(HttpHeaders.AUTHORIZATION, bearer(cuenta.accessToken())))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content(linkBody("ZZZZZZZZ", identificador)))
                .andExpect(status().isUnauthorized());
        assertThat(dispositivos.findByFamilia_IdAndId(cuenta.familiaId(), inicial.deviceId()).orElseThrow().getEstado())
                .isEqualTo(EstadoDispositivo.REVOCADO);
    }

    private int usosDe(Cuenta cuenta, long deviceId) {
        return usos.findAllByFamilia_IdAndDispositivo_IdAndInicioEnGreaterThanEqualAndInicioEnLessThan(
                cuenta.familiaId(), deviceId, Instant.EPOCH, Instant.now().plus(1, ChronoUnit.DAYS)).size();
    }

    private boolean codigoDisponible(String codigo) {
        return codigos.findByCodigoHashAndUsadoEnIsNullAndExpiraEnAfter(
                AutenticacionService.hashToken(codigo), Instant.now()).isPresent();
    }

    private Vinculo vincular(String codigo, String identificador) throws Exception {
        String result = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON)
                        .content(linkBody(codigo, identificador)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("AUTORIZADO"))
                .andReturn().getResponse().getContentAsString();
        return new Vinculo(((Number) JsonPath.read(result, "$.dispositivoId")).longValue(), JsonPath.read(result, "$.agenteToken"));
    }

    private String codigo(Cuenta cuenta, Long menorId) throws Exception {
        String json = mvc.perform(post("/api/children/{id}/device-link-codes", menorId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(cuenta.accessToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.codigo");
    }

    private Cuenta cuenta() throws Exception {
        String email = "relink-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Padre Revinculacion","email":"%s","password":"%s",
                 "confirmarPassword":"%s","nombreFamilia":"Familia Revinculacion"}
                """.formatted(email, PASSWORD, PASSWORD))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        Menor child = menores.save(new Menor(user.getFamilia(), "Hijo Revinculacion"));
        return new Cuenta(JsonPath.read(login, "$.accessToken"), user.getFamilia().getId(), child.getId(), email);
    }

    private static String linkBody(String code, String identifier) {
        return """
                {"codigo":"%s","nombre":"PC Familiar","identificador":"%s",
                 "sistemaOperativo":"Windows","versionSistema":"11"}
                """.formatted(code, identifier);
    }
    private static String inventoryBody() {
        return """
                {"aplicaciones":[{"nombre":"Editor Real","rutaEjecutable":"C:\\\\Apps\\\\editor.exe",
                  "version":"1.0","editor":"FamilyGuard Test","tipoInstalacion":"REGISTRY"}],
                 "procesos":[{"pid":4242,"nombre":"Editor Real","rutaEjecutable":"C:\\\\Apps\\\\editor.exe",
                  "ventanaActiva":true}],"observadoEn":"2026-11-15T12:00:00Z"}
                """;
    }
    private static String restrictionBody(long child, long app) {
        return """
                {"menorId":%d,"aplicacionId":%d,"minutosDiarios":60,"bloqueoTotal":false,"advertenciaMinutos":5,
                 "horarios":[{"dia":"MONDAY","inicio":"08:00","fin":"10:00","tipo":"BLOQUEAR"}]}
                """.formatted(child, app);
    }
    private static String usageBody(UUID id) {
        Instant end = Instant.now().minusSeconds(5);
        Instant start = end.minusSeconds(120);
        return """
                {"registros":[{"registroId":"%s","aplicacionNombre":"Editor Real",
                  "rutaEjecutable":"C:\\\\Apps\\\\editor.exe","pid":4242,"inicioEn":"%s",
                  "finEn":"%s","duracionSegundos":120}]}
                """.formatted(id, start, end);
    }
    private static String heartbeatBody() {
        return "{\"enviadoEn\":\"%s\",\"intervaloSegundos\":30,\"revisionAplicada\":0}".formatted(Instant.now());
    }
    private static String bearer(String token) { return "Bearer " + token; }
    private record Cuenta(String accessToken, Long familiaId, Long childId, String email) { }
    private record Vinculo(long deviceId, String token) { }
}
