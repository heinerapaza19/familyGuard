package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Entrega final: las pruebas manuales usan una base aislada (familyguard_dev) y las migraciones nuevas (V11, V12)
 * nunca se aplican a familyguard_db mientras el usuario no lo autorice. No arranca el backend ni toca MySQL.
 */
class DevelopmentDatabaseIsolationTests {
    private static Properties cargar(String recurso) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = DevelopmentDatabaseIsolationTests.class.getResourceAsStream(recurso)) {
            assertThat(input).as(recurso).isNotNull();
            properties.load(input);
        }
        return properties;
    }

    @Test
    void elPerfilDevUsaUnaBaseSeparadaYNuncaFamilyguardDb() throws Exception {
        Properties dev = cargar("/application-dev.properties");
        assertThat(dev.getProperty("spring.datasource.url")).contains("familyguard_dev").doesNotContain("familyguard_db");
        assertThat(dev.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration,classpath:db/pendiente");
    }

    @Test
    void laBaseRealNoRecibeMigracionesPendientesSinAutorizacion() throws Exception {
        Properties local = cargar("/application.properties");
        assertThat(local.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(DevelopmentDatabaseIsolationTests.class.getResource("/db/migration/V11__permisos_temporales.sql")).isNull();
        assertThat(DevelopmentDatabaseIsolationTests.class.getResource("/db/pendiente/V11__permisos_temporales.sql")).isNotNull();
        assertThat(DevelopmentDatabaseIsolationTests.class.getResource("/db/pendiente/V12__pin_parental_y_autorizaciones.sql")).isNotNull();
    }

    @Test
    void laClaveDelPinNuncaEstaEnArchivosVersionados() throws Exception {
        for (String recurso : new String[] {"/application.properties", "/application-dev.properties", "/application-remote.properties"}) {
            Properties p = cargar(recurso);
            String clave = p.getProperty("familyguard.pin.clave");
            assertThat(clave == null || clave.startsWith("${")).as(recurso).isTrue();
        }
    }
}
