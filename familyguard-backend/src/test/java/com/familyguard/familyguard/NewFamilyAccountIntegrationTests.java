package com.familyguard.familyguard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Una cuenta nueva empieza vacía: el registro no pide ni crea hijos, no hay datos de ejemplo y el panel funciona
 * sin hijos. El padre agrega hijos cuando quiere y solo ve los de su propia familia.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class NewFamilyAccountIntegrationTests {
    private static final String PASSWORD = "ClaveSegura#2026";
    @Autowired MockMvc mvc;

    private String cuenta(String familia) throws Exception {
        String email = "nueva-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Padre o madre","email":"%s","password":"%s","confirmarPassword":"%s","nombreFamilia":"%s"}
                """.formatted(email, PASSWORD, PASSWORD, familia))).andExpect(status().isCreated());
        return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void cuentaNuevaSinHijosNiDatosDeEjemploYConPanelAccesible() throws Exception {
        String sesion = "Bearer " + cuenta("Familia nueva");
        mvc.perform(get("/api/children").header(HttpHeaders.AUTHORIZATION, sesion))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/devices/panel").header(HttpHeaders.AUTHORIZATION, sesion))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/restrictions").header(HttpHeaders.AUTHORIZATION, sesion))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/web-restrictions").header(HttpHeaders.AUTHORIZATION, sesion))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hijosRegistrados").value(0))
                .andExpect(jsonPath("$.dispositivosVinculados").value(0));
    }

    @Test
    void elPadreAgregaVariosHijosCuandoQuiereYSoloVeLosSuyos() throws Exception {
        String a = "Bearer " + cuenta("Familia A");
        String b = "Bearer " + cuenta("Familia B");
        for (String nombre : new String[] {"Primer hijo", "Segundo hijo"})
            mvc.perform(post("/api/children").header(HttpHeaders.AUTHORIZATION, a).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nombre\":\"%s\"}".formatted(nombre))).andExpect(status().isCreated());
        mvc.perform(get("/api/children").header(HttpHeaders.AUTHORIZATION, a))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/children").header(HttpHeaders.AUTHORIZATION, b))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
