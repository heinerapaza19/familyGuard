package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;

/** PIN parental local: solo verificador, bloqueo persistente tras intentos fallidos y pausas con fin fijo. */
class ParentalProtectionTests {
    static final Instant T0 = Instant.parse("2026-11-19T15:00:00Z");

    static String verifier(String pin) throws Exception {
        byte[] salt = "sal-de-prueba-16".getBytes();
        byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(pin.toCharArray(), salt, 1000, 256)).getEncoded();
        return "pbkdf2-sha256$1000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
    }
    static ProtectedFile file(Path p) { return new ProtectedFile(p, TemporaryPermissionsTests.PLAIN); }

    @Test
    void pinCorrectoIncorrectoYBloqueoQueSobreviveAlReinicio() throws Exception {
        Path path = Files.createTempDirectory("fg-pin").resolve("protection.bin");
        ParentalProtection protection = new ParentalProtection(file(path));
        assertEquals(ParentalProtection.Outcome.NOT_CONFIGURED, protection.verify("4826", T0).outcome());
        protection.updateVerifier(true, 1, verifier("4826"));
        assertTrue(protection.verify("4826", T0).ok());
        for (int i = 1; i <= 4; i++) assertEquals(5 - i, protection.verify("0000", T0).remainingAttempts());
        assertEquals(ParentalProtection.Outcome.LOCKED, protection.verify("0000", T0).outcome());
        // Bloqueado: ni el PIN correcto funciona, tampoco tras reiniciar el Agente.
        assertEquals(ParentalProtection.Outcome.LOCKED, protection.verify("4826", T0.plusSeconds(60)).outcome());
        ParentalProtection reopened = new ParentalProtection(file(path));
        assertEquals(ParentalProtection.Outcome.LOCKED, reopened.verify("4826", T0.plus(Duration.ofMinutes(4))).outcome());
        assertTrue(reopened.verify("4826", T0.plus(Duration.ofMinutes(5))).ok());
        // El segundo bloqueo dura el doble.
        for (int i = 0; i < 5; i++) reopened.verify("1111", T0.plus(Duration.ofMinutes(6)));
        assertEquals(T0.plus(Duration.ofMinutes(16)), reopened.verify("4826", T0.plus(Duration.ofMinutes(7))).lockedUntil());
        assertTrue(reopened.pendingEvents().stream().anyMatch(e -> "PIN_BLOQUEADO".equals(e.type())));
        assertFalse(Files.readString(path).contains("4826"), "nunca se guarda el PIN");
    }

    @Test
    void pausaTerminaASuHoraAunqueSeReinicieElAgente() throws Exception {
        Path path = Files.createTempDirectory("fg-pausa").resolve("protection.bin");
        ParentalProtection protection = new ParentalProtection(file(path));
        protection.pause(T0, Duration.ofMinutes(30), "PIN");
        assertTrue(protection.paused(T0.plus(Duration.ofMinutes(29))));
        ParentalProtection reopened = new ParentalProtection(file(path));
        assertTrue(reopened.paused(T0.plus(Duration.ofMinutes(29))));
        assertFalse(reopened.paused(T0.plus(Duration.ofMinutes(30))));
        assertNull(reopened.pausedUntil());
        assertTrue(reopened.pendingEvents().stream().anyMatch(e -> "PAUSA_FINALIZADA".equals(e.type())));
    }

    @Test
    void unPinNuevoReiniciaLosIntentos() throws Exception {
        ParentalProtection protection = new ParentalProtection(file(Files.createTempDirectory("fg-pin2").resolve("p.bin")));
        protection.updateVerifier(true, 1, verifier("4826"));
        protection.verify("1", T0); protection.verify("2", T0);
        protection.updateVerifier(true, 2, verifier("7391"));
        assertEquals(4, protection.verify("4826", T0).remainingAttempts());
        assertTrue(protection.verify("7391", T0).ok());
    }
}
