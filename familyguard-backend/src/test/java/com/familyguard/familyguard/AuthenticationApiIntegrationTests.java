package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyguard.familyguard.domain.repository.SesionAutenticacionRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationApiIntegrationTests {

    private static final String PASSWORD = "ClaveSegura#2026";

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarios;
    @Autowired private SesionAutenticacionRepository sesiones;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void registraPadreYFamiliaConPasswordBcrypt() throws Exception {
        String email = emailUnico();

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registroJson(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.rol").value("PADRE"))
                .andExpect(jsonPath("$.familiaId").isNumber());

        var usuario = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(usuario.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, usuario.getPasswordHash())).isTrue();
    }

    @Test
    void registraEIniciaSesionConNombreDeUsuario() throws Exception {
        String usuario = "padre." + java.util.UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registroJson(usuario)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(usuario));
        // Se puede entrar con el usuario sin importar mayúsculas.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(usuario.toUpperCase(java.util.Locale.ROOT), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
        // Un usuario repetido se rechaza con un mensaje claro.
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registroJson(usuario)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ese usuario ya existe; elige otro"));
    }

    @Test
    void rechazaCorreosDuplicados() throws Exception {
        String email = emailUnico();
        registrar(email);

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registroJson(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void rechazaPasswordIncorrecto() throws Exception {
        String email = emailUnico();
        registrar(email);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "OtraClave#2026")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Credenciales invalidas"));
    }

    @Test
    void loginValidoEntregaTokenOpacoYNoLoPersisteEnClaro() throws Exception {
        String email = emailUnico();
        registrar(email);

        String token = login(email);

        assertThat(token).isNotBlank().doesNotContain(".");
        assertThat(sesiones.findAll()).anySatisfy(sesion -> {
            assertThat(sesion.getTokenHash()).hasSize(64);
            assertThat(sesion.getTokenHash()).isNotEqualTo(token);
        });
    }

    @Test
    void loginInvalidoNoRevelaSiElCorreoExiste() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(emailUnico(), PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Credenciales invalidas"));
    }

    @Test
    void bloqueaTemporalmenteTrasCincoIntentosFallidos() throws Exception {
        String email = emailUnico();
        registrar(email);

        for (int intento = 0; intento < 5; intento++) {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(email, "OtraClave#2026")))
                    .andExpect(status().isUnauthorized());
        }

        var usuario = usuarios.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(usuario.getIntentosFallidos()).isEqualTo(5);
        assertThat(usuario.getBloqueadoHasta()).isNotNull();
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Credenciales invalidas"));
    }

    @Test
    void rechazaAccesoPrivadoSinAutenticacion() throws Exception {
        mvc.perform(get("/api/children"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void registraHijoEnLaFamiliaAutenticada() throws Exception {
        String token = registrarYLogin();

        mvc.perform(post("/api/children")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(menorJson("Ana Familiar", "2014-05-12", "Ana")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Ana Familiar"))
                .andExpect(jsonPath("$.activo").value(true));
    }

    @Test
    void modificaPerfilDeHijo() throws Exception {
        String token = registrarYLogin();
        long id = crearMenor(token, "Perfil Inicial");

        mvc.perform(put("/api/children/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(menorJson("Perfil Actualizado", "2013-03-03", "Nuevo perfil")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Perfil Actualizado"))
                .andExpect(jsonPath("$.nombrePerfil").value("Nuevo perfil"));
    }

    @Test
    void cierreDeSesionRevocaElToken() throws Exception {
        String token = registrarYLogin();

        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actualizaPerfilConReautenticacionYCorreoUnico() throws Exception {
        String email = emailUnico(); registrar(email); String token = login(email);
        String nuevo = emailUnico();
        mvc.perform(put("/api/auth/me").header(HttpHeaders.AUTHORIZATION,bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Nombre Renovado\",\"email\":\"%s\",\"passwordActual\":\"%s\"}".formatted(nuevo,PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Nombre Renovado"))
                .andExpect(jsonPath("$.email").value(nuevo));
        mvc.perform(put("/api/auth/me").header(HttpHeaders.AUTHORIZATION,bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"No\",\"email\":\"%s\",\"passwordActual\":\"incorrecta\"}".formatted(nuevo)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cambioPasswordRevocaSesionesYExigeNuevaClave() throws Exception {
        String email=emailUnico();registrar(email);String token=login(email);String nueva="NuevaClave#2027";
        mvc.perform(put("/api/auth/password").header(HttpHeaders.AUTHORIZATION,bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passwordActual\":\"%s\",\"passwordNuevo\":\"%s\",\"confirmarPassword\":\"%s\"}".formatted(PASSWORD,nueva,nueva)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION,bearer(token))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginJson(email,PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginJson(email,nueva)))
                .andExpect(status().isOk());
    }

    @Test
    void perfilDeHijoPuedeDesactivarseYReactivarseSinBorrarDatos() throws Exception {
        String token=registrarYLogin();long id=crearMenor(token,"Perfil reversible");
        mvc.perform(delete("/api/children/{id}",id).header(HttpHeaders.AUTHORIZATION,bearer(token)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/children/{id}",id).header(HttpHeaders.AUTHORIZATION,bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(false));
        mvc.perform(patch("/api/children/{id}/active",id).param("value","true")
                        .header(HttpHeaders.AUTHORIZATION,bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(true));
    }

    @Test
    void impideConsultarModificarYDesactivarHijosDeOtraFamilia() throws Exception {
        String tokenA = registrarYLogin();
        String tokenB = registrarYLogin();
        long idFamiliaB = crearMenor(tokenB, "Hijo Familia B");

        mvc.perform(get("/api/children/{id}", idFamiliaB)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/children/{id}", idFamiliaB)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(menorJson("Intrusion", "2012-02-02", "Ajeno")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/children/{id}", idFamiliaB)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isNotFound());
    }

    @Test
    void registroPublicoRechazaAsignacionDeRol() throws Exception {
        String email = emailUnico();
        String json = registroJson(email).replace("}", ",\"rol\":\"ADMIN_SISTEMA\"}");

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        assertThat(usuarios.findByEmailIgnoreCase(email)).isEmpty();
    }

    @Test
    void validaPasswordConfirmacionYCamposObligatorios() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"","email":"usuario con espacios","password":"corta",
                                 "confirmarPassword":"distinta","nombreFamilia":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.nombre").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.nombreFamilia").exists());
    }

    private void registrar(String email) throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registroJson(email)))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        String response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private String registrarYLogin() throws Exception {
        String email = emailUnico();
        registrar(email);
        return login(email);
    }

    private long crearMenor(String token, String nombre) throws Exception {
        String response = mvc.perform(post("/api/children")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(menorJson(nombre, "2015-04-10", "Perfil")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(response, "$.id");
        return id.longValue();
    }

    private static String registroJson(String email) {
        return """
                {"nombre":"Madre o Padre Prueba","email":"%s","password":"%s",
                 "confirmarPassword":"%s","nombreFamilia":"Familia %s"}
                """.formatted(email, PASSWORD, PASSWORD, UUID.randomUUID());
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private static String menorJson(String nombre, String fecha, String perfil) {
        return "{\"nombre\":\"%s\",\"fechaNacimiento\":\"%s\",\"nombrePerfil\":\"%s\"}"
                .formatted(nombre, fecha, perfil);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String emailUnico() {
        return "etapa3-" + UUID.randomUUID() + "@example.test";
    }
}
