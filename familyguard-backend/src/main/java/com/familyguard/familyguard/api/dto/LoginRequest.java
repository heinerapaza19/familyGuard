package com.familyguard.familyguard.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        // Usuario (o el correo de las cuentas creadas antes).
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 72) String password) {
}
