package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.familyguard.agent.DetectionModels.*;
import com.familyguard.familyguard.FamilyguardApplication;
import com.familyguard.familyguard.domain.model.CodigoVinculacion;
import com.familyguard.familyguard.domain.model.Familia;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.repository.CodigoVinculacionRepository;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.UsoAplicacionRepository;
import com.familyguard.familyguard.service.AutenticacionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Agente real contra backend real: un registro inválido en la cola ya no bloquea la sincronización
 * y, tras una sola ronda, la cola local queda vacía.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = FamilyguardApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentUsageSyncHttpIntegrationTests {
    @LocalServerPort private int port;
    @Autowired private FamiliaRepository familias;
    @Autowired private MenorRepository menores;
    @Autowired private CodigoVinculacionRepository codigos;
    @Autowired private UsoAplicacionRepository usos;

    @Test
    void registroInvalidoNoBloqueaLaColaDelAgente() {
        AgentConfigStore.Config config = vincular();
        AgentApiClient api = new AgentApiClient();
        Instant end = Instant.now().minusSeconds(10);
        UsageEvent valido = new UsageEvent(UUID.randomUUID(), "chrome.exe", "C:\\Chrome\\chrome.exe", 10,
                end.minusSeconds(40), end, 40);
        // Reloj del equipo adelantado: el intervalo termina una hora en el futuro.
        Instant future = Instant.now().plusSeconds(3600);
        UsageEvent futuro = new UsageEvent(UUID.randomUUID(), "chrome.exe", "C:\\Chrome\\chrome.exe", 10,
                future.minusSeconds(30), future, 30);
        UsageEvent otroValido = new UsageEvent(UUID.randomUUID(), "msedge.exe", "C:\\Edge\\msedge.exe", 20,
                end.minusSeconds(15), end, 15);

        MemoryJournal journal = new MemoryJournal(List.of(valido, futuro, otroValido));
        UsageTracker tracker = new UsageTracker(journal);
        assertEquals(3, tracker.pendingCount());

        AgentApiClient.UsageSyncResult result = api.syncUsage(config, tracker.pendingBatch(200)).join();
        assertEquals(2, result.accepted());
        assertEquals(1, result.rejected().size());
        assertEquals(futuro.eventId(), result.rejected().get(0).eventId());
        assertEquals("INTERVALO_FUTURO", result.rejected().get(0).code());

        tracker.acknowledge(result.removable());
        assertEquals(0, tracker.pendingCount(), "la cola no debe conservar el registro rechazado");
        assertTrue(usos.existsByRegistroUuid(valido.eventId().toString()));
        assertTrue(usos.existsByRegistroUuid(otroValido.eventId().toString()));
        assertFalse(usos.existsByRegistroUuid(futuro.eventId().toString()));

        // Reenvío idempotente si la respuesta anterior se hubiera perdido.
        AgentApiClient.UsageSyncResult again = api.syncUsage(config, List.of(valido, futuro, otroValido)).join();
        assertEquals(0, again.accepted());
        assertEquals(2, again.duplicates());
        assertEquals(3, again.removable().size());
    }

    private AgentConfigStore.Config vincular() {
        String suffix = UUID.randomUUID().toString();
        Familia familia = familias.save(new Familia("Familia uso " + suffix, "America/Lima"));
        Menor menor = menores.save(new Menor(familia, "Perfil uso"));
        String code = suffix.substring(0, 8).toUpperCase();
        codigos.save(new CodigoVinculacion(familia, menor, AutenticacionService.hashToken(code),
                Instant.now().plusSeconds(600)));
        String baseUrl = "http://127.0.0.1:" + port;
        AgentApiClient.LinkResult linked = new AgentApiClient().link(baseUrl, code, "uso-" + suffix).join();
        return new AgentConfigStore.Config(baseUrl, linked.deviceId(), "uso-" + suffix, linked.token(), 30);
    }

    private static final class MemoryJournal implements UsagePersistence {
        private UsageState state;
        MemoryJournal(List<UsageEvent> pending) { state = new UsageState(null, new ArrayList<>(pending)); }
        @Override public UsageState snapshot() { return state; }
        @Override public void persist(OpenUsage current, List<UsageEvent> pending) {
            state = new UsageState(current, List.copyOf(pending));
        }
    }
}
