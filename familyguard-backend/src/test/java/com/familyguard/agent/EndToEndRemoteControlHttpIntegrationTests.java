package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.familyguard.agent.DetectionModels.WebEvent;
import com.familyguard.familyguard.FamilyguardApplication;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Etapa 6: prueba integral de punta a punta con el Agente real (cliente HTTP, canal SSE y confirmaciones)
 * contra el backend real en un puerto local. Escenario: Heiner vincula dos laptops de Luz, bloquea TikTok en
 * ambas con una operación masiva, la laptop conectada recibe el aviso en tiempo real, descarga, aplica y
 * confirma; la desconectada queda pendiente; al revocar, el canal se cierra y la credencial deja de valer.
 * Las esperas están acotadas (polling con límite), sin pausas arbitrarias.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = FamilyguardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndRemoteControlHttpIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    @LocalServerPort private int port;
    @Autowired private UsuarioRepository usuarios;
    @Autowired private MenorRepository menores;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void heinerBloqueaTikTokEnLasLaptopsDeLuzYLaConectadaLoAplicaEnTiempoReal() throws Exception {
        String base = "http://127.0.0.1:" + port;
        String email = "e2e-" + UUID.randomUUID() + "@example.test";
        assertEquals(201, enviar("POST", "/api/auth/register", null, """
                {"nombre":"Heiner","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"Familia Heiner"}
                """.formatted(email, PASSWORD, PASSWORD)).statusCode());
        String sesion = JsonPath.read(enviar("POST", "/api/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)).body(), "$.accessToken");
        var usuario = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        long luz = menores.save(new Menor(usuario.getFamilia(), "Luz")).getId();
        long familiaId = usuario.getFamilia().getId();

        // Vinculación rápida de dos laptops con el cliente real del Agente.
        AgentApiClient api = new AgentApiClient();
        AgentConfigStore.Config juliaca = vincular(api, base, sesion, luz, "e2e-juliaca-" + UUID.randomUUID());
        AgentConfigStore.Config cusco = vincular(api, base, sesion, luz, "e2e-cusco-" + UUID.randomUUID());
        assertNotEquals(juliaca.agentToken(), cusco.agentToken(), "cada equipo tiene su propia credencial");

        // La laptop de Juliaca abre el canal en tiempo real; la de Cusco queda desconectada.
        BlockingQueue<Long> avisos = new LinkedBlockingQueue<>();
        List<Exception> errores = new CopyOnWriteArrayList<>();
        AgentRealtimeSync tiempoReal = new AgentRealtimeSync(juliaca.deviceId(), () -> api.openEvents(juliaca),
                avisos::add, errores::add);
        try {
            tiempoReal.start();
            Long inicial = avisos.poll(15, TimeUnit.SECONDS);
            assertNotNull(inicial, "al conectarse recibe la revisión vigente");

            // Operación masiva sobre ambas laptops.
            HttpResponse<String> operacion = enviar("POST", "/api/bulk-operations", sesion,
                    "{\"operacionId\":\"%s\",\"tipo\":\"BLOQUEAR_SITIO\",\"dispositivoIds\":[%d,%d],\"dominio\":\"tiktok.com\"}"
                            .formatted(UUID.randomUUID(), juliaca.deviceId(), cusco.deviceId()));
            assertEquals(201, operacion.statusCode(), operacion.body());
            long operacionId = ((Number) JsonPath.read(operacion.body(), "$.id")).longValue();

            Long aviso = avisos.poll(15, TimeUnit.SECONDS);
            assertNotNull(aviso, "la laptop conectada recibe POLICY_CHANGED");
            assertTrue(aviso > inicial);
            String estado = estadoDe(sesion, operacionId, juliaca.deviceId());
            assertNotEquals("APLICADA", estado, "un aviso SSE no equivale a una aplicación");

            // El Agente descarga por la API autenticada, aplica y confirma.
            var reglas = api.fetchWebRules(juliaca).join();
            var tiktok = reglas.rules().stream().filter(r -> r.domain().equals("tiktok.com")).findFirst().orElseThrow();
            assertEquals("RECIBIDA", estadoDe(sesion, operacionId, juliaca.deviceId()));
            api.syncWebEvents(juliaca, List.of(new WebEvent(UUID.randomUUID(), tiktok.id(), "tiktok.com", "FILTRO_APLICADO",
                    "Sitio bloqueado en este equipo", Instant.now().minusSeconds(1), true, tiktok.revision()))).join();
            var latido = api.heartbeat(juliaca, 0, null).join();
            assertTrue(api.confirm(juliaca, UUID.randomUUID(), latido.desiredRevision(), true, "Política aplicada").join());

            assertEquals("APLICADA", estadoDe(sesion, operacionId, juliaca.deviceId()));
            assertEquals("DESCONECTADO", estadoDe(sesion, operacionId, cusco.deviceId()));
            String resultado = enviar("GET", "/api/bulk-operations/" + operacionId, sesion, null).body();
            assertFalse((Boolean) JsonPath.read(resultado, "$.completa"), "no se muestra «Todo aplicado» con pendientes");

            // Revocación: se cierra el canal y la credencial antigua deja de valer.
            assertEquals(204, enviar("DELETE", "/api/devices/" + juliaca.deviceId(), sesion, null).statusCode());
            esperarHasta(() -> errores.stream().anyMatch(e -> e instanceof AgentApiClient.ApiException api401
                    && api401.status() == 401), "el canal recibe 401 tras la revocación");
            AgentApiClient.ApiException rechazo = assertThrows(AgentApiClient.ApiException.class,
                    () -> api.openEvents(juliaca));
            assertEquals(401, rechazo.status());
        } finally {
            tiempoReal.stop();
        }

        // Auditoría de las acciones del padre, sin secretos.
        List<String> tipos = jdbc.queryForList("select tipo_evento from eventos_auditoria where familia_id=?", String.class, familiaId);
        assertTrue(tipos.containsAll(List.of("DISPOSITIVO_VINCULADO", "OPERACION_MASIVA_REGISTRADA", "DISPOSITIVO_REVOCADO")), tipos.toString());
        String textos = String.join("\n", jdbc.queryForList("select descripcion from eventos_auditoria where familia_id=?",
                String.class, familiaId));
        assertFalse(textos.contains(juliaca.agentToken()) || textos.contains(cusco.agentToken()) || textos.contains(PASSWORD));
    }

    private AgentConfigStore.Config vincular(AgentApiClient api, String base, String sesion, long hijo, String identificador)
            throws Exception {
        String codigo = JsonPath.read(enviar("POST", "/api/children/" + hijo + "/device-link-codes", sesion, null).body(), "$.codigo");
        AgentApiClient.LinkResult vinculado = api.link(base, codigo, identificador).join();
        return new AgentConfigStore.Config(base, vinculado.deviceId(), identificador, vinculado.token(), 30);
    }

    private String estadoDe(String sesion, long operacion, long dispositivo) throws Exception {
        String json = enviar("GET", "/api/bulk-operations/" + operacion, sesion, null).body();
        List<String> estados = JsonPath.read(json, "$.dispositivos[?(@.dispositivoId==" + dispositivo + ")].estado");
        return estados.get(0);
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String sesion, String cuerpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (sesion != null) b.header("Authorization", "Bearer " + sesion);
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void esperarHasta(java.util.function.BooleanSupplier condicion, String mensaje) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condicion.getAsBoolean()) {
            if (System.nanoTime() > limite) fail(mensaje);
            Thread.sleep(50);
        }
    }
}
