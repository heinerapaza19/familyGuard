package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.familyguard.familyguard.FamilyguardApplication;
import com.familyguard.familyguard.domain.model.CodigoVinculacion;
import com.familyguard.familyguard.domain.model.Familia;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.CodigoVinculacionRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.service.AutenticacionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(classes = FamilyguardApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalAgentHttpIntegrationTests {
    @LocalServerPort private int port;
    @Autowired private FamiliaRepository familias;
    @Autowired private MenorRepository menores;
    @Autowired private CodigoVinculacionRepository codigos;
    @Autowired private DispositivoRepository dispositivos;

    @Test
    void agenteWindowsRealSeVinculaYSincronizaConBackendLocal() {
        String suffix = UUID.randomUUID().toString();
        Familia familia = familias.save(new Familia("Familia integracion " + suffix, "America/Lima"));
        Menor menor = menores.save(new Menor(familia, "Perfil integracion"));
        String code = "FG9LOCAL";
        codigos.save(new CodigoVinculacion(familia, menor, AutenticacionService.hashToken(code),
                Instant.now().plusSeconds(600)));

        String baseUrl = "http://127.0.0.1:" + port;
        AgentApiClient api = new AgentApiClient();
        AgentApiClient.LinkResult linked = api.link(baseUrl, code, suffix).join();
        AgentConfigStore.Config config = new AgentConfigStore.Config(baseUrl, linked.deviceId(), suffix,
                linked.token(), 30);

        AgentApiClient.Heartbeat heartbeat = api.heartbeat(config, 0, null).join();
        assertEquals(0, heartbeat.appliedRevision());
        api.sync(config, new DetectionModels.DetectionSnapshot(List.of(), List.of(), Instant.now())).join();
        assertTrue(api.confirm(config, UUID.randomUUID(), 0, true, "integracion local").join());

        var stored = dispositivos.findById(linked.deviceId()).orElseThrow();
        assertNotNull(stored.getUltimoHeartbeat());
        assertEquals(AgentApiClient.AGENT_VERSION, stored.getVersionAgente());
        assertEquals(familia.getId(), stored.getFamilia().getId());
    }
}
