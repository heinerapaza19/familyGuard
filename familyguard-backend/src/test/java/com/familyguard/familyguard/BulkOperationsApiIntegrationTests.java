package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.model.SitioBloqueado;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.SitioBloqueadoRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Etapa 4: restricciones masivas. Escenario de Heiner: Luz, Mateo y Ana con varias computadoras.
 * Comprueba estados individuales verificables, idempotencia, concurrencia, conservación de reglas y
 * aislamiento entre familias. Usa H2 (perfil test), nunca la base MySQL real.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class BulkOperationsApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired MenorRepository menores;
    @Autowired DispositivoRepository dispositivos;
    @Autowired SitioBloqueadoRepository sitios;
    @Autowired AplicacionRepository aplicaciones;
    @Autowired JdbcTemplate jdbc;

    // 1. Una sola computadora: pendiente de entrega → recibida → aplicada (solo con confirmación real).
    @Test
    void unaComputadoraPasaPorEntregaRecepcionYAplicacionReal() throws Exception {
        Familia f = familia("Familia Una", "Luz");
        Equipo laptop = vincular(f, f.hijo("Luz"), "Laptop Luz 1");
        heartbeat(laptop);

        String op = ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", laptop)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.objetivo").value("tiktok.com"))
                .andExpect(jsonPath("$.dispositivos[0].resultado").value("PROGRAMADA"))
                .andExpect(jsonPath("$.dispositivos[0].estado").value("PENDIENTE_ENTREGA"))
                .andExpect(jsonPath("$.completa").value(false))
                .andReturn().getResponse().getContentAsString();
        long id = id(op);

        descargarReglasWeb(laptop);
        consultar(f, id).andExpect(jsonPath("$.dispositivos[0].estado").value("RECIBIDA"))
                .andExpect(jsonPath("$.completa").value(false));

        eventoWeb(laptop, regla(f, "Luz", "tiktok.com"), "FILTRO_APLICADO", true);
        consultar(f, id).andExpect(jsonPath("$.dispositivos[0].estado").value("APLICADA"))
                .andExpect(jsonPath("$.completa").value(true))
                .andExpect(jsonPath("$.mensaje").value("Aplicada en los 1 equipos seleccionados."));
    }

    // 2 y 6. Varias computadoras de varios hijos: una regla por hijo, estado individual y errores visibles.
    @Test
    void variasComputadorasConEstadosIndividualesYErroresSinTodoAplicado() throws Exception {
        Familia f = familia("Familia Varias", "Luz", "Mateo");
        Equipo luz1 = vincular(f, f.hijo("Luz"), "Laptop Luz 1");
        Equipo luz2 = vincular(f, f.hijo("Luz"), "Laptop Luz 2");
        Equipo mateo = vincular(f, f.hijo("Mateo"), "PC Mateo");
        heartbeat(luz1);
        heartbeat(mateo);
        String body = sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "instagram.com", luz1, luz2, mateo);

        previsualizar(f, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.conectados").value(2))
                .andExpect(jsonPath("$.desconectados").value(1))
                .andExpect(jsonPath("$.mensaje").value(containsString(
                        "Vas a bloquear instagram.com en 3 computadoras (2 conectadas, 1 desconectada).")));
        // La vista previa no modifica nada.
        assertThat(sitios.findByFamilia_IdAndMenor_IdAndDominio(f.familiaId(), f.hijo("Luz"), "instagram.com")).isEmpty();

        long id = id(ejecutar(f, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(asignados(f, "Luz", "instagram.com")).isEqualTo(2);
        assertThat(asignados(f, "Mateo", "instagram.com")).isEqualTo(1);

        descargarReglasWeb(luz1);
        eventoWeb(luz1, regla(f, "Luz", "instagram.com"), "FILTRO_APLICADO", true);
        descargarReglasWeb(mateo);
        eventoWeb(mateo, regla(f, "Mateo", "instagram.com"), "FILTRO_ERROR", false);

        String resultado = consultar(f, id).andExpect(jsonPath("$.completa").value(false))
                .andExpect(jsonPath("$.resumen.APLICADA").value(1))
                .andExpect(jsonPath("$.resumen.ERROR").value(1))
                .andExpect(jsonPath("$.resumen.DESCONECTADO").value(1))
                .andExpect(jsonPath("$.mensaje").value(containsString("con error")))
                .andReturn().getResponse().getContentAsString();
        assertThat(estado(resultado, luz1)).isEqualTo("APLICADA");
        assertThat(estado(resultado, luz2)).isEqualTo("DESCONECTADO");
        assertThat(estado(resultado, mateo)).isEqualTo("ERROR");
    }

    // 3 y 4. Todas las computadoras de un hijo y luego toda la familia, sin revisiones repetidas.
    @Test
    void todosLosEquiposDeUnHijoYDespuesTodaLaFamiliaSinRevisionesRepetidas() throws Exception {
        Familia f = familia("Familia Heiner", "Luz", "Mateo", "Ana");
        List<Equipo> luz = new ArrayList<>(), todos = new ArrayList<>();
        for (int i = 1; i <= 4; i++) luz.add(vincular(f, f.hijo("Luz"), "Laptop Luz " + i));
        todos.addAll(luz);
        for (int i = 1; i <= 3; i++) todos.add(vincular(f, f.hijo("Mateo"), "PC Mateo " + i));
        for (int i = 1; i <= 3; i++) todos.add(vincular(f, f.hijo("Ana"), "Laptop Ana " + i));

        ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", luz.toArray(Equipo[]::new)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.dispositivos[*].resultado").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("PROGRAMADA"))));
        assertThat(asignados(f, "Luz", "tiktok.com")).isEqualTo(4);
        assertThat(sitios.findByFamilia_IdAndMenor_IdAndDominio(f.familiaId(), f.hijo("Mateo"), "tiktok.com")).isEmpty();
        Map<Long, Long> revisionesLuz = revisiones(luz);

        String familia = ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", todos.toArray(Equipo[]::new)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.total").value(10))
                .andReturn().getResponse().getContentAsString();
        for (Equipo e : luz) assertThat(resultado(familia, e)).isEqualTo("SIN_CAMBIOS");
        for (Equipo e : todos.subList(4, 10)) assertThat(resultado(familia, e)).isEqualTo("PROGRAMADA");
        assertThat(revisiones(luz)).isEqualTo(revisionesLuz);
        assertThat(asignados(f, "Luz", "tiktok.com")).isEqualTo(4);
        assertThat(asignados(f, "Mateo", "tiktok.com")).isEqualTo(3);
        assertThat(asignados(f, "Ana", "tiktok.com")).isEqualTo(3);
    }

    // 5. Un equipo desconectado recibe la política al volver a comunicarse.
    @Test
    void equipoDesconectadoRecibeLaPoliticaAlReconectarse() throws Exception {
        Familia f = familia("Familia Desconectada", "Ana");
        Equipo ana = vincular(f, f.hijo("Ana"), "Laptop Ana");
        long id = id(ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "facebook.com", ana))
                .andExpect(jsonPath("$.dispositivos[0].estado").value("DESCONECTADO"))
                .andReturn().getResponse().getContentAsString());
        heartbeat(ana);
        consultar(f, id).andExpect(jsonPath("$.dispositivos[0].estado").value("PENDIENTE_ENTREGA"));
        String reglas = mvc.perform(get("/api/agent/web-rules").header("X-Agent-Token", ana.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(reglas, "$.reglas[*].dominio")).contains("facebook.com");
        consultar(f, id).andExpect(jsonPath("$.dispositivos[0].estado").value("RECIBIDA"));
    }

    // 7. Reintentos de la misma operación: no duplican ni incrementan revisiones; otro contenido → 409.
    @Test
    void reintentoConElMismoIdentificadorEsIdempotente() throws Exception {
        Familia f = familia("Familia Reintento", "Luz");
        Equipo laptop = vincular(f, f.hijo("Luz"), "Laptop Luz");
        UUID operacion = UUID.randomUUID();
        String body = sitio(operacion, "BLOQUEAR_SITIO", "tiktok.com", laptop);
        long primero = id(ejecutar(f, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long revision = dispositivos.findById(laptop.id()).orElseThrow().getRevisionDeseada();
        long reglaRevision = regla(f, "Luz", "tiktok.com").getRevision();

        long segundo = id(ejecutar(f, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(segundo).isEqualTo(primero);
        assertThat(dispositivos.findById(laptop.id()).orElseThrow().getRevisionDeseada()).isEqualTo(revision);
        assertThat(regla(f, "Luz", "tiktok.com").getRevision()).isEqualTo(reglaRevision);
        assertThat(operaciones(f)).isEqualTo(1);

        ejecutar(f, sitio(operacion, "BLOQUEAR_SITIO", "instagram.com", laptop)).andExpect(status().isConflict());
        assertThat(sitios.findByFamilia_IdAndMenor_IdAndDominio(f.familiaId(), f.hijo("Luz"), "instagram.com")).isEmpty();
    }

    // 8. Operaciones simultáneas: mismo identificador y operaciones distintas sobre el mismo equipo.
    @Test
    void operacionesSimultaneasNoDuplicanReglasNiRevisiones() throws Exception {
        Familia f = familia("Familia Simultanea", "Luz");
        Equipo laptop = vincular(f, f.hijo("Luz"), "Laptop Luz");
        long antes = dispositivos.findById(laptop.id()).orElseThrow().getRevisionDeseada();

        String mismo = sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", laptop);
        List<Integer> estados = simultaneas(() -> ejecutar(f, mismo).andReturn().getResponse().getStatus(),
                () -> ejecutar(f, mismo).andReturn().getResponse().getStatus());
        assertThat(estados).containsExactlyInAnyOrder(201, 200);
        assertThat(operaciones(f)).isEqualTo(1);

        List<String> respuestas = simultaneas(
                () -> ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "roblox.com", laptop)).andReturn().getResponse().getContentAsString(),
                () -> ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "roblox.com", laptop)).andReturn().getResponse().getContentAsString());
        List<String> resultados = respuestas.stream().map(r -> resultado(r, laptop)).toList();
        assertThat(resultados).containsExactlyInAnyOrder("PROGRAMADA", "SIN_CAMBIOS");
        assertThat(asignados(f, "Luz", "roblox.com")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sitios_bloqueados where familia_id=? and dominio='roblox.com'",
                Integer.class, f.familiaId())).isEqualTo(1);
        assertThat(dispositivos.findById(laptop.id()).orElseThrow().getRevisionDeseada()).isEqualTo(antes + 2);
    }

    // 9. Prevención de duplicados y rechazo de reglas con otra configuración, sin cambios parciales.
    @Test
    void evitaDuplicadosYNoAlteraReglasConHorario() throws Exception {
        Familia f = familia("Familia Duplicados", "Luz");
        Equipo laptop = vincular(f, f.hijo("Luz"), "Laptop Luz");
        ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", laptop, laptop))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.total").value(1));
        // Regla creada desde «Sitios web» con el mismo dominio: no se duplica.
        reglaNormal(f, "Luz", laptop, "youtube.com", "[]");
        long revision = dispositivos.findById(laptop.id()).orElseThrow().getRevisionDeseada();
        ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "youtube.com", laptop))
                .andExpect(jsonPath("$.dispositivos[0].resultado").value("SIN_CAMBIOS"));
        assertThat(dispositivos.findById(laptop.id()).orElseThrow().getRevisionDeseada()).isEqualTo(revision);

        reglaNormal(f, "Luz", laptop, "twitch.tv",
                "[{\"dia\":\"MONDAY\",\"inicio\":\"08:00\",\"fin\":\"10:00\",\"tipo\":\"BLOQUEAR\"}]");
        Equipo otra = vincular(f, f.hijo("Luz"), "Laptop Luz 2");
        ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "twitch.tv", laptop, otra))
                .andExpect(status().isConflict());
        assertThat(asignados(f, "Luz", "twitch.tv")).isEqualTo(1);
        assertThat(operaciones(f)).isEqualTo(2);
    }

    // 10. Aislamiento entre familias: nada se modifica si se incluye un equipo ajeno.
    @Test
    void aislamientoEntreFamilias() throws Exception {
        Familia a = familia("Familia A", "Luz");
        Familia b = familia("Familia B", "Mateo");
        Equipo deA = vincular(a, a.hijo("Luz"), "Laptop de A");
        Equipo deB = vincular(b, b.hijo("Mateo"), "PC de B");
        long revisionB = dispositivos.findById(deB.id()).orElseThrow().getRevisionDeseada();

        previsualizar(b, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", deA)).andExpect(status().isNotFound());
        ejecutar(b, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", deA)).andExpect(status().isNotFound());
        ejecutar(b, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", deB, deA)).andExpect(status().isNotFound());
        assertThat(sitios.findByFamilia_IdAndMenor_IdAndDominio(b.familiaId(), b.hijo("Mateo"), "tiktok.com")).isEmpty();
        assertThat(dispositivos.findById(deB.id()).orElseThrow().getRevisionDeseada()).isEqualTo(revisionB);
        assertThat(operaciones(b)).isZero();

        long idA = id(ejecutar(a, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", deA))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/bulk-operations/{id}", idA).header(HttpHeaders.AUTHORIZATION, bearer(b.token())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/bulk-operations/{id}/cancel", idA).header(HttpHeaders.AUTHORIZATION, bearer(b.token())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/bulk-operations").header(HttpHeaders.AUTHORIZATION, bearer(b.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        // Una aplicación de referencia de otra familia tampoco se acepta.
        inventario(a, deA, "C:\\Juegos\\Roblox\\RobloxPlayerBeta.exe");
        long appA = app(a, deA, "robloxplayerbeta.exe");
        ejecutar(b, aplicacion(UUID.randomUUID(), "LIMITAR_APLICACION", appA, 60, deB)).andExpect(status().isNotFound());
        mvc.perform(post("/api/bulk-operations").contentType(MediaType.APPLICATION_JSON)
                .content(sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", deB))).andExpect(status().isUnauthorized());
    }

    // 11. Validación y normalización de dominios y de la solicitud.
    @Test
    void validaYNormalizaDominiosYLimites() throws Exception {
        Familia f = familia("Familia Dominios", "Luz");
        Equipo laptop = vincular(f, f.hijo("Luz"), "Laptop Luz");
        ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "HTTPS://TikTok.com/foryou?x=1", laptop))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.objetivo").value("tiktok.com"));
        for (String invalido : List.of("no es un dominio", "localhost", "microsoft.com", "", "http://"))
            ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", invalido, laptop)).andExpect(status().isConflict());
        ejecutar(f, sitio(UUID.randomUUID(), "DESCONOCIDO", "tiktok.com", laptop)).andExpect(status().isConflict());
        ejecutar(f, "{\"operacionId\":\"%s\",\"tipo\":\"BLOQUEAR_SITIO\",\"dispositivoIds\":[],\"dominio\":\"a.com\"}"
                .formatted(UUID.randomUUID())).andExpect(status().isBadRequest());
        ejecutar(f, "{\"tipo\":\"BLOQUEAR_SITIO\",\"dispositivoIds\":[%d],\"dominio\":\"a.com\"}".formatted(laptop.id()))
                .andExpect(status().isBadRequest());
        String demasiados = java.util.stream.LongStream.rangeClosed(1, 201).mapToObj(Long::toString).collect(Collectors.joining(","));
        ejecutar(f, "{\"operacionId\":\"%s\",\"tipo\":\"BLOQUEAR_SITIO\",\"dispositivoIds\":[%s],\"dominio\":\"a.com\"}"
                .formatted(UUID.randomUUID(), demasiados)).andExpect(status().isBadRequest());
    }

    // 12. Aplicaciones no instaladas o ambiguas: no se inventan rutas ni se crean reglas en esos equipos.
    @Test
    void aplicacionNoRegistradaOAmbiguaNoCreaReglas() throws Exception {
        Familia f = familia("Familia Roblox", "Luz");
        Equipo conRoblox = vincular(f, f.hijo("Luz"), "Laptop Luz 1");
        Equipo sinRoblox = vincular(f, f.hijo("Luz"), "Laptop Luz 2");
        Equipo dosRutas = vincular(f, f.hijo("Luz"), "Laptop Luz 3");
        inventario(f, conRoblox, "C:\\Users\\luz\\AppData\\Local\\Roblox\\Versions\\version-a\\RobloxPlayerBeta.exe");
        inventario(f, dosRutas, "C:\\Users\\luz\\AppData\\Local\\Roblox\\Versions\\version-a\\RobloxPlayerBeta.exe",
                "D:\\Otros\\RobloxPlayerBeta.exe");
        long referencia = app(f, conRoblox, "robloxplayerbeta.exe");
        String body = aplicacion(UUID.randomUUID(), "LIMITAR_APLICACION", referencia, 120, conRoblox, sinRoblox, dosRutas);

        previsualizar(f, body).andExpect(jsonPath("$.programados").value(1)).andExpect(jsonPath("$.noAplicables").value(2))
                .andExpect(jsonPath("$.mensaje").value(containsString("limitar robloxplayerbeta.exe a 2 h diarios")));
        String op = ejecutar(f, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(resultado(op, conRoblox)).isEqualTo("PROGRAMADA");
        assertThat(resultado(op, sinRoblox)).isEqualTo("APLICACION_NO_REGISTRADA");
        assertThat(resultado(op, dosRutas)).isEqualTo("APLICACION_AMBIGUA");
        assertThat(limitesDe(f, sinRoblox)).isZero();
        assertThat(limitesDe(f, dosRutas)).isZero();
        assertThat(limitesDe(f, conRoblox)).isEqualTo(1);

        List<Number> objetivos = JsonPath.read(op, "$.dispositivos[?(@.dispositivoId==" + conRoblox.id() + ")].revisionObjetivo");
        long objetivo = objetivos.get(0).longValue();
        confirmar(conRoblox, objetivo, "APLICADA");
        consultar(f, id(op)).andExpect(jsonPath("$.completa").value(false))
                .andExpect(jsonPath("$.resumen.APLICADA").value(1))
                .andExpect(jsonPath("$.mensaje").value(containsString("sin aplicar")));
        ejecutar(f, aplicacion(UUID.randomUUID(), "LIMITAR_APLICACION", referencia, null, conRoblox)).andExpect(status().isConflict());
    }

    // 13. Conservación de reglas anteriores al bloquear, retirar y limitar.
    @Test
    void conservaReglasAnterioresAlRetirarYLimitar() throws Exception {
        Familia f = familia("Familia Conserva", "Luz");
        Equipo luz1 = vincular(f, f.hijo("Luz"), "Laptop Luz 1");
        Equipo luz2 = vincular(f, f.hijo("Luz"), "Laptop Luz 2");
        reglaNormal(f, "Luz", luz1, "example.org", "[]");
        ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", luz1, luz2)).andExpect(status().isCreated());

        String retiro = ejecutar(f, sitio(UUID.randomUUID(), "RETIRAR_SITIO", "tiktok.com", luz1))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.dispositivos[0].resultado").value("PROGRAMADA"))
                .andReturn().getResponse().getContentAsString();
        assertThat(asignados(f, "Luz", "tiktok.com")).isEqualTo(1);
        assertThat(regla(f, "Luz", "tiktok.com").isActivo()).isTrue();
        assertThat(regla(f, "Luz", "example.org").isActivo()).isTrue();
        assertThat(asignados(f, "Luz", "example.org")).isEqualTo(1);
        long objetivo = ((Number) JsonPath.read(retiro, "$.dispositivos[0].revisionObjetivo")).longValue();
        confirmar(luz1, objetivo, "APLICADA");
        consultar(f, id(retiro)).andExpect(jsonPath("$.dispositivos[0].estado").value("APLICADA"));
        ejecutar(f, sitio(UUID.randomUUID(), "RETIRAR_SITIO", "tiktok.com", luz1))
                .andExpect(jsonPath("$.dispositivos[0].resultado").value("SIN_CAMBIOS"));

        // Límite existente con horario: la operación masiva solo cambia los minutos y conserva el horario.
        inventario(f, luz1, "C:\\Apps\\editor.exe");
        inventario(f, luz2, "C:\\Apps\\editor.exe");
        long editor1 = app(f, luz1, "editor.exe");
        long editor2 = app(f, luz2, "editor.exe");
        limiteNormal(f, "Luz", editor1, 60);
        limiteNormal(f, "Luz", editor2, 45);
        ejecutar(f, aplicacion(UUID.randomUUID(), "LIMITAR_APLICACION", editor1, 30, luz1))
                .andExpect(jsonPath("$.dispositivos[0].resultado").value("PROGRAMADA"));
        String limites = restricciones(f, luz1);
        assertThat(JsonPath.<List<Integer>>read(limites, "$[*].minutosDiarios")).containsExactly(30);
        assertThat(JsonPath.<List<Object>>read(limites, "$[0].horarios")).hasSize(1);

        ejecutar(f, aplicacion(UUID.randomUUID(), "RETIRAR_LIMITE", editor1, null, luz1))
                .andExpect(jsonPath("$.dispositivos[0].resultado").value("PROGRAMADA"));
        assertThat(JsonPath.<List<Object>>read(restricciones(f, luz1), "$")).isEmpty();
        assertThat(JsonPath.<List<Integer>>read(restricciones(f, luz2), "$[*].minutosDiarios")).containsExactly(45);
    }

    // Regresión (Hibernate 7): retirar quita solo la asociación regla–equipo, sin tocar otras familias.
    @Test
    void retirarDeTodosLosEquiposDesactivaLaReglaSinAfectarOtraFamilia() throws Exception {
        Familia a = familia("Familia Retiro A", "Luz");
        Familia b = familia("Familia Retiro B", "Mateo");
        Equipo luz1 = vincular(a, a.hijo("Luz"), "Laptop Luz 1");
        Equipo luz2 = vincular(a, a.hijo("Luz"), "Laptop Luz 2");
        Equipo mateo = vincular(b, b.hijo("Mateo"), "PC Mateo");
        ejecutar(a, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", luz1, luz2)).andExpect(status().isCreated());
        ejecutar(b, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", mateo)).andExpect(status().isCreated());

        ejecutar(a, sitio(UUID.randomUUID(), "RETIRAR_SITIO", "tiktok.com", luz1, luz2)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.dispositivos[*].resultado").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("PROGRAMADA"))));
        assertThat(asignados(a, "Luz", "tiktok.com")).isZero();
        assertThat(regla(a, "Luz", "tiktok.com").isActivo()).isFalse();
        assertThat(asignados(b, "Mateo", "tiktok.com")).isEqualTo(1);
        assertThat(regla(b, "Mateo", "tiktok.com").isActivo()).isTrue();

        long revision = dispositivos.findById(luz1.id()).orElseThrow().getRevisionDeseada();
        ejecutar(a, sitio(UUID.randomUUID(), "RETIRAR_SITIO", "tiktok.com", luz1))
                .andExpect(jsonPath("$.dispositivos[0].resultado").value("SIN_CAMBIOS"));
        assertThat(dispositivos.findById(luz1.id()).orElseThrow().getRevisionDeseada()).isEqualTo(revision);
    }

    // Regresión (V10): el mismo horario en varias computadoras del hijo, sin duplicados ni pérdida de horarios.
    @Test
    void mismoHorarioEnVariasComputadorasSinDuplicadosYConservado() throws Exception {
        Familia f = familia("Familia Horarios", "Luz", "Mateo");
        Equipo luz1 = vincular(f, f.hijo("Luz"), "Laptop Luz 1");
        Equipo luz2 = vincular(f, f.hijo("Luz"), "Laptop Luz 2");
        Equipo mateo = vincular(f, f.hijo("Mateo"), "PC Mateo");
        for (Equipo e : List.of(luz1, luz2, mateo)) inventario(f, e, "C:\\Juegos\\Roblox\\RobloxPlayerBeta.exe");
        long robloxLuz1 = app(f, luz1, "robloxplayerbeta.exe");
        long robloxLuz2 = app(f, luz2, "robloxplayerbeta.exe");
        long robloxMateo = app(f, mateo, "robloxplayerbeta.exe");

        // Lunes 08:00–10:00 en las dos laptops de Luz y en la PC de Mateo: se guardan los tres.
        limiteNormal(f, "Luz", robloxLuz1, 120);
        limiteNormal(f, "Luz", robloxLuz2, 120);
        limiteNormal(f, "Mateo", robloxMateo, 60);
        // Repetir el mismo horario para la misma aplicación reutiliza el existente: no se duplica.
        limiteNormal(f, "Luz", robloxLuz1, 120);
        assertThat(horarios(f, robloxLuz1)).isEqualTo(1);
        assertThat(horarios(f, robloxLuz2)).isEqualTo(1);
        assertThat(horarios(f, robloxMateo)).isEqualTo(1);

        // Límite masivo en las laptops de Luz: cambia los minutos y conserva cada horario; Mateo no cambia.
        ejecutar(f, aplicacion(UUID.randomUUID(), "LIMITAR_APLICACION", robloxLuz1, 90, luz1, luz2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dispositivos[*].resultado").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("PROGRAMADA"))));
        assertThat(horarios(f, robloxLuz1)).isEqualTo(1);
        assertThat(horarios(f, robloxLuz2)).isEqualTo(1);
        assertThat(JsonPath.<List<Integer>>read(restricciones(f, luz1), "$[*].minutosDiarios")).containsExactly(90);
        assertThat(JsonPath.<List<Integer>>read(restricciones(f, luz2), "$[*].minutosDiarios")).containsExactly(90);
        assertThat(JsonPath.<List<Integer>>read(restricciones(f, mateo), "$[*].minutosDiarios")).containsExactly(60);
        assertThat(horarios(f, robloxMateo)).isEqualTo(1);
    }

    // 14. Revocación y reconexión.
    @Test
    void revocacionYReconexionDelDispositivo() throws Exception {
        Familia f = familia("Familia Revocacion", "Luz");
        String identificador = "bulk-relink-" + UUID.randomUUID();
        Equipo laptop = vincular(f, f.hijo("Luz"), "Laptop Luz", identificador);
        heartbeat(laptop);
        long id = id(ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", laptop))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(delete("/api/devices/{id}", laptop.id()).header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isNoContent());
        consultar(f, id).andExpect(jsonPath("$.dispositivos[0].estado").value("REVOCADO"));
        ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "instagram.com", laptop)).andExpect(status().isConflict());
        assertThat(sitios.findByFamilia_IdAndMenor_IdAndDominio(f.familiaId(), f.hijo("Luz"), "instagram.com")).isEmpty();

        Equipo renovado = vincular(f, f.hijo("Luz"), "Laptop Luz", identificador);
        assertThat(renovado.id()).isEqualTo(laptop.id());
        consultar(f, id).andExpect(jsonPath("$.dispositivos[0].estado").value("DESCONECTADO"));
        heartbeat(renovado);
        descargarReglasWeb(renovado);
        consultar(f, id).andExpect(jsonPath("$.dispositivos[0].estado").value("RECIBIDA"));
    }

    // 15. El panel «Mis dispositivos» refleja los estados reales tras una operación masiva.
    @Test
    void panelReflejaEstadosRealesDeLaOperacion() throws Exception {
        Familia f = familia("Familia Panel Masivo", "Luz");
        Equipo laptop = vincular(f, f.hijo("Luz"), "Laptop Luz");
        heartbeat(laptop);
        ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", laptop)).andExpect(status().isCreated());
        detalle(f, laptop).andExpect(jsonPath("$.estadoBloqueoWeb").value("PENDIENTE"));
        descargarReglasWeb(laptop);
        eventoWeb(laptop, regla(f, "Luz", "tiktok.com"), "FILTRO_APLICADO", true);
        detalle(f, laptop).andExpect(jsonPath("$.estadoBloqueoWeb").value("APLICADO"));

        inventario(f, laptop, "C:\\Juegos\\Roblox\\RobloxPlayerBeta.exe");
        String op = ejecutar(f, aplicacion(UUID.randomUUID(), "LIMITAR_APLICACION", app(f, laptop, "robloxplayerbeta.exe"), 90, laptop))
                .andReturn().getResponse().getContentAsString();
        detalle(f, laptop).andExpect(jsonPath("$.estadoAplicaciones").value("PENDIENTE"));
        confirmar(laptop, ((Number) JsonPath.read(op, "$.dispositivos[0].revisionObjetivo")).longValue(), "APLICADA");
        detalle(f, laptop).andExpect(jsonPath("$.estadoAplicaciones").value("APLICADA"));
    }

    // Cancelación: solo en los equipos que aún no aplicaron; repetirla no modifica nada.
    @Test
    void cancelarRevierteSoloLosEquiposPendientes() throws Exception {
        Familia f = familia("Familia Cancelar", "Luz");
        Equipo luz1 = vincular(f, f.hijo("Luz"), "Laptop Luz 1");
        Equipo luz2 = vincular(f, f.hijo("Luz"), "Laptop Luz 2");
        long id = id(ejecutar(f, sitio(UUID.randomUUID(), "BLOQUEAR_SITIO", "tiktok.com", luz1, luz2))
                .andReturn().getResponse().getContentAsString());
        descargarReglasWeb(luz1);
        eventoWeb(luz1, regla(f, "Luz", "tiktok.com"), "FILTRO_APLICADO", true);

        String cancelada = cancelar(f, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CANCELADA")).andReturn().getResponse().getContentAsString();
        assertThat(estado(cancelada, luz1)).isEqualTo("APLICADA");
        assertThat(estado(cancelada, luz2)).isEqualTo("CANCELADA");
        assertThat(asignados(f, "Luz", "tiktok.com")).isEqualTo(1);
        long revision = dispositivos.findById(luz2.id()).orElseThrow().getRevisionDeseada();
        cancelar(f, id).andExpect(status().isOk());
        assertThat(dispositivos.findById(luz2.id()).orElseThrow().getRevisionDeseada()).isEqualTo(revision);

        inventario(f, luz1, "C:\\Apps\\editor.exe");
        long limite = id(ejecutar(f, aplicacion(UUID.randomUUID(), "LIMITAR_APLICACION", app(f, luz1, "editor.exe"), 30, luz1))
                .andReturn().getResponse().getContentAsString());
        cancelar(f, limite).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ utilidades

    private ResultActions ejecutar(Familia f, String body) throws Exception {
        return mvc.perform(post("/api/bulk-operations").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private ResultActions previsualizar(Familia f, String body) throws Exception {
        return mvc.perform(post("/api/bulk-operations/preview").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private ResultActions consultar(Familia f, long id) throws Exception {
        return mvc.perform(get("/api/bulk-operations/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isOk());
    }
    private ResultActions cancelar(Familia f, long id) throws Exception {
        return mvc.perform(post("/api/bulk-operations/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, bearer(f.token())));
    }
    private ResultActions detalle(Familia f, Equipo e) throws Exception {
        return mvc.perform(get("/api/devices/{id}/detail", e.id()).header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isOk());
    }

    private static String sitio(UUID operacion, String tipo, String dominio, Equipo... equipos) {
        return "{\"operacionId\":\"%s\",\"tipo\":\"%s\",\"dispositivoIds\":%s,\"dominio\":\"%s\"}"
                .formatted(operacion, tipo, ids(equipos), dominio);
    }
    private static String aplicacion(UUID operacion, String tipo, long aplicacionId, Integer minutos, Equipo... equipos) {
        return "{\"operacionId\":\"%s\",\"tipo\":\"%s\",\"dispositivoIds\":%s,\"aplicacionId\":%d,\"minutosDiarios\":%s}"
                .formatted(operacion, tipo, ids(equipos), aplicacionId, minutos == null ? "null" : minutos.toString());
    }
    private static String ids(Equipo... equipos) {
        return java.util.Arrays.stream(equipos).map(e -> Long.toString(e.id())).collect(Collectors.joining(",", "[", "]"));
    }

    private static long id(String json) { return ((Number) JsonPath.read(json, "$.id")).longValue(); }
    private static String estado(String json, Equipo e) { return campo(json, e, "estado"); }
    private static String resultado(String json, Equipo e) { return campo(json, e, "resultado"); }
    private static String campo(String json, Equipo e, String campo) {
        List<String> valores = JsonPath.read(json, "$.dispositivos[?(@.dispositivoId==" + e.id() + ")]." + campo);
        assertThat(valores).hasSize(1);
        return valores.get(0);
    }

    private SitioBloqueado regla(Familia f, String hijo, String dominio) {
        return sitios.findByFamilia_IdAndMenor_IdAndDominio(f.familiaId(), f.hijo(hijo), dominio).orElseThrow();
    }
    private int asignados(Familia f, String hijo, String dominio) {
        return jdbc.queryForObject("select count(*) from sitios_dispositivos sd join sitios_bloqueados s on s.id=sd.sitio_id"
                + " where s.familia_id=? and s.menor_id=? and s.dominio=?", Integer.class, f.familiaId(), f.hijo(hijo), dominio);
    }
    private int horarios(Familia f, long aplicacionId) {
        return jdbc.queryForObject("select count(*) from horarios_restriccion where familia_id=? and aplicacion_id=? and activo=true",
                Integer.class, f.familiaId(), aplicacionId);
    }
    private int operaciones(Familia f) {
        return jdbc.queryForObject("select count(*) from operaciones_masivas where familia_id=?", Integer.class, f.familiaId());
    }
    private int limitesDe(Familia f, Equipo e) {
        return jdbc.queryForObject("select count(*) from limites_tiempo l join aplicaciones a on a.id=l.aplicacion_id"
                + " where l.familia_id=? and a.dispositivo_id=? and l.activo=true", Integer.class, f.familiaId(), e.id());
    }
    private Map<Long, Long> revisiones(List<Equipo> equipos) {
        Map<Long, Long> valores = new LinkedHashMap<>();
        for (Equipo e : equipos) valores.put(e.id(), dispositivos.findById(e.id()).orElseThrow().getRevisionDeseada());
        return valores;
    }
    private long app(Familia f, Equipo e, String ejecutable) {
        return aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(f.familiaId(), e.id()).stream()
                .filter(a -> a.getRutaEjecutable().toLowerCase(java.util.Locale.ROOT).endsWith(ejecutable))
                .findFirst().orElseThrow().getId();
    }
    private String restricciones(Familia f, Equipo e) throws Exception {
        return mvc.perform(get("/api/restrictions").param("dispositivoId", Long.toString(e.id()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(f.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void reglaNormal(Familia f, String hijo, Equipo e, String dominio, String horarios) throws Exception {
        mvc.perform(post("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"menorId":%d,"dispositivoIds":[%d],"dominio":"%s","incluirSubdominios":true,"categoria":"PERSONALIZADO",
                         "modo":"BLOQUEAR","permanente":true,"vigenteHasta":null,"motivo":"estudio","horarios":%s}
                        """.formatted(f.hijo(hijo), e.id(), dominio, horarios))).andExpect(status().isCreated());
    }
    private void limiteNormal(Familia f, String hijo, long app, int minutos) throws Exception {
        mvc.perform(post("/api/restrictions").header(HttpHeaders.AUTHORIZATION, bearer(f.token()))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"menorId":%d,"aplicacionId":%d,"minutosDiarios":%d,"bloqueoTotal":false,"advertenciaMinutos":5,
                         "horarios":[{"dia":"MONDAY","inicio":"08:00","fin":"10:00","tipo":"BLOQUEAR"}]}
                        """.formatted(f.hijo(hijo), app, minutos))).andExpect(status().isCreated());
    }

    private void heartbeat(Equipo e) throws Exception {
        long revision = dispositivos.findById(e.id()).orElseThrow().getRevisionAplicada();
        mvc.perform(post("/api/agent/heartbeat").header("X-Agent-Token", e.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enviadoEn\":\"%s\",\"intervaloSegundos\":30,\"usuarioLocal\":\"hijo\",\"versionAgente\":\"1.0\",\"revisionAplicada\":%d}"
                        .formatted(Instant.now(), revision))).andExpect(status().isOk());
    }
    private void descargarReglasWeb(Equipo e) throws Exception {
        mvc.perform(get("/api/agent/web-rules").header("X-Agent-Token", e.token())).andExpect(status().isOk());
    }
    private void eventoWeb(Equipo e, SitioBloqueado regla, String tipo, boolean exitoso) throws Exception {
        mvc.perform(post("/api/agent/web-events").header("X-Agent-Token", e.token()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventos":[{"eventoId":"%s","reglaId":%d,"dominio":"%s","tipo":"%s","detalle":"Estado del filtro",
                          "ocurridoEn":"%s","exitoso":%s,"revision":%d}]}
                        """.formatted(UUID.randomUUID(), regla.getId(), regla.getDominio(), tipo, Instant.now().minusSeconds(1),
                        exitoso, regla.getRevision())))
                .andExpect(status().isOk());
    }
    private void confirmar(Equipo e, long revision, String estado) throws Exception {
        mvc.perform(post("/api/agent/sync-confirmations").header("X-Agent-Token", e.token())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"confirmacionId":"%s","revision":%d,"estado":"%s","detalle":"Prueba","confirmadoEn":"%s"}
                        """.formatted(UUID.randomUUID(), revision, estado, Instant.now())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aceptada").value(true));
    }
    private void inventario(Familia f, Equipo e, String... rutas) throws Exception {
        String apps = java.util.Arrays.stream(rutas).map(r -> """
                {"nombre":"%s","rutaEjecutable":"%s","version":"1.0","editor":"Prueba","tipoInstalacion":"REGISTRY"}"""
                .formatted(OperacionNombre.de(r), r.replace("\\", "\\\\"))).collect(Collectors.joining(","));
        mvc.perform(post("/api/agent/inventory").header("X-Agent-Token", e.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"aplicaciones\":[%s],\"procesos\":[],\"observadoEn\":\"%s\"}".formatted(apps, Instant.now())))
                .andExpect(status().isOk());
    }

    private Familia familia(String nombre, String... hijos) throws Exception {
        String email = "bulk-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Heiner","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"%s"}
                """.formatted(email, PASSWORD, PASSWORD, nombre))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var user = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String hijo : hijos) ids.put(hijo, menores.save(new Menor(user.getFamilia(), hijo)).getId());
        return new Familia(JsonPath.read(login, "$.accessToken"), user.getFamilia().getId(), ids);
    }
    private Equipo vincular(Familia f, long childId, String nombre) throws Exception {
        return vincular(f, childId, nombre, "bulk-" + UUID.randomUUID());
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

    @SafeVarargs
    private static <T> List<T> simultaneas(Callable<T>... tareas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tareas.length);
        CountDownLatch salida = new CountDownLatch(1);
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> tarea : tareas) futuros.add(pool.submit(() -> { salida.await(); return tarea.call(); }));
            salida.countDown();
            List<T> valores = new ArrayList<>();
            for (Future<T> futuro : futuros) valores.add(futuro.get(30, TimeUnit.SECONDS));
            return valores;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String bearer(String token) { return "Bearer " + token; }

    /** Nombre visible a partir de la ruta (solo para el inventario de prueba). */
    private static final class OperacionNombre {
        static String de(String ruta) {
            String archivo = ruta.substring(Math.max(ruta.lastIndexOf('\\'), ruta.lastIndexOf('/')) + 1);
            return archivo.replace(".exe", "");
        }
    }

    private record Familia(String token, long familiaId, Map<String, Long> hijos) {
        long hijo(String nombre) { return hijos.get(nombre); }
    }
    private record Equipo(long id, String token) { }
}
