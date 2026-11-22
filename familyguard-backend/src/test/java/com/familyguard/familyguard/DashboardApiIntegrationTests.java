package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyguard.familyguard.domain.model.Aplicacion;
import com.familyguard.familyguard.domain.model.Dispositivo;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.model.TipoDispositivo;
import com.familyguard.familyguard.domain.model.UsoAplicacion;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.UsoAplicacionRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.familyguard.familyguard.domain.repository.SesionAutenticacionRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class DashboardApiIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarios;
    @Autowired private MenorRepository menores;
    @Autowired private DispositivoRepository dispositivos;
    @Autowired private AplicacionRepository aplicaciones;
    @Autowired private UsoAplicacionRepository usos;
    @Autowired private SesionAutenticacionRepository sesiones;

    @Test
    void dashboardDeFamiliaVaciaDevuelveCeros() throws Exception {
        Cuenta cuenta = cuenta();
        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, bearer(cuenta.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hijosRegistrados").value(0))
                .andExpect(jsonPath("$.dispositivosVinculados").value(0))
                .andExpect(jsonPath("$.aplicacionesDetectadas").value(0))
                .andExpect(jsonPath("$.tiempoUsoHoySegundos").value(0));
    }

    @Test
    void dashboardCuentaHijosExistentes() throws Exception {
        Cuenta cuenta = cuenta();
        menores.save(new Menor(cuenta.familia(), "Hijo Dashboard"));
        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, bearer(cuenta.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hijosRegistrados").value(1));
    }

    @Test
    void consultaDispositivosRealesDeLaFamilia() throws Exception {
        Cuenta cuenta = cuenta();
        Datos datos = datos(cuenta, "PC Familiar");
        mvc.perform(get("/api/devices").header(HttpHeaders.AUTHORIZATION, bearer(cuenta.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("PC Familiar"))
                .andExpect(jsonPath("$[0].menorId").value(datos.menor().getId()));
    }

    @Test
    void consultaAplicacionesYUsoAcumuladoReal() throws Exception {
        Cuenta cuenta = cuenta();
        Datos datos = datos(cuenta, "PC Aplicaciones");
        Instant inicio = Instant.now().minusSeconds(900);
        usos.save(new UsoAplicacion(cuenta.familia(), datos.menor(), datos.dispositivo(),
                datos.aplicacion(), inicio, inicio.plusSeconds(600)));
        mvc.perform(get("/api/applications")
                        .param("nombre", "navegador")
                        .header(HttpHeaders.AUTHORIZATION, bearer(cuenta.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Navegador Familiar"))
                .andExpect(jsonPath("$[0].tiempoAcumuladoSegundos").value(600));
    }

    @Test
    void reporteSinDatosContieneSerieVaciaEnValores() throws Exception {
        Cuenta cuenta = cuenta();
        mvc.perform(get("/api/reports/usage")
                        .param("desde", LocalDate.now().minusDays(2).toString())
                        .param("hasta", LocalDate.now().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(cuenta.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiempoTotalSegundos").value(0))
                .andExpect(jsonPath("$.usoDiario.length()").value(3))
                .andExpect(jsonPath("$.rankingAplicaciones.length()").value(0));
    }

    @Test
    void reporteAgrupaDatosRealesPorDiaYAplicacion() throws Exception {
        Cuenta cuenta = cuenta();
        Datos datos = datos(cuenta, "PC Reportes");
        Instant inicio = Instant.now().minusSeconds(1200);
        usos.save(new UsoAplicacion(cuenta.familia(), datos.menor(), datos.dispositivo(),
                datos.aplicacion(), inicio, inicio.plusSeconds(900)));
        mvc.perform(get("/api/reports/usage")
                        .param("desde", LocalDate.now().minusDays(1).toString())
                        .param("hasta", LocalDate.now().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(cuenta.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiempoTotalSegundos").value(900))
                .andExpect(jsonPath("$.rankingAplicaciones[0].nombre").value("Navegador Familiar"));
    }

    @Test
    void filtrosNoPermitenVerDatosDeOtraFamilia() throws Exception {
        Cuenta familiaA = cuenta();
        Cuenta familiaB = cuenta();
        Datos ajenos = datos(familiaB, "PC Ajeno");
        String response = mvc.perform(get("/api/devices")
                        .header(HttpHeaders.AUTHORIZATION, bearer(familiaA.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("PC Ajeno");
        mvc.perform(get("/api/applications")
                        .param("dispositivoId", ajenos.dispositivo().getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(familiaA.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void dashboardRechazaAccesoSinAutenticacion() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void sesionExpiradaNoPermiteConsultarElDashboard() throws Exception {
        Cuenta cuenta = cuenta();
        // expirar() fija expira_en = ahora - 1 ms y la tabla exige expira_en > creada_en (ck_sesiones_expiracion).
        // Si la sesión se acaba de crear en el mismo milisegundo, ese valor no es posterior a creada_en: se espera
        // a que transcurra un margen para que la sesión vencida sea válida para el esquema y esté realmente vencida.
        java.time.Instant creadaComoMaximo = java.time.Instant.now();
        while (!java.time.Instant.now().isAfter(creadaComoMaximo.plusMillis(5))) Thread.sleep(2);
        var sesion = sesiones.findAll().stream()
                .max(java.util.Comparator.comparing(com.familyguard.familyguard.domain.model.SesionAutenticacion::getId))
                .orElseThrow();
        sesion.expirar();
        sesiones.saveAndFlush(sesion);

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, bearer(cuenta.token())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validaRangoDeFechasDelReporte() throws Exception {
        Cuenta cuenta = cuenta();
        mvc.perform(get("/api/reports/usage")
                        .param("desde", "2026-11-15").param("hasta", "2026-01-01")
                        .header(HttpHeaders.AUTHORIZATION, bearer(cuenta.token())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    private Cuenta cuenta() throws Exception {
        String email = "dashboard-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nombre":"Padre Dashboard","email":"%s","password":"%s",
                         "confirmarPassword":"%s","nombreFamilia":"Familia Dashboard"}
                        """.formatted(email, PASSWORD, PASSWORD))).andExpect(status().isCreated());
        String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var usuario = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        return new Cuenta(JsonPath.read(json, "$.accessToken"), usuario.getFamilia());
    }

    private Datos datos(Cuenta cuenta, String dispositivoNombre) {
        Menor menor = menores.save(new Menor(cuenta.familia(), "Hijo Datos"));
        Dispositivo dispositivo = new Dispositivo(cuenta.familia(), dispositivoNombre,
                UUID.randomUUID().toString(), TipoDispositivo.WINDOWS);
        dispositivo.setMenor(menor);
        dispositivos.save(dispositivo);
        Aplicacion aplicacion = aplicaciones.save(new Aplicacion(cuenta.familia(), dispositivo,
                "Navegador Familiar", "C:\\FamilyGuard\\browser.exe"));
        return new Datos(menor, dispositivo, aplicacion);
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Cuenta(String token, com.familyguard.familyguard.domain.model.Familia familia) { }
    private record Datos(Menor menor, Dispositivo dispositivo, Aplicacion aplicacion) { }
}
