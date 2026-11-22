package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.*;
import com.familyguard.familyguard.service.AutenticacionService;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AgentLinkApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired CodigoVinculacionRepository codigos;
    @Autowired DispositivoRepository dispositivos;
    @Autowired AplicacionRepository aplicaciones;
    @Autowired ProcesoDetectadoRepository procesos;
    @Autowired UsoAplicacionRepository usos;

    @Test
    void generaCodigoTemporalYGuardaSoloSuHash() throws Exception {
        Cuenta cuenta = cuenta();
        String json = generarCodigo(cuenta);
        String codigo = JsonPath.read(json, "$.codigo");
        String hash = AutenticacionService.hashToken(codigo);
        assertThat(codigo).matches("[A-Z0-9]{8}");
        assertThat(json).contains("expiraEn");
        assertThat(codigos.findByCodigoHashAndUsadoEnIsNullAndExpiraEnAfter(hash, Instant.now())).isPresent();
        assertThat(codigos.findAll()).noneMatch(c -> c.getCodigoHash().equals(codigo));
    }

    @Test
    void vinculaDispositivoUnaSolaVezYEntregaTokenIndependiente() throws Exception {
        Cuenta cuenta = cuenta();
        String code = JsonPath.read(generarCodigo(cuenta), "$.codigo");
        String body = linkBody(code, "pc-" + UUID.randomUUID());
        String result = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("AUTORIZADO"))
                .andReturn().getResponse().getContentAsString();
        String agentToken = JsonPath.read(result, "$.agenteToken");
        assertThat(agentToken).hasSizeGreaterThan(32);
        assertThat(dispositivos.findByAgenteTokenHashAndAgenteRevocadoFalseAndActivoTrue(
                AutenticacionService.hashToken(agentToken))).isPresent();
        mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rechazaCodigoExpirado() throws Exception {
        Cuenta cuenta = cuenta();
        String code = JsonPath.read(generarCodigo(cuenta), "$.codigo");
        var entity = codigos.findByCodigoHashAndUsadoEnIsNullAndExpiraEnAfter(
                AutenticacionService.hashToken(code), Instant.now()).orElseThrow();
        entity.expirar(); codigos.saveAndFlush(entity);
        mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON)
                        .content(linkBody(code, "expired-" + UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test void limitaIntentosDeVinculacionPorDispositivo() throws Exception{
        String identifier="attempts-"+UUID.randomUUID();String body=linkBody("AAAAAAAA",identifier);
        for(int i=0;i<5;i++)mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Demasiados intentos")));
    }

    @Test void heartbeatDeterminaConexionYConfirmacionEsIdempotente() throws Exception{
        Vinculado linked=vinculado();String heartbeat="""
          {"enviadoEn":"%s","intervaloSegundos":30,"usuarioLocal":"hijo","versionAgente":"0.9.0","revisionAplicada":0}
          """.formatted(Instant.now());
        mvc.perform(post("/api/agent/heartbeat").header("X-Agent-Token",linked.agentToken()).contentType(MediaType.APPLICATION_JSON).content(heartbeat)).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CONECTADO"));
        var device=dispositivos.findById(linked.deviceId()).orElseThrow();assertThat(device.getUltimoHeartbeat()).isNotNull();assertThat(device.getUsuarioLocal()).isEqualTo("hijo");
        UUID id=UUID.randomUUID();String confirmation="""
          {"confirmacionId":"%s","revision":0,"estado":"APLICADA","detalle":"cache local","confirmadoEn":"%s"}
          """.formatted(id,Instant.now());
        mvc.perform(post("/api/agent/sync-confirmations").header("X-Agent-Token",linked.agentToken()).contentType(MediaType.APPLICATION_JSON).content(confirmation)).andExpect(status().isOk()).andExpect(jsonPath("$.aceptada").value(true));
        assertThat(dispositivos.findById(linked.deviceId()).orElseThrow().getUltimaSincronizacion()).isNotNull();
        mvc.perform(post("/api/agent/sync-confirmations").header("X-Agent-Token",linked.agentToken()).contentType(MediaType.APPLICATION_JSON).content(confirmation)).andExpect(status().isOk()).andExpect(jsonPath("$.aceptada").value(false));
    }

    @Test void heartbeatVencidoMuestraDispositivoDesconectado() throws Exception{
        Vinculado linked=vinculado();var device=dispositivos.findById(linked.deviceId()).orElseThrow();device.setUltimoHeartbeat(Instant.now().minusSeconds(200));device.setIntervaloHeartbeatSegundos(30);dispositivos.saveAndFlush(device);
        mvc.perform(get("/api/devices").header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken()))).andExpect(status().isOk()).andExpect(jsonPath("$[0].conectado").value(false));
    }

    @Test
    void inventarioExigeTokenDeAgente() throws Exception {
        mvc.perform(post("/api/agent/inventory").contentType(MediaType.APPLICATION_JSON)
                        .content(inventoryBody())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", "token-invalido")
                        .contentType(MediaType.APPLICATION_JSON).content(inventoryBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sincronizaInventarioProcesosYVentanaActivaReales() throws Exception {
        Vinculado linked = vinculado();
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", linked.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content(inventoryBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CONECTADO"));
        var apps = aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(
                linked.cuenta().familiaId(), linked.deviceId());
        assertThat(apps).singleElement().satisfies(app -> {
            assertThat(app.getNombre()).isEqualTo("Editor Real");
            assertThat(app.isEnEjecucion()).isTrue();
            assertThat(app.isVentanaActiva()).isTrue();
            assertThat(app.getUltimoPid()).isEqualTo(4242L);
        });
        assertThat(procesos.findAllByDispositivo_Id(linked.deviceId())).singleElement()
                .satisfies(process -> assertThat(process.isVentanaActiva()).isTrue());
    }

    @Test
    void consolidaAplicacionesYPidsDuplicadosEnUnaSincronizacion() throws Exception {
        Vinculado linked = vinculado();
        String duplicate = """
                {"aplicaciones":[
                  {"nombre":"Editor Real","rutaEjecutable":"C:\\\\Apps\\\\editor.exe","version":"1","editor":"FG","tipoInstalacion":"REGISTRY"},
                  {"nombre":"Editor Real","rutaEjecutable":"C:\\\\Apps\\\\editor.exe","version":"1","editor":"FG","tipoInstalacion":"REGISTRY"}],
                 "procesos":[
                  {"pid":4242,"nombre":"Editor Real","rutaEjecutable":"C:\\\\Apps\\\\editor.exe","ventanaActiva":true},
                  {"pid":4242,"nombre":"Editor Real","rutaEjecutable":"C:\\\\Apps\\\\editor.exe","ventanaActiva":true}],
                 "observadoEn":"2026-11-15T12:00:00Z"}
                """;
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", linked.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate)).andExpect(status().isOk());
        assertThat(aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(
                linked.cuenta().familiaId(), linked.deviceId())).hasSize(1);
        assertThat(procesos.findAllByDispositivo_Id(linked.deviceId())).hasSize(1);
    }

    @Test
    void revocacionInvalidaInmediatamenteElTokenDelAgente() throws Exception {
        Vinculado linked = vinculado();
        mvc.perform(delete("/api/devices/{id}", linked.deviceId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(linked.cuenta().accessToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/agent/status").header("X-Agent-Token", linked.agentToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void limitaSincronizacionesRepetidasDelMismoDispositivo() throws Exception {
        Vinculado linked = vinculado();
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", linked.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content(inventoryBody()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", linked.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content(inventoryBody()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void dispositivoVinculadoApareceSoloEnSuFamilia() throws Exception {
        Vinculado linked = vinculado();
        Cuenta ajena = cuenta();
        mvc.perform(get("/api/devices").header(HttpHeaders.AUTHORIZATION, bearer(linked.cuenta().accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(linked.deviceId()));
        mvc.perform(get("/api/devices").header(HttpHeaders.AUTHORIZATION, bearer(ajena.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void registraIntervaloDeUsoConIdentificadorUnicoYMetadatosDeFecha() throws Exception {
        Vinculado linked = vinculado(); UUID id = UUID.randomUUID();
        mvc.perform(post("/api/agent/usage").header("X-Agent-Token", linked.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content(usageBody(id, "chrome.exe", 30)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(1))
                .andExpect(jsonPath("$.confirmados[0]").value(id.toString()));
        assertThat(usos.findAll()).anySatisfy(usage -> {
            assertThat(usage.getRegistroUuid()).isEqualTo(id.toString());
            assertThat(usage.getDuracionSegundos()).isEqualTo(30);
            assertThat(usage.getFechaLocal()).isNotNull();
            assertThat(usage.getZonaHoraria()).isEqualTo("America/Lima");
        });
    }

    @Test
    void sincronizacionDeUsoEsIdempotente() throws Exception {
        Vinculado linked = vinculado(); UUID id = UUID.randomUUID();
        String body = usageBody(id, "msedge.exe", 20);
        mvc.perform(post("/api/agent/usage").header("X-Agent-Token", linked.agentToken())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/agent/usage").header("X-Agent-Token", linked.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(0))
                .andExpect(jsonPath("$.confirmados[0]").value(id.toString()));
        assertThat(usos.findAll().stream().filter(u -> id.toString().equals(u.getRegistroUuid()))).hasSize(1);
    }

    @Test
    void reporteAcumulaSesionesYPermiteFiltrarPorAplicacion() throws Exception {
        Vinculado linked = vinculado();
        syncUsage(linked, UUID.randomUUID(), "chrome.exe", 12);
        syncUsage(linked, UUID.randomUUID(), "chrome.exe", 18);
        syncUsage(linked, UUID.randomUUID(), "msedge.exe", 9);
        Long chromeId = aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(
                        linked.cuenta().familiaId(), linked.deviceId()).stream()
                .filter(app -> app.getNombre().equals("chrome.exe")).findFirst().orElseThrow().getId();
        String today = LocalDate.now(java.time.ZoneId.of("America/Lima")).toString();
        mvc.perform(get("/api/reports/usage").param("desde", today).param("hasta", today)
                        .param("aplicacionId", chromeId.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(linked.cuenta().accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tiempoTotalSegundos").value(30))
                .andExpect(jsonPath("$.rankingAplicaciones[0].nombre").value("chrome.exe"));
    }

    @Test
    void usoDeUnDispositivoNoEsVisibleParaOtraFamilia() throws Exception {
        Vinculado linked = vinculado(); syncUsage(linked, UUID.randomUUID(), "discord.exe", 15);
        Cuenta other = cuenta(); String today = LocalDate.now(java.time.ZoneId.of("America/Lima")).toString();
        mvc.perform(get("/api/reports/usage").param("desde", today).param("hasta", today)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tiempoTotalSegundos").value(0));
    }

    @Test
    void rechazaIntervalosInconsistentesYTokenInvalido() throws Exception {
        Vinculado linked = vinculado();
        UUID inconsistent = UUID.randomUUID();
        String body = usageBody(inconsistent, "word.exe", 30).replace("\"duracionSegundos\":30",
                "\"duracionSegundos\":5");
        // Un registro inconsistente ya no rechaza el lote: se informa y se retira de la cola.
        mvc.perform(post("/api/agent/usage").header("X-Agent-Token", linked.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(0))
                .andExpect(jsonPath("$.rechazados[0].registroId").value(inconsistent.toString()))
                .andExpect(jsonPath("$.rechazados[0].codigo").value("DURACION_INCONSISTENTE"))
                .andExpect(jsonPath("$.confirmados[0]").value(inconsistent.toString()));
        assertThat(usos.existsByRegistroUuid(inconsistent.toString())).isFalse();
        mvc.perform(post("/api/agent/usage").header("X-Agent-Token", "invalido")
                        .contentType(MediaType.APPLICATION_JSON).content(usageBody(UUID.randomUUID(), "word.exe", 30)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creaModificaDesbloqueaReactivaYEliminaRestriccion() throws Exception {
        Vinculado linked=vinculado(); sincronizarInventario(linked);
        long appId=aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(linked.cuenta().familiaId(),linked.deviceId()).get(0).getId();
        String created=mvc.perform(post("/api/restrictions").header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken()))
                .contentType(MediaType.APPLICATION_JSON).content(restrictionBody(linked.cuenta().childId(),appId,60,false)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.restanteSegundos").value(3600))
                .andReturn().getResponse().getContentAsString();
        long id=((Number)JsonPath.read(created,"$.id")).longValue();
        mvc.perform(put("/api/restrictions/{id}",id).header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken()))
                .contentType(MediaType.APPLICATION_JSON).content(restrictionBody(linked.cuenta().childId(),appId,30,true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bloqueoTotal").value(true));
        mvc.perform(patch("/api/restrictions/{id}/active",id).param("value","false")
                        .header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(false));
        mvc.perform(patch("/api/restrictions/{id}/active",id).param("value","true")
                        .header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(true));
        mvc.perform(delete("/api/restrictions/{id}",id).header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/restrictions").header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == %s)]".formatted(id)).isEmpty());
    }

    @Test
    void agenteDescargaReglasYEventosSonIdempotentes() throws Exception {
        Vinculado linked=vinculado();sincronizarInventario(linked);long appId=aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(linked.cuenta().familiaId(),linked.deviceId()).get(0).getId();
        String created=mvc.perform(post("/api/restrictions").header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content(restrictionBody(linked.cuenta().childId(),appId,60,false))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id=((Number)JsonPath.read(created,"$.id")).longValue();
        mvc.perform(get("/api/agent/rules").header("X-Agent-Token",linked.agentToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.reglas[0].rutaEjecutable").value("C:\\Apps\\editor.exe"));
        UUID event=UUID.randomUUID();String body="""
                {"eventos":[{"eventoId":"%s","restriccionId":%d,"tipo":"BLOQUEO","detalle":"Prueba","ocurridoEn":"%s","exitoso":true}]}
                """.formatted(event,id,Instant.now());
        mvc.perform(post("/api/agent/restriction-events").header("X-Agent-Token",linked.agentToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(1));
        mvc.perform(post("/api/agent/restriction-events").header("X-Agent-Token",linked.agentToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(0));
    }

    @Test
    void unaFamiliaNoPuedeRestringirAplicacionAjena() throws Exception {
        Vinculado owner=vinculado();sincronizarInventario(owner);long appId=aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(owner.cuenta().familiaId(),owner.deviceId()).get(0).getId();Cuenta other=cuenta();
        mvc.perform(post("/api/restrictions").header(HttpHeaders.AUTHORIZATION,bearer(other.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content(restrictionBody(other.childId(),appId,60,false))).andExpect(status().isNotFound());
    }

    @Test void creaEditaDesactivaYDescargaReglaWeb() throws Exception{
        Vinculado linked=vinculado();String created=mvc.perform(post("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken())).contentType(MediaType.APPLICATION_JSON).content(webRuleBody(linked, "HTTPS://Example.COM/ruta",true))).andExpect(status().isCreated()).andExpect(jsonPath("$.dominio").value("example.com")).andReturn().getResponse().getContentAsString();long id=((Number)JsonPath.read(created,"$.id")).longValue();
        mvc.perform(get("/api/agent/web-rules").header("X-Agent-Token",linked.agentToken())).andExpect(status().isOk()).andExpect(jsonPath("$.reglas[0].dominio").value("example.com"));
        mvc.perform(put("/api/web-restrictions/{id}",id).header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken())).contentType(MediaType.APPLICATION_JSON).content(webRuleBody(linked,"example.org",false))).andExpect(status().isOk()).andExpect(jsonPath("$.dominio").value("example.org"));
        mvc.perform(delete("/api/web-restrictions/{id}",id).header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken()))).andExpect(status().isNoContent());
    }
    @Test void rechazaRutasInvalidasYCategoriasSinFuente() throws Exception{Vinculado linked=vinculado();mvc.perform(post("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken())).contentType(MediaType.APPLICATION_JSON).content(webRuleBody(linked,"localhost",true))).andExpect(status().isConflict());mvc.perform(post("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken())).contentType(MediaType.APPLICATION_JSON).content(webRuleBody(linked,"example.net",true).replace("PERSONALIZADO","APUESTAS"))).andExpect(status().isConflict());}
    @Test void reglaWebAisladaYEventosIdempotentes() throws Exception{Vinculado linked=vinculado();String created=mvc.perform(post("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION,bearer(linked.cuenta().accessToken())).contentType(MediaType.APPLICATION_JSON).content(webRuleBody(linked,"example.net",true))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();long id=((Number)JsonPath.read(created,"$.id")).longValue();Cuenta other=cuenta();mvc.perform(get("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION,bearer(other.accessToken()))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));UUID uuid=UUID.randomUUID();String event="{\"eventos\":[{\"eventoId\":\"%s\",\"reglaId\":%d,\"dominio\":\"example.net\",\"tipo\":\"FILTRO_APLICADO\",\"detalle\":\"hosts\",\"ocurridoEn\":\"%s\",\"exitoso\":true}]}".formatted(uuid,id,Instant.now());mvc.perform(post("/api/agent/web-events").header("X-Agent-Token",linked.agentToken()).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(1));mvc.perform(post("/api/agent/web-events").header("X-Agent-Token",linked.agentToken()).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isOk()).andExpect(jsonPath("$.aceptados").value(0));}

    private static String webRuleBody(Vinculado linked,String domain,boolean permanent){return """
      {"menorId":%d,"dispositivoIds":[%d],"dominio":"%s","incluirSubdominios":true,"categoria":"PERSONALIZADO","modo":"BLOQUEAR","permanente":%s,"vigenteHasta":%s,"motivo":"estudio","horarios":[{"dia":"MONDAY","inicio":"08:00","fin":"10:00","tipo":"BLOQUEAR"}]}
      """.formatted(linked.cuenta().childId(),linked.deviceId(),domain,permanent,permanent?"null":"\"2027-01-01T00:00:00Z\"");}

    private void sincronizarInventario(Vinculado linked)throws Exception{mvc.perform(post("/api/agent/inventory").header("X-Agent-Token",linked.agentToken()).contentType(MediaType.APPLICATION_JSON).content(inventoryBody())).andExpect(status().isOk());}
    private static String restrictionBody(long child,long app,int minutes,boolean full){return """
            {"menorId":%d,"aplicacionId":%d,"minutosDiarios":%d,"bloqueoTotal":%s,"advertenciaMinutos":5,
             "horarios":[{"dia":"MONDAY","inicio":"08:00","fin":"10:00","tipo":"BLOQUEAR"}]}
            """.formatted(child,app,minutes,full);}

    private Vinculado vinculado() throws Exception {
        Cuenta cuenta = cuenta();
        String code = JsonPath.read(generarCodigo(cuenta), "$.codigo");
        String result = mvc.perform(post("/api/agent/link").contentType(MediaType.APPLICATION_JSON)
                        .content(linkBody(code, "agent-" + UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Vinculado(cuenta, ((Number) JsonPath.read(result, "$.dispositivoId")).longValue(),
                JsonPath.read(result, "$.agenteToken"));
    }

    private String generarCodigo(Cuenta cuenta) throws Exception {
        return mvc.perform(post("/api/children/{id}/device-link-codes", cuenta.childId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(cuenta.accessToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private Cuenta cuenta() throws Exception {
        String email = "agent-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Padre Agente","email":"%s","password":"%s",
                 "confirmarPassword":"%s","nombreFamilia":"Familia Agente"}
                """.formatted(email, PASSWORD, PASSWORD))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        Menor child = menores.save(new Menor(user.getFamilia(), "Hijo Agente"));
        return new Cuenta(JsonPath.read(login, "$.accessToken"), user.getFamilia().getId(), child.getId());
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
    private void syncUsage(Vinculado linked, UUID id, String app, long seconds) throws Exception {
        mvc.perform(post("/api/agent/usage").header("X-Agent-Token", linked.agentToken())
                .contentType(MediaType.APPLICATION_JSON).content(usageBody(id, app, seconds)))
                .andExpect(status().isOk());
    }
    private static String usageBody(UUID id, String app, long seconds) {
        Instant end = Instant.now().minusSeconds(5);
        Instant start = end.minusSeconds(seconds);
        return """
                {"registros":[{"registroId":"%s","aplicacionNombre":"%s",
                  "rutaEjecutable":"C:\\\\Apps\\\\%s","pid":4242,"inicioEn":"%s",
                  "finEn":"%s","duracionSegundos":%d}]}
                """.formatted(id, app, app, start, end, seconds);
    }
    private static String bearer(String token) { return "Bearer " + token; }
    private record Cuenta(String accessToken, Long familiaId, Long childId) { }
    private record Vinculado(Cuenta cuenta, long deviceId, String agentToken) { }
}
