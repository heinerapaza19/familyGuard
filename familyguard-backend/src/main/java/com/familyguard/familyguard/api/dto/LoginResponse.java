package com.familyguard.familyguard.api.dto;

import java.time.Instant;

public record LoginResponse(String accessToken, String tokenType, Instant expiraEn, PerfilResponse perfil) {
}
