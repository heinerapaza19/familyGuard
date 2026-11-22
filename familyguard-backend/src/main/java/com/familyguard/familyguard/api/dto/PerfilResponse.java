package com.familyguard.familyguard.api.dto;

public record PerfilResponse(Long usuarioId, String nombre, String email, String rol,
                             Long familiaId, String familiaNombre, String estadoCuenta) {
}
