package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.familyguard.familyguard.FamilyguardApplication;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bloqueo web que quedaba «Pendiente — Esperando confirmación» (tiktok.com): cadena completa con el Agente real
 * contra el backend real. Usa un archivo hosts temporal y un verificador DNS simulado; nunca toca el hosts ni el
 * DNS del equipo que ejecuta las pruebas.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = FamilyguardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebBlockConfirmationHttpIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    @LocalServerPort private int port;
    @Autowired private UsuarioRepository usuarios;
    @Autowired private MenorRepository menores;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void inventarioRealDeWindowsNoImpideRecibirLaReglaYSoloSeConfirmaCuandoWindowsLaAplica() throws Exception {
        String base = "http://127.0.0.1:" + port;
        String email = "web-" + UUID.randomUUID() + "@example.test";
        assertEquals(201, enviar("POST", "/api/auth/register", null, """
                {"nombre":"Heiner","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"Familia"}
                """.formatted(email, PASSWORD, PASSWORD)).statusCode());
        String sesion = JsonPath.read(enviar("POST", "/api/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)).body(), "$.accessToken");
        var usuario = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        long luz = menores.save(new Menor(usuario.getFamilia(), "Luz")).getId();
        AgentApiClient api = new AgentApiClient();
        String codigo = JsonPath.read(enviar("POST", "/api/children/" + luz + "/device-link-codes", sesion, null).body(), "$.codigo");
        String identificador = "web-" + UUID.randomUUID();
        var vinculado = api.link(base, codigo, identificador).join();
        var config = new AgentConfigStore.Config(base, vinculado.deviceId(), identificador, vinculado.token(), 30);

        // El padre bloquea tiktok.com (con subdominios) en el equipo de Luz.
        var creado = enviar("POST", "/api/bulk-operations", sesion,
                "{\"operacionId\":\"%s\",\"tipo\":\"BLOQUEAR_SITIO\",\"dispositivoIds\":[%d],\"dominio\":\"tiktok.com\"}"
                        .formatted(UUID.randomUUID(), config.deviceId()));
        assertEquals(201, creado.statusCode(), creado.body());
        assertEquals("El equipo todavía no descargó la regla (sin conexión o sincronización fallida)", detalle(sesion));

        // Inventario como el de Windows: proceso «Idle» con PID 0 y nombres muy largos. Antes el servidor lo
        // rechazaba (400) y el Agente abandonaba el ciclo antes de descargar las reglas web.
        String largo = "Aplicación con un nombre extremadamente largo ".repeat(10);
        var snapshot = new DetectionModels.DetectionSnapshot(
                List.of(new DetectionModels.InstalledApplication(largo, "1".repeat(100), "Editor ".repeat(40), "C:\\Apps\\app.exe", "REGISTRY")),
                List.of(new DetectionModels.RunningProcess(0, "Idle", null, false, Instant.now()),
                        new DetectionModels.RunningProcess(4, "System", null, false, Instant.now()),
                        new DetectionModels.RunningProcess(4321, "Ventana " + largo, "C:\\Apps\\app.exe", true, Instant.now())),
                Instant.now());
        api.sync(config, snapshot).join();   // el inventario saneado se acepta (antes: HTTP 400)

        var reglas = api.fetchWebRules(config).join();
        assertEquals(List.of("tiktok.com"), reglas.rules().stream().map(DetectionModels.WebRule::domain).toList());
        assertEquals("Regla descargada por el equipo; esperando la confirmación de Windows", detalle(sesion));

        // Motor web real con hosts temporal. Si Windows todavía resuelve el dominio, no se confirma.
        Path dir = Files.createTempDirectory("fg-web-e2e");
        Path hosts = dir.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\r\n");
        var store = new WebRestrictionStore(dir.resolve("web-rules.bin"));
        boolean[] windowsResuelveA0000 = {false};
        var engine = new WebRestrictionEngine(store, new HostsFileFilter(hosts, (p, c) -> Files.write(p, c), () -> { }),
                domains -> windowsResuelveA0000[0] ? new WindowsDnsVerifier.Result(true, "Verificado en Windows")
                        : new WindowsDnsVerifier.Result(false, "Windows no aplica el bloqueo: tiktok.com → 157.240.1.1"));
        engine.replace(reglas.zoneId(), reglas.rules());
        String contenido = Files.readString(hosts);
        assertTrue(contenido.contains("0.0.0.0 tiktok.com") && contenido.contains("0.0.0.0 www.tiktok.com"), contenido);
        store.ack(api.syncWebEvents(config, store.snapshot().events()).join());
        assertEquals("ERROR", estado(sesion), "sin confirmación de Windows no se marca como aplicada");
        assertTrue(detalle(sesion).startsWith("Windows no aplica el bloqueo"), detalle(sesion));

        windowsResuelveA0000[0] = true;
        engine.apply(engine.nextAttempt());
        store.ack(api.syncWebEvents(config, store.snapshot().events()).join());
        assertEquals("APLICADO", estado(sesion));
        assertTrue(detalle(sesion).contains("verificado en Windows"), detalle(sesion));
        assertTrue(store.snapshot().events().isEmpty(), "todos los eventos fueron confirmados por el servidor");
    }

    private String estado(String sesion) throws Exception {
        return JsonPath.read(enviar("GET", "/api/web-restrictions", sesion, null).body(), "$[0].estadoAplicacion");
    }

    private String detalle(String sesion) throws Exception {
        return JsonPath.read(enviar("GET", "/api/web-restrictions", sesion, null).body(), "$[0].detalleAplicacion");
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String sesion, String cuerpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (sesion != null) b.header("Authorization", "Bearer " + sesion);
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
