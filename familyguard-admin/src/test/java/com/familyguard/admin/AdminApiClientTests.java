package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class AdminApiClientTests {
    @Test
    void informaConClaridadCuandoElServidorNoEstaDisponible() {
        AdminApiClient client = new AdminApiClient(URI.create("http://127.0.0.1:1"));
        CompletionException exception = assertThrows(CompletionException.class,
                () -> client.login("padre@example.test", "ClaveSegura#2026").join());
        assertTrue(exception.getCause().getMessage().contains("servidor")
                || exception.getCause().getMessage().contains("conectar"));
    }
}
