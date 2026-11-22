package com.familyguard.familyguard.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record MenorRequest(
        @NotBlank(message = "El nombre es obligatorio") @Size(max = 120) String nombre,
        @Past(message = "La fecha de nacimiento debe estar en el pasado") LocalDate fechaNacimiento,
        @Size(max = 80) String nombrePerfil) {
}
