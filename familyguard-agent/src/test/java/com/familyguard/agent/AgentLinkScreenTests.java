package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.net.ConnectException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** Etapa 2: código fácil de escribir, mensajes claros y dirección del servidor fuera de la vista principal. */
class AgentLinkScreenTests {
    @Test void aceptaElCodigoConMinusculasEspaciosYGuiones() {
        assertEquals("K7P2M9QX", AgentApiClient.normalizeCode(" k7p2-m9qx "));
        assertEquals("K7P2M9QX", AgentApiClient.normalizeCode("K7P2 M9QX"));
        assertTrue(AgentApiClient.validCode("k7p2 m9qx"));
        assertFalse(AgentApiClient.validCode("K7P2M9Q"));
        assertFalse(AgentApiClient.validCode("K7P2M9Q*"));
        assertFalse(AgentApiClient.validCode(null));
    }

    @Test void traduceLasRespuestasDelServidorAMensajesClaros() {
        // Textos exactos pedidos para la pantalla de vinculación (entrega de diseño del Agente).
        assertEquals("El código ha vencido. Solicita uno nuevo.", message(401, "El codigo vencio; solicite uno nuevo"));
        assertTrue(message(401, "El codigo ya fue utilizado; solicite uno nuevo").contains("ya fue utilizado"));
        assertTrue(message(401, "El codigo fue reemplazado por uno nuevo; use el ultimo codigo generado").contains("reemplazado"));
        assertTrue(message(401, "Demasiados intentos de vinculación; espere 15 minutos").contains("Espera 15 minutos"));
        assertEquals("El código ingresado no es válido.", message(401, "Codigo invalido; revise los 8 caracteres"));
        assertEquals("El servidor FamilyGuard no está disponible.", message(503, null));
        assertEquals("Este dispositivo necesita una nueva vinculación autorizada.", message(403, "Dispositivo revocado"));
        assertTrue(message(400, null).contains("8 letras o números"));
        assertFalse(message(401, "Codigo invalido").contains("revocada"), "no debe confundirse con una revocación");
    }

    @Test void distingueLaFaltaDeConexion() {
        String text = AgentApiClient.linkErrorMessage(new CompletionException(new ConnectException("Connection refused")));
        assertEquals("No se pudo conectar con FamilyGuard. Comprueba la conexión.", text);
        assertFalse(text.contains("Connection refused"), "sin detalles técnicos");
    }

    @Test void direccionOficialTienePrioridadSobreLaLocalYSeIgnoraSiNoEsSegura() {
        assertEquals("https://servidor.ejemplo", AgentServerSettings.resolve(null, null, "https://servidor.ejemplo/"));
        assertEquals("https://desde-entorno.ejemplo", AgentServerSettings.resolve("https://desde-entorno.ejemplo", null, "https://servidor.ejemplo"));
        assertEquals(AgentServerSettings.LOCAL_DEFAULT, AgentServerSettings.resolve(null, null, ""));
        assertEquals(AgentServerSettings.LOCAL_DEFAULT, AgentServerSettings.resolve("http://remoto.ejemplo", null, null),
                "HTTP remoto se rechaza y se mantiene la configuración local");
    }

    @Test void laInstalacionActualNoTraeUnDominioInventado() {
        String packaged = AgentServerSettings.packagedUrl();
        assertTrue(packaged == null || packaged.isBlank());
        assertFalse(AgentServerSettings.officialConfigured());
    }

    @Test void soloAceptaUnaVinculacionConfirmadaPorElServidor() throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var ok = AgentApiClient.verifiedLink(mapper.readTree("{\"dispositivoId\":7,\"agenteToken\":\"abc\",\"estado\":\"AUTORIZADO\"}"));
        assertEquals(7L, ok.deviceId());
        for (String body : new String[] {"{\"dispositivoId\":7,\"agenteToken\":\"\",\"estado\":\"AUTORIZADO\"}",
                "{\"dispositivoId\":0,\"agenteToken\":\"abc\",\"estado\":\"AUTORIZADO\"}",
                "{\"dispositivoId\":7,\"agenteToken\":\"abc\",\"estado\":\"PENDIENTE\"}"})
            assertThrows(AgentApiClient.LinkRejectedException.class, () -> AgentApiClient.verifiedLink(mapper.readTree(body)));
    }

    private static String message(int status, String server) {
        return AgentApiClient.linkErrorMessage(new CompletionException(new AgentApiClient.ApiException(status, "x", server)));
    }
}
