package com.familyguard.familyguard.api;

import com.familyguard.familyguard.api.dto.ApiErrorResponse;
import com.familyguard.familyguard.service.exception.ConflictException;
import com.familyguard.familyguard.service.exception.ForbiddenException;
import com.familyguard.familyguard.service.exception.ResourceNotFoundException;
import com.familyguard.familyguard.service.exception.UnauthorizedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validacion(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        exception.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.putIfAbsent("request", error.getDefaultMessage()));
        return respuesta(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Revise los datos enviados", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> jsonInvalido(HttpMessageNotReadableException exception) {
        return respuesta(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "La solicitud contiene JSON invalido o campos no permitidos", Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> argumentoInvalido(IllegalArgumentException exception) {
        return respuesta(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiErrorResponse> conflicto(ConflictException exception) {
        return respuesta(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ApiErrorResponse> noAutorizado(UnauthorizedException exception) {
        return respuesta(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ApiErrorResponse> prohibido(ForbiddenException exception) {
        return respuesta(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> noEncontrado(ResourceNotFoundException exception) {
        return respuesta(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), Map.of());
    }

    private static ResponseEntity<ApiErrorResponse> respuesta(HttpStatus status, String code,
                                                               String message, Map<String, String> errors) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(Instant.now(), status.value(), code, message, errors));
    }
}
