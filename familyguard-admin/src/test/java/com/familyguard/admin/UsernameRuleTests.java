package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Usuario de inicio de sesión: misma regla que el servidor. */
class UsernameRuleTests {
    @Test
    void aceptaUsuariosYCorreosAnteriores() {
        assertTrue(UsernameRule.valid("heiner"));
        assertTrue(UsernameRule.valid("heiner.apaza"));
        assertTrue(UsernameRule.valid(" luz_2026 "));                 // se ignoran espacios alrededor
        assertTrue(UsernameRule.valid("heineeapaza@gmail.com"));      // cuentas creadas antes con correo
        assertFalse(UsernameRule.valid("ab"));
        assertFalse(UsernameRule.valid("con espacio"));
        assertFalse(UsernameRule.valid("usuario@"));
        assertFalse(UsernameRule.valid(""));
        assertFalse(UsernameRule.valid(null));
    }
}
