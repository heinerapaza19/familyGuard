package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AgentLifecycleTests {
    @TempDir Path temp;

    @Test void reintentoEsExponencialYAcotado() {
        RetryBackoff backoff = new RetryBackoff(300);
        assertEquals(Duration.ofSeconds(2), backoff.delay(1));
        assertEquals(Duration.ofSeconds(32), backoff.delay(5));
        assertEquals(Duration.ofSeconds(300), backoff.delay(20));
    }

    @Test void comandoDeInicioEsVisibleYConservaRutasConEspacios() {
        assertEquals("\"C:\\Program Files\\FamilyGuard Agente.exe\" --autostart",
                WindowsAutoStart.command(Path.of("C:\\Program Files\\FamilyGuard Agente.exe")));
    }

    @Test void identificaRevocacionSinConfundirlaConFalloDeRed() {
        assertTrue(AgentApiClient.authorizationFailure(new CompletionException(
                new AgentApiClient.ApiException(401, "revocado"))));
        assertFalse(AgentApiClient.authorizationFailure(new IllegalStateException("sin red")));
    }

    @Test void impideDosInstanciasConElMismoBloqueo() throws Exception {
        Path lockFile = temp.resolve("agent.lock");
        try (SingleInstanceLock ignored = SingleInstanceLock.acquire(lockFile)) {
            assertThrows(java.io.IOException.class, () -> SingleInstanceLock.acquire(lockFile));
        }
        assertDoesNotThrow(() -> { try (SingleInstanceLock ignored = SingleInstanceLock.acquire(lockFile)) { } });
    }

    @Test void configuracionCorruptaNoSeInterpretaComoInstalacionNueva() throws Exception {
        Path file=temp.resolve("config.json");Files.writeString(file,"contenido no válido");
        assertEquals(AgentConfigStore.LoadStatus.UNREADABLE,new AgentConfigStore(file).loadResult().status());
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void dpapiConservaIdentidadYAutorizacionDeInicio() {
        Path file=temp.resolve("config.json");AgentConfigStore store=new AgentConfigStore(file);
        AgentConfigStore.Config expected=new AgentConfigStore.Config("https://familyguard.example",42,"equipo-estable","token-secreto",30,true);
        store.save(expected);
        assertFalse(assertDoesNotThrow(()->Files.readString(file)).contains("token-secreto"));
        assertEquals(expected,store.loadResult().config());
    }
}
