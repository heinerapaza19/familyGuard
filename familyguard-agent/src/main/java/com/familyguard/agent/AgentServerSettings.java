package com.familyguard.agent;

import java.io.InputStream;
import java.util.Properties;

/**
 * Dirección del servidor que se ofrece al vincular. Orden de prioridad:
 * variable {@code FAMILYGUARD_API_URL}, propiedad {@code -Dfamilyguard.api.url}, la dirección oficial
 * empaquetada en {@code server.properties} y, si no hay ninguna, la configuración local actual.
 * El usuario final solo la ve en «Opciones avanzadas».
 */
final class AgentServerSettings {
    static final String LOCAL_DEFAULT = "http://localhost:8080";
    private AgentServerSettings() { }

    static String defaultUrl() {
        return resolve(System.getenv("FAMILYGUARD_API_URL"), System.getProperty("familyguard.api.url"), packagedUrl());
    }

    /** Hay una dirección oficial válida empaquetada (HTTPS remoto o servidor local explícito). */
    static boolean officialConfigured() { return valid(packagedUrl()) != null; }

    static String resolve(String environment, String property, String packaged) {
        for (String candidate : new String[] {environment, property, packaged}) {
            String value = valid(candidate);
            if (value != null) return value;
        }
        return LOCAL_DEFAULT;
    }

    static String packagedUrl() {
        try (InputStream input = AgentServerSettings.class.getResourceAsStream("server.properties")) {
            if (input == null) return null;
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
            return properties.getProperty("familyguard.server.url");
        } catch (Exception unreadable) { return null; }
    }

    private static String valid(String value) {
        if (value == null || value.isBlank()) return null;
        try { return AgentApiClient.validateBaseUrl(value); } catch (Exception invalid) { return null; }
    }
}
