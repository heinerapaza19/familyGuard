package com.familyguard.familyguard.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CambiarPasswordRequest(
        @NotBlank @Size(max = 72) String passwordActual,
        @NotBlank @Size(min = 12, max = 72)
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
                message = "debe incluir mayúscula, minúscula, número y símbolo") String passwordNuevo,
        @NotBlank @Size(max = 72) String confirmarPassword) { }
