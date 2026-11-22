package com.familyguard.agent;

import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Modo auxiliar de una sola ejecución, iniciado por {@link WebFilterElevation} mediante UAC.
 * No abre ventanas, no se comunica con el backend, no lee credenciales y solo puede escribir
 * entradas de bloqueo para dominios válidos dentro del bloque de FamilyGuard.
 */
final class WebFilterHelper {
    private static final long MAX_REQUEST_BYTES = 256 * 1024;
    private WebFilterHelper() { }

    static int run(String[] args) { return run(args, new HostsFileFilter()); }

    static int run(String[] args, HostsFileFilter filter) {
        if (args.length != 2 || !WebFilterElevation.HELPER_ARGUMENT.equals(args[0])) return WebFilterElevation.EXIT_INVALID;
        Set<String> domains = new TreeSet<>();
        try {
            Path request = Path.of(args[1]);
            if (!Files.isRegularFile(request) || Files.size(request) > MAX_REQUEST_BYTES) return WebFilterElevation.EXIT_INVALID;
            JsonNode json = new ObjectMapper().readTree(Files.readString(request));
            JsonNode list = json.path("dominios");
            if (!list.isArray()) return WebFilterElevation.EXIT_INVALID;
            for (JsonNode item : list) {
                String value = item.asText(null);
                if (value == null || value.isBlank()) return WebFilterElevation.EXIT_INVALID;
                domains.add(value);
            }
        } catch (Exception invalid) {
            return WebFilterElevation.EXIT_INVALID;
        }
        HostsFileFilter.Result result = filter.apply(domains);
        return switch (result.outcome()) {
            case APPLIED, UNCHANGED -> WebFilterElevation.EXIT_APPLIED;
            case PERMISSION_DENIED -> WebFilterElevation.EXIT_DENIED;
            case INVALID -> WebFilterElevation.EXIT_INVALID;
            case ERROR -> WebFilterElevation.EXIT_ERROR;
        };
    }
}
