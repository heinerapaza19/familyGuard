package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Programación de reglas web: lo que el padre guarda se recupera exactamente igual, y el estado (Programada,
 * Bloqueando ahora, Finalizada…) depende de la hora y de la confirmación real del equipo. Reloj inyectable.
 * Base H2 (perfil test); no toca MySQL.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class WebScheduleApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    /** Reloj que la prueba adelanta a voluntad. */
    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-20T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class RelojDePrueba {
        @Bean @Primary MutableClock relojDePrueba() { return new MutableClock(); }
    }

    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired MutableClock reloj;

    static Instant lima(String local) { return LocalDateTime.parse(local).atZone(LIMA).toInstant(); }

    @Test
    void guardarYEditarRecuperaExactamenteFechasHorasYDias() throws Exception {
        reloj.now = lima("2026-11-19T21:00");
        Escenario e = escenario();
        String creada = guardar(e, null, regla(e, "youtube.com", false, "2026-11-20T08:00", "2026-11-20T18:00", ""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(creada, "$.id")).longValue();
        String r = consultar(e).andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(r, "$[0].vigenteDesde"))).isEqualTo(lima("2026-11-20T08:00"));
        assertThat(Instant.parse(JsonPath.read(r, "$[0].vigenteHasta"))).isEqualTo(lima("2026-11-20T18:00"));
        assertThat((String) JsonPath.read(r, "$[0].programacion")).isEqualTo("FECHA");
        assertThat((String) JsonPath.read(r, "$[0].zonaHoraria")).isEqualTo("America/Lima");

        // Editar: pasa a lunes a viernes 08:00–18:00 sin vencimiento; se recupera igual y sin fechas activas.
        String semanal = regla(e, "youtube.com", true, null, null, dias("08:00:00", "18:00:00", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"));
        guardar(e, id, semanal).andExpect(status().isOk());
        r = consultar(e).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(r, "$[0].programacion")).isEqualTo("SEMANAL");
        assertThat((Boolean) JsonPath.read(r, "$[0].permanente")).isTrue();
        assertThat((Object) JsonPath.read(r, "$[0].vigenteHasta")).isNull();
        assertThat(JsonPath.<java.util.List<String>>read(r, "$[0].horarios[*].dia"))
                .containsExactlyInAnyOrder("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY");
        assertThat(JsonPath.<java.util.List<String>>read(r, "$[0].horarios[*].inicio")).allMatch(v -> v.startsWith("08:00"));
        assertThat(JsonPath.<java.util.List<String>>read(r, "$[0].horarios[*].fin")).allMatch(v -> v.startsWith("18:00"));

        // Errores del servidor visibles: fin anterior al inicio y horario vacío.
        guardar(e, id, regla(e, "youtube.com", false, "2026-11-20T18:00", "2026-11-20T08:00", "")).andExpect(status().isConflict());
        guardar(e, id, regla(e, "youtube.com", true, null, null, dias("08:00:00", "08:00:00", "MONDAY"))).andExpect(status().isBadRequest());
    }

    @Test
    void estadoSegunLaHoraYSoloBloqueandoConConfirmacionDeWindows() throws Exception {
        reloj.now = lima("2026-11-20T07:00");
        Escenario e = escenario();
        String creada = guardar(e, null, regla(e, "youtube.com", false, "2026-11-20T08:00", "2026-11-20T18:00", ""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(creada, "$.id")).longValue();
        long revision = ((Number) JsonPath.read(creada, "$.revision")).longValue();
        consultar(e).andExpect(jsonPath("$[0].estadoEfectivo").value("PENDIENTE"));

        // El equipo descarga y confirma que, por ahora, no bloquea.
        mvc.perform(get("/api/agent/web-rules").header("X-Agent-Token", e.agente())).andExpect(status().isOk());
        evento(e, id, "FILTRO_INACTIVO", "Fuera del horario o vigencia: sin bloqueo activo", lima("2026-11-20T07:00:05"), revision);
        consultar(e).andExpect(jsonPath("$[0].estadoEfectivo").value("PROGRAMADA"))
                .andExpect(jsonPath("$[0].proximoInicio").value(lima("2026-11-20T08:00").toString()));

        // 08:30: el horario exige bloquear, pero sin confirmación del equipo no se muestra «Bloqueando ahora».
        reloj.now = lima("2026-11-20T08:30");
        consultar(e).andExpect(jsonPath("$[0].estadoEfectivo").value("PENDIENTE"))
                .andExpect(jsonPath("$[0].debeBloquearAhora").value(true));
        evento(e, id, "FILTRO_APLICADO", "Sitio bloqueado y verificado en Windows (youtube.com → 0.0.0.0)", lima("2026-11-20T08:00:02"), revision);
        String ahora = consultar(e).andExpect(jsonPath("$[0].estadoEfectivo").value("BLOQUEANDO_AHORA")).andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(ahora, "$[0].bloqueoDesde"))).isEqualTo(lima("2026-11-20T08:00:02"));
        assertThat(Instant.parse(JsonPath.read(ahora, "$[0].finActual"))).isEqualTo(lima("2026-11-20T18:00"));

        // Un reinicio vuelve a confirmar el bloqueo: el inicio real sigue siendo el primero de la racha.
        evento(e, id, "FILTRO_APLICADO", "Sitio bloqueado y verificado en Windows (youtube.com → 0.0.0.0)", lima("2026-11-20T10:15"), revision);
        ahora = consultar(e).andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(ahora, "$[0].bloqueoDesde"))).isEqualTo(lima("2026-11-20T08:00:02"));

        // 18:00: termina; el equipo confirma el retiro.
        reloj.now = lima("2026-11-20T18:01");
        evento(e, id, "FILTRO_INACTIVO", "Fuera del horario o vigencia: sin bloqueo activo", lima("2026-11-20T18:00:01"), revision);
        consultar(e).andExpect(jsonPath("$[0].estadoEfectivo").value("FINALIZADA"));

        mvc.perform(get("/api/web-restrictions/{id}/events", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + e.sesion()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].tipo").value("FILTRO_INACTIVO"));
        mvc.perform(patch("/api/web-restrictions/{id}/active", id).param("value", "false")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + e.sesion())).andExpect(status().isOk());
        consultar(e).andExpect(jsonPath("$[0].estadoEfectivo").value("DESACTIVADA"));
    }

    @Test
    void semanalFueraDeHorarioMuestraElProximoBloqueo() throws Exception {
        reloj.now = lima("2026-11-21T10:00");   // sábado
        Escenario e = escenario();
        String creada = guardar(e, null, regla(e, "tiktok.com", true, null, null,
                dias("08:00:00", "18:00:00", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"))).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(creada, "$.id")).longValue();
        long revision = ((Number) JsonPath.read(creada, "$.revision")).longValue();
        evento(e, id, "FILTRO_INACTIVO", "Fuera del horario o vigencia: sin bloqueo activo", lima("2026-11-21T09:59"), revision);
        consultar(e).andExpect(jsonPath("$[0].estadoEfectivo").value("FUERA_DE_HORARIO"))
                .andExpect(jsonPath("$[0].proximoInicio").value(lima("2026-11-23T08:00").toString()));
    }

    // ------------------------------------------------------------------------------------------- Ayudas
    private record Escenario(String sesion, long hijo, long dispositivo, String agente) { }

    private static String dias(String inicio, String fin, String... dias) {
        StringBuilder b = new StringBuilder();
        for (String d : dias) b.append(b.isEmpty() ? "" : ",").append("{\"dia\":\"%s\",\"inicio\":\"%s\",\"fin\":\"%s\",\"tipo\":\"BLOQUEAR\"}".formatted(d, inicio, fin));
        return b.toString();
    }

    private static String regla(Escenario e, String dominio, boolean permanente, String desde, String hasta, String horarios) {
        return """
                {"menorId":%d,"dispositivoIds":[%d],"dominio":"%s","incluirSubdominios":true,"categoria":"PERSONALIZADO",
                 "modo":"BLOQUEAR","permanente":%s,"vigenteDesde":%s,"vigenteHasta":%s,"motivo":"","horarios":[%s]}"""
                .formatted(e.hijo(), e.dispositivo(), dominio, permanente,
                        desde == null ? "null" : "\"" + lima(desde) + "\"", hasta == null ? "null" : "\"" + lima(hasta) + "\"", horarios);
    }

    private ResultActions guardar(Escenario e, Long id, String body) throws Exception {
        var request = id == null ? post("/api/web-restrictions") : put("/api/web-restrictions/{id}", id);
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + e.sesion()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions consultar(Escenario e) throws Exception {
        return mvc.perform(get("/api/web-restrictions").param("menorId", Long.toString(e.hijo()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + e.sesion())).andExpect(status().isOk());
    }

    private void evento(Escenario e, long regla, String tipo, String detalle, Instant cuando, long revision) throws Exception {
        mvc.perform(post("/api/agent/web-events").header("X-Agent-Token", e.agente()).contentType(MediaType.APPLICATION_JSON).content("""
                {"eventos":[{"eventoId":"%s","reglaId":%d,"dominio":"youtube.com","tipo":"%s","detalle":"%s","ocurridoEn":"%s","exitoso":true,"revision":%d}]}
                """.formatted(UUID.randomUUID(), regla, tipo, detalle, cuando, revision))).andExpect(status().isOk());
    }

    private Escenario escenario() throws Exception {
        String email = "programacion-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Heiner","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"Familia"}
                """.formatted(email, PASSWORD, PASSWORD))).andExpect(status().isCreated());
        String sesion = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn().getResponse().getContentAsString(), "$.accessToken");
        var usuario = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        long hijo = menores.save(new Menor(usuario.getFamilia(), "Luz")).getId();
        String codigo = JsonPath.read(mvc.perform(post("/api/children/{id}/device-link-codes", hijo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sesion)).andReturn().getResponse().getContentAsString(), "$.codigo");
        String vinculado = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content("""
                {"codigo":"%s","nombre":"Laptop Luz","identificador":"%s","sistemaOperativo":"Windows","versionSistema":"11"}
                """.formatted(codigo, "prog-" + UUID.randomUUID()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Escenario(sesion, hijo, ((Number) JsonPath.read(vinculado, "$.dispositivoId")).longValue(), JsonPath.read(vinculado, "$.agenteToken"));
    }
}
