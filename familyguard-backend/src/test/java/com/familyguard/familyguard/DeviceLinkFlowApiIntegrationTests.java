package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.CodigoVinculacion;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.*;
import com.familyguard.familyguard.service.AutenticacionService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
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

/** Etapa 2: vinculación rápida por código con confirmación automática en el Administrador. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class DeviceLinkFlowApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired CodigoVinculacionRepository codigos;
    @Autowired DispositivoRepository dispositivos;

    @Test
    void codigoNuevoTieneIdentificadorVencimientoYQuedaPendiente() throws Exception {
        Familia f = familia();
        Instant antes = Instant.now();
        String json = mvc.perform(post("/api/children/{id}/device-link-codes", f.childId()).header(HttpHeaders.AUTHORIZATION, bearer(f)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo").value(org.hamcrest.Matchers.matchesPattern("[A-Z0-9]{8}")))
                .andExpect(jsonPath("$.codigoId").isNumber())
                .andReturn().getResponse().getContentAsString();
        Instant expira = Instant.parse(JsonPath.read(json, "$.expiraEn"));
        assertThat(Duration.between(antes, expira)).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(11));
        estado(f, id(json)).andExpect(jsonPath("$.estado").value("PENDIENTE"));
    }

    @Test
    void elAdministradorVeElEquipoVinculadoAutomaticamente() throws Exception {
        Familia f = familia();
        String json = codigo(f, f.childId());
        String linked = vincular(JsonPath.read(json, "$.codigo"), "PC-LUZ", "luz-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long deviceId = ((Number) JsonPath.read(linked, "$.dispositivoId")).longValue();
        estado(f, id(json))
                .andExpect(jsonPath("$.estado").value("VINCULADO"))
                .andExpect(jsonPath("$.dispositivoId").value(deviceId))
                .andExpect(jsonPath("$.dispositivoNombre").value("PC-LUZ"));
    }

    @Test
    void generarOtroCodigoInvalidaElPendienteDelMismoHijoSinAfectarAOtros() throws Exception {
        Familia f = familia();
        Long otroHijo = menores.save(new Menor(usuarios.findByEmailIgnoreCase(f.email()).orElseThrow().getFamilia(), "Hermano")).getId();
        String delHermano = codigo(f, otroHijo);
        String primero = codigo(f, f.childId());
        String segundo = codigo(f, f.childId());

        estado(f, id(primero)).andExpect(jsonPath("$.estado").value("REEMPLAZADO"));
        estado(f, id(delHermano)).andExpect(jsonPath("$.estado").value("PENDIENTE"));
        vincular(JsonPath.read(primero, "$.codigo"), "PC-1", "rep-" + UUID.randomUUID())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("reemplazado")));
        vincular(JsonPath.read(segundo, "$.codigo"), "PC-1", "rep-" + UUID.randomUUID()).andExpect(status().isCreated());
        vincular(JsonPath.read(delHermano, "$.codigo"), "PC-2", "rep-" + UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void codigoConsumidoNoSePuedeReutilizar() throws Exception {
        Familia f = familia();
        String json = codigo(f, f.childId());
        String code = JsonPath.read(json, "$.codigo");
        vincular(code, "PC-1", "uso-" + UUID.randomUUID()).andExpect(status().isCreated());
        vincular(code, "PC-2", "uso-" + UUID.randomUUID())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("ya fue utilizado")));
        estado(f, id(json)).andExpect(jsonPath("$.estado").value("VINCULADO"));
    }

    @Test
    void codigoVencidoSeRechazaConMensajeClaro() throws Exception {
        Familia f = familia();
        var user = usuarios.findByEmailIgnoreCase(f.email()).orElseThrow();
        Menor menor = menores.findById(f.childId()).orElseThrow();
        String code = "V" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase();
        // Código real con vigencia breve (cumple ck_codigos_expiracion) que vence de forma natural.
        Instant expira = Instant.now().plusMillis(200);
        CodigoVinculacion vencido = codigos.save(new CodigoVinculacion(user.getFamilia(), menor,
                AutenticacionService.hashToken(code), expira));
        esperarHasta(expira.plusMillis(100));
        vincular(code, "PC-1", "venc-" + UUID.randomUUID())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("vencio")));
        estado(f, vencido.getId()).andExpect(jsonPath("$.estado").value("VENCIDO"));
    }

    @Test
    void cadaComputadoraObtieneSuPropioRegistroYCredencial() throws Exception {
        Familia f = familia();
        String a = vincular(JsonPath.read(codigo(f, f.childId()), "$.codigo"), "PC-SALA", "multi-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String b = vincular(JsonPath.read(codigo(f, f.childId()), "$.codigo"), "LAPTOP-LUZ", "multi-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String c = vincular(JsonPath.read(codigo(f, f.childId()), "$.codigo"), "PC-ESTUDIO", "multi-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(java.util.Set.of(JsonPath.<Integer>read(a, "$.dispositivoId"), JsonPath.<Integer>read(b, "$.dispositivoId"),
                JsonPath.<Integer>read(c, "$.dispositivoId"))).hasSize(3);
        assertThat(java.util.Set.of(JsonPath.<String>read(a, "$.agenteToken"), JsonPath.<String>read(b, "$.agenteToken"),
                JsonPath.<String>read(c, "$.agenteToken"))).hasSize(3);
        mvc.perform(get("/api/devices").header(HttpHeaders.AUTHORIZATION, bearer(f)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void otraFamiliaNoPuedeConsultarNiUsarCodigosAjenos() throws Exception {
        Familia propia = familia();
        Familia ajena = familia();
        String json = codigo(propia, propia.childId());
        estado(ajena, id(json)).andExpect(status().isNotFound());
        mvc.perform(post("/api/children/{id}/device-link-codes", propia.childId()).header(HttpHeaders.AUTHORIZATION, bearer(ajena)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/device-link-codes/{id}", id(json))).andExpect(status().isUnauthorized());
        // El dispositivo vinculado con el código queda en la familia y el hijo del código.
        String linked = vincular(JsonPath.read(json, "$.codigo"), "PC-PROPIA", "fam-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long deviceId = ((Number) JsonPath.read(linked, "$.dispositivoId")).longValue();
        assertThat(dispositivos.findByFamilia_IdAndId(propia.familiaId(), deviceId)).isPresent();
        assertThat(dispositivos.findByFamilia_IdAndId(ajena.familiaId(), deviceId)).isEmpty();
    }

    @Test
    void limitePorIpNoDependeDelIdentificadorEnviadoPorElCliente() throws Exception {
        Familia f = familia();
        String ip = "203.0.113." + (10 + (int) (Math.random() * 200));
        // Un atacante cambia el identificador en cada intento: antes eso evitaba el bloqueo.
        for (int i = 0; i < 20; i++)
            vincularDesde(ip, "ZZZZZZZ" + (char) ('A' + (i % 26)), "PC", "ataque-" + UUID.randomUUID())
                    .andExpect(status().isUnauthorized());
        String valido = JsonPath.read(codigo(f, f.childId()), "$.codigo");
        vincularDesde(ip, valido, "PC", "ataque-" + UUID.randomUUID())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("Demasiados intentos")));
        // Otra red no queda afectada y el código válido sigue disponible.
        vincularDesde("198.51.100.20", valido, "PC", "legitimo-" + UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void codigoIncorrectoDevuelveMensajeClaro() throws Exception {
        vincular("QQQQQQQQ", "PC", "mal-" + UUID.randomUUID())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("Codigo invalido")));
    }

    @Test
    void revinculacionTambienSeRefleja() throws Exception {
        Familia f = familia();
        String identificador = "revinc-" + UUID.randomUUID();
        String linked = vincular(JsonPath.read(codigo(f, f.childId()), "$.codigo"), "PC-LUZ", identificador)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long deviceId = ((Number) JsonPath.read(linked, "$.dispositivoId")).longValue();
        mvc.perform(delete("/api/devices/{id}", deviceId).header(HttpHeaders.AUTHORIZATION, bearer(f))).andExpect(status().isNoContent());
        String nuevo = codigo(f, f.childId());
        vincular(JsonPath.read(nuevo, "$.codigo"), "PC-LUZ", identificador).andExpect(status().isCreated())
                .andExpect(jsonPath("$.dispositivoId").value(deviceId));
        estado(f, id(nuevo)).andExpect(jsonPath("$.estado").value("VINCULADO"))
                .andExpect(jsonPath("$.dispositivoId").value(deviceId));
    }

    @Test
    void codigoAnuladoSeRechazaAunqueElRelojNoHayaAvanzado() throws Exception {
        Familia f = familia();
        String primero = codigo(f, f.childId());
        String code = JsonPath.read(primero, "$.codigo");
        var entity = codigos.findById(id(primero)).orElseThrow();
        entity.expirar();
        codigos.saveAndFlush(entity);
        // Sin esperar: la anulación es definitiva aunque Instant.now() no haya avanzado.
        vincular(code, "PC", "anulado-" + UUID.randomUUID())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("reemplazado")));
        estado(f, id(primero)).andExpect(jsonPath("$.estado").value("REEMPLAZADO"));
        assertThat(codigos.findById(id(primero)).orElseThrow().getExpiraEn())
                .isAfter(codigos.findById(id(primero)).orElseThrow().getCreadoEn());
    }

    static void esperarHasta(Instant instante) throws InterruptedException {
        while (!Instant.now().isAfter(instante)) Thread.sleep(20);
    }

    private ResultActions estado(Familia f, long codigoId) throws Exception {
        return mvc.perform(get("/api/device-link-codes/{id}", codigoId).header(HttpHeaders.AUTHORIZATION, bearer(f)));
    }
    private String codigo(Familia f, Long childId) throws Exception {
        return mvc.perform(post("/api/children/{id}/device-link-codes", childId).header(HttpHeaders.AUTHORIZATION, bearer(f)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private ResultActions vincular(String code, String name, String identifier) throws Exception {
        return mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content(linkBody(code, name, identifier)));
    }
    private ResultActions vincularDesde(String ip, String code, String name, String identifier) throws Exception {
        return mvc.perform(post("/api/agent/link").with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON).content(linkBody(code, name, identifier)));
    }
    private static String linkBody(String code, String name, String identifier) {
        return """
                {"codigo":"%s","nombre":"%s","identificador":"%s","sistemaOperativo":"Windows","versionSistema":"11"}
                """.formatted(code, name, identifier);
    }
    private static long id(String json) { return ((Number) JsonPath.read(json, "$.codigoId")).longValue(); }

    private Familia familia() throws Exception {
        String email = "link-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Heiner","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"Familia Link"}
                """.formatted(email, PASSWORD, PASSWORD))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        Menor child = menores.save(new Menor(user.getFamilia(), "Luz"));
        return new Familia(JsonPath.read(login, "$.accessToken"), user.getFamilia().getId(), child.getId(), email);
    }
    private static String bearer(Familia f) { return "Bearer " + f.token(); }
    private record Familia(String token, Long familiaId, Long childId, String email) { }
}
