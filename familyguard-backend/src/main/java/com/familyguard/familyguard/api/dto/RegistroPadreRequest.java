
package com.familyguard.familyguard.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO utilizado para registrar una nueva cuenta familiar.
 *
 * Valida los datos del padre o administrador y la informacion
 * necesaria para crear su familia dentro de FamilyGuard.
 */
public record RegistroPadreRequest(

        // Nombre completo del padre o administrador.
        @NotBlank(message = "Los nombres y apellidos son obligatorios")
        @Size(max = 120, message = "El nombre no puede superar 120 caracteres")
        String nombre,

        // Usuario para iniciar sesion (se guarda en la columna email; tambien se acepta un correo).
        @NotBlank(message = "El usuario es obligatorio")
        @jakarta.validation.constraints.Pattern(regexp = NombreUsuario.PATRON, message = NombreUsuario.MENSAJE)
        @Size(max = 254)
        String email,

        // Contrasena de 6 a 72 caracteres con requisitos de seguridad.
        @NotBlank(message = "La contrasena es obligatoria")
        @Size(
                min = 6,
                max = 72,
                message = "La contrasena debe tener entre 6 y 72 caracteres"
        )
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
                message = "La contrasena debe incluir mayuscula, minuscula, numero y simbolo"
        )
        String password,

        // Confirmacion de la contrasena ingresada.
        @NotBlank(message = "Debe confirmar la contrasena")
        String confirmarPassword,

        // Nombre de la familia que se registrara en el sistema.
        @NotBlank(message = "El nombre de la familia es obligatorio")
        @Size(max = 120)
        String nombreFamilia

) {

    /**
     * Comprueba que la contrasena y su confirmacion coincidan.
     *
     * @return true si ambas contrasenas son iguales;
     *         false en caso contrario.
     */
    @AssertTrue(message = "Las contrasenas no coinciden")
    public boolean isPasswordConfirmado() {
        return password != null && password.equals(confirmarPassword);
    }
}
