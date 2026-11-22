package com.familyguard.familyguard.security;

import com.familyguard.familyguard.domain.model.RolUsuario;

public record UsuarioPrincipal(Long usuarioId, Long familiaId, String nombre,
                               String email, RolUsuario rol) {
}
