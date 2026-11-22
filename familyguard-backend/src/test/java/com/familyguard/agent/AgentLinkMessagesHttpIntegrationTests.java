package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.familyguard.familyguard.FamilyguardApplication;
import com.familyguard.familyguard.domain.model.CodigoVinculacion;
import com.familyguard.familyguard.domain.model.Familia;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.CodigoVinculacionRepository;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.service.AutenticacionService;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** Contrato real agente-backend: el agente muestra el motivo exacto por el que no pudo vincularse. */
@ActiveProfiles("test")
@SpringBootTest(classes = FamilyguardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentLinkMessagesHttpIntegrationTests {
    @LocalServerPort private int port;
    @Autowired private FamiliaRepository familias;
    @Autowired private MenorRepository menores;
    @Autowired private CodigoVinculacionRepository codigos;

    @Test
    void elAgenteExplicaCadaRechazoDeVinculacion() {
        String base = "http://127.0.0.1:" + port;
        AgentApiClient api = new AgentApiClient();
        Familia familia = familias.save(new Familia("Familia mensajes " + UUID.randomUUID(), "America/Lima"));
        Menor menor = menores.save(new Menor(familia, "Luz"));

        String valido = codigo(familia, menor, Instant.now().plusSeconds(600));
        AgentApiClient.LinkResult linked = api.link(base, valido.toLowerCase(), "msg-" + UUID.randomUUID()).join();
        assertTrue(linked.deviceId() > 0, "acepta el código escrito en minúsculas");

        assertTrue(error(api, base, valido).contains("ya fue utilizado"));
        Instant breve = Instant.now().plusMillis(200);
        String vencido = codigo(familia, menor, breve);
        esperarHasta(breve.plusMillis(100));
        assertEquals(AgentApiClient.LINK_EXPIRED, error(api, base, vencido));
        assertEquals(AgentApiClient.LINK_INVALID, error(api, base, "QQQQ-QQQQ"));
        assertTrue(error(api, "http://127.0.0.1:1", "ABCD1234").contains("No se pudo conectar"));
    }

    private static void esperarHasta(Instant instante) {
        try { while (!Instant.now().isAfter(instante)) Thread.sleep(20); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private String codigo(Familia familia, Menor menor, Instant expira) {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        codigos.save(new CodigoVinculacion(familia, menor, AutenticacionService.hashToken(code), expira));
        return code;
    }

    private static String error(AgentApiClient api, String base, String code) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> api.link(base, code, "msg-" + UUID.randomUUID()).join());
        return AgentApiClient.linkErrorMessage(failure);
    }
}
