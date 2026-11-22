package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Etapa 7: el perfil del servidor público toma las credenciales de MySQL de variables de entorno obligatorias
 * y nunca usa la configuración local (root sin contraseña). No arranca el backend ni toca MySQL.
 */
class RemoteProfileConfigurationTests {
    private static Properties cargar(String recurso) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = RemoteProfileConfigurationTests.class.getResourceAsStream(recurso)) {
            assertThat(input).as(recurso).isNotNull();
            properties.load(input);
        }
        return properties;
    }

    @Test
    void credencialesDeBaseDeDatosObligatoriasPorVariablesDeEntorno() throws Exception {
        Properties remoto = cargar("/application-remote.properties");
        assertThat(remoto.getProperty("spring.datasource.url")).isEqualTo("${FAMILYGUARD_DB_URL}");
        assertThat(remoto.getProperty("spring.datasource.username")).isEqualTo("${FAMILYGUARD_DB_USERNAME}");
        assertThat(remoto.getProperty("spring.datasource.password")).isEqualTo("${FAMILYGUARD_DB_PASSWORD}");
        for (String clave : remoto.stringPropertyNames()) {
            assertThat(remoto.getProperty(clave)).as(clave).doesNotContainIgnoringCase("root");
        }
    }

    @Test
    void servidorDetrasDeUnProxyHttpsYSinExponerDetalles() throws Exception {
        Properties remoto = cargar("/application-remote.properties");
        assertThat(remoto.getProperty("server.address")).isEqualTo("${FAMILYGUARD_BIND_ADDRESS:127.0.0.1}");
        assertThat(remoto.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
        assertThat(remoto.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(remoto.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info");
    }

    @Test
    void laConfiguracionLocalProtegidaNoCambia() throws Exception {
        Properties local = cargar("/application.properties");
        assertThat(local.getProperty("spring.datasource.url")).isEqualTo("jdbc:mysql://127.0.0.1:3306/familyguard_db");
        assertThat(local.getProperty("spring.datasource.username")).isEqualTo("root");
        assertThat(local.getProperty("spring.datasource.password")).isEmpty();
        assertThat(local.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
    }
}
