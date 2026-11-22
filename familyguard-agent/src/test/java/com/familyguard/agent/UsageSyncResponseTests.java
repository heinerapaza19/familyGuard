package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Compatibilidad del agente con la respuesta de /api/agent/usage, nueva y anterior. */
class UsageSyncResponseTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void interpretaAceptadosDuplicadosYRechazados() {
        UUID valido = UUID.randomUUID(), duplicado = UUID.randomUUID(), invalido = UUID.randomUUID();
        var result = AgentApiClient.parseUsageResponse(mapper.readTree("""
                {"aceptados":1,"duplicados":1,
                 "confirmados":["%s","%s","%s"],
                 "rechazados":[{"registroId":"%s","codigo":"INTERVALO_FUTURO","motivo":"Revise el reloj"}]}
                """.formatted(valido, duplicado, invalido, invalido)));
        assertEquals(1, result.accepted());
        assertEquals(1, result.duplicates());
        assertEquals(1, result.rejected().size());
        assertEquals("INTERVALO_FUTURO", result.rejected().get(0).code());
        assertEquals(Set.of(valido, duplicado, invalido), result.removable());
    }

    @Test void rechazadoNoConfirmadoTambienSeRetiraDeLaCola() {
        UUID invalido = UUID.randomUUID();
        var result = AgentApiClient.parseUsageResponse(mapper.readTree("""
                {"aceptados":0,"confirmados":[],
                 "rechazados":[{"registroId":"%s","codigo":"DURACION_INCONSISTENTE","motivo":"x"}]}
                """.formatted(invalido)));
        assertEquals(Set.of(invalido), result.removable());
    }

    @Test void admiteLaRespuestaDeBackendsAnteriores() {
        UUID id = UUID.randomUUID();
        var result = AgentApiClient.parseUsageResponse(mapper.readTree("""
                {"aceptados":1,"confirmados":["%s"]}
                """.formatted(id)));
        assertTrue(result.rejected().isEmpty());
        assertEquals(0, result.duplicates());
        assertEquals(Set.of(id), result.removable());
    }

    @Test void ignoraIdentificadoresNulosOMalFormados() {
        var result = AgentApiClient.parseUsageResponse(mapper.readTree("""
                {"aceptados":0,"confirmados":["no-es-uuid"],
                 "rechazados":[{"registroId":null,"codigo":"REGISTRO_SIN_IDENTIFICADOR","motivo":"x"}]}
                """));
        assertTrue(result.removable().isEmpty());
    }
}
