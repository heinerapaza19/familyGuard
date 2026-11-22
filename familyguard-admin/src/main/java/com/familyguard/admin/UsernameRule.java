package com.familyguard.admin;

/**
 * Usuario de inicio de sesión (misma regla que el servidor): 3 a 40 caracteres con letras, números, punto, guion o
 * guion bajo. Las cuentas creadas antes con un correo siguen entrando con ese correo.
 */
final class UsernameRule {
    static final String PATTERN = "^[A-Za-z0-9._-]{3,40}$|^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]+$";
    static final String MESSAGE = "El usuario debe tener de 3 a 40 caracteres: letras, números, punto, guion o guion bajo (sin espacios).";
    private UsernameRule() { }

    static boolean valid(String value) { return value != null && value.strip().matches(PATTERN); }
}
