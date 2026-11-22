package com.familyguard.familyguard.api.dto;

import java.time.LocalDate;

public record MenorResponse(Long id, String nombre, LocalDate fechaNacimiento,
                            String nombrePerfil, boolean activo) {
}
