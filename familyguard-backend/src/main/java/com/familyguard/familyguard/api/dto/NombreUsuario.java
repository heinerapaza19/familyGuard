package com.familyguard.familyguard.api.dto;

/**
 * Nombre de usuario para iniciar sesión. Se guarda en la columna existente {@code usuarios.email} (única), sin cambios
 * de esquema. Se aceptan nombres de 3 a 40 caracteres (letras, números, punto, guion y guion bajo) y, para las cuentas
 * creadas antes, también su correo electrónico, que sigue funcionando como usuario.
 */
public final class NombreUsuario {
    public static final String PATRON = "^[A-Za-z0-9._-]{3,40}$|^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]+$";
    public static final String MENSAJE = "El usuario debe tener de 3 a 40 caracteres: letras, números, punto, guion o guion bajo";
    private NombreUsuario() { }
}
