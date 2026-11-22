package com.familyguard.familyguard.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ActualizarPerfilRequest(
        @NotBlank @Size(max = 120) String nombre,
        @NotBlank @jakarta.validation.constraints.Pattern(regexp = NombreUsuario.PATRON, message = NombreUsuario.MENSAJE)
        @Size(max = 254) String email,
        @NotBlank @Size(max = 72) String passwordActual) { }
