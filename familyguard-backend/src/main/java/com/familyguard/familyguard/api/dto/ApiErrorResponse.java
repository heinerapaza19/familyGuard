package com.familyguard.familyguard.api.dto;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(Instant timestamp, int status, String code, String message,
                               Map<String, String> errors) {
}
