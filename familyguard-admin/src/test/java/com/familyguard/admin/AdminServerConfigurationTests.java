package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Etapa 7: dirección del servidor del Administrador (oficial empaquetada, sin escribir URLs técnicas). */
class AdminServerConfigurationTests {
    @Test void ordenDePrioridadVariablePropiedadGuardadaYOficial() {
        assertEquals(Optional.of(URI.create("https://env.example")), AdminApiConfiguration.resolve(
                "https://env.example", "https://prop.example", "https://guardada.example", "https://oficial.example"));
        assertEquals(Optional.of(URI.create("https://prop.example")), AdminApiConfiguration.resolve(
                null, "https://prop.example", "https://guardada.example", "https://oficial.example"));
        assertEquals(Optional.of(URI.create("https://guardada.example")), AdminApiConfiguration.resolve(
                "", " ", "https://guardada.example", "https://oficial.example"));
        assertEquals(Optional.of(URI.create("https://oficial.example")), AdminApiConfiguration.resolve(
                null, null, "", "https://oficial.example/"));
        assertEquals(Optional.empty(), AdminApiConfiguration.resolve(null, null, "", ""));
    }

    @Test void descartaDireccionesInsegurasOConCredenciales() {
        assertEquals(Optional.of(URI.create("https://oficial.example")), AdminApiConfiguration.resolve(
                "http://servidor-remoto.example", "https://usuario:clave@x.example", "https://x.example/?token=1",
                "https://oficial.example"));
        assertEquals(Optional.of(URI.create("http://localhost:8080")), AdminApiConfiguration.resolve(
                null, null, "http://localhost:8080", null));
    }

    @Test void laDireccionOficialEmpaquetadaEstaVaciaHastaQueExistaUnServidorAutorizado() {
        String packaged = AdminApiConfiguration.packagedUrl();
        assertNotNull(packaged, "server.properties debe estar incluido en el Administrador");
        assertTrue(packaged.isBlank());
    }
}
