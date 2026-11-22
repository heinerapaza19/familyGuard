package com.familyguard.familyguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familyguard.familyguard.domain.model.Aplicacion;
import com.familyguard.familyguard.domain.model.Dispositivo;
import com.familyguard.familyguard.domain.model.EventoAuditoria;
import com.familyguard.familyguard.domain.model.Familia;
import com.familyguard.familyguard.domain.model.HorarioRestriccion;
import com.familyguard.familyguard.domain.model.LimiteTiempo;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.model.PeriodoLimite;
import com.familyguard.familyguard.domain.model.ResultadoEvento;
import com.familyguard.familyguard.domain.model.RolUsuario;
import com.familyguard.familyguard.domain.model.SitioBloqueado;
import com.familyguard.familyguard.domain.model.TipoDispositivo;
import com.familyguard.familyguard.domain.model.UsoAplicacion;
import com.familyguard.familyguard.domain.model.Usuario;
import com.familyguard.familyguard.domain.repository.AplicacionRepository;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.EventoAuditoriaRepository;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.HorarioRestriccionRepository;
import com.familyguard.familyguard.domain.repository.LimiteTiempoRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.domain.repository.SitioBloqueadoRepository;
import com.familyguard.familyguard.domain.repository.UsoAplicacionRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class PersistenceIntegrationTests {

    @Autowired private FamiliaRepository familias;
    @Autowired private UsuarioRepository usuarios;
    @Autowired private MenorRepository menores;
    @Autowired private DispositivoRepository dispositivos;
    @Autowired private AplicacionRepository aplicaciones;
    @Autowired private UsoAplicacionRepository usos;
    @Autowired private LimiteTiempoRepository limites;
    @Autowired private SitioBloqueadoRepository sitios;
    @Autowired private HorarioRestriccionRepository horarios;
    @Autowired private EventoAuditoriaRepository eventos;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private Flyway flyway;
    @Autowired private EntityManager entityManager;

    @Test
    void flywayAplicaLasMigracionesDisponibles() {
        assertThat(flyway.info().current()).isNotNull();
        // V8 (aditiva) registra el estado real de aplicación del bloqueo web por regla y dispositivo.
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void persisteRelacionesYResuelveLasConsultasPrincipales() {
        var datos = crearDatos("Familia Norte", "equipo-norte");
        Instant inicio = Instant.parse("2026-11-14T14:00:00Z");
        var uso = usos.save(new UsoAplicacion(datos.familia(), datos.menor(), datos.dispositivo(),
                datos.aplicacion(), inicio, inicio.plusSeconds(1800)));
        var limite = limites.save(new LimiteTiempo(datos.familia(), datos.menor(), datos.aplicacion(),
                PeriodoLimite.DIARIO, 90));
        var sitio = sitios.save(new SitioBloqueado(datos.familia(), datos.menor(), "https://example.com/ruta"));
        var horario = horarios.save(new HorarioRestriccion(datos.familia(), datos.menor(),
                DayOfWeek.MONDAY, LocalTime.of(21, 0), LocalTime.of(7, 0)));
        var usuario = usuarios.save(new Usuario(datos.familia(), "Tutor Norte", "tutor.norte@example.test",
                passwordEncoder.encode("clave-de-prueba"), RolUsuario.TUTOR));
        var evento = new EventoAuditoria(datos.familia(), "REGLA_CREADA", "Regla de prueba", ResultadoEvento.EXITO);
        evento.setUsuario(usuario);
        evento.setDispositivo(datos.dispositivo());
        eventos.save(evento);
        entityManager.flush();

        assertThat(uso.getId()).isNotNull();
        assertThat(dispositivos.findAllByFamilia_IdAndActivoTrue(datos.familia().getId()))
                .extracting(Dispositivo::getId).containsExactly(datos.dispositivo().getId());
        assertThat(aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(
                datos.familia().getId(), datos.dispositivo().getId())).hasSize(1);
        assertThat(usos.findAllByFamilia_IdAndDispositivo_IdAndInicioEnGreaterThanEqualAndInicioEnLessThan(
                datos.familia().getId(), datos.dispositivo().getId(), inicio, inicio.plusSeconds(86400)))
                .hasSize(1);
        assertThat(usos.sumarDuracionPorDispositivoYPeriodo(datos.familia().getId(),
                datos.dispositivo().getId(), inicio, inicio.plusSeconds(86400))).isEqualTo(1800);
        assertThat(limites.findByFamilia_IdAndMenor_IdAndAplicacion_IdAndPeriodoAndActivoTrue(
                datos.familia().getId(), datos.menor().getId(), datos.aplicacion().getId(), PeriodoLimite.DIARIO))
                .contains(limite);
        assertThat(sitios.findAllByFamilia_IdAndMenor_IdAndActivoTrueOrderByDominio(
                datos.familia().getId(), datos.menor().getId())).containsExactly(sitio);
        assertThat(sitio.getDominio()).isEqualTo("example.com");
        assertThat(horarios.findAllByFamilia_IdAndMenor_IdAndDiaSemanaAndActivoTrue(
                datos.familia().getId(), datos.menor().getId(), DayOfWeek.MONDAY)).containsExactly(horario);
        assertThat(horario.isCruzaMedianoche()).isTrue();
    }

    @Test
    void lasConsultasNoMezclanFamilias() {
        var primera = crearDatos("Familia Uno", "equipo-uno");
        var segunda = crearDatos("Familia Dos", "equipo-dos");

        assertThat(dispositivos.findAllByFamilia_IdAndActivoTrue(primera.familia().getId()))
                .extracting(Dispositivo::getIdentificador).containsExactly("equipo-uno");
        assertThat(dispositivos.findByFamilia_IdAndId(primera.familia().getId(), segunda.dispositivo().getId()))
                .isEmpty();
        assertThat(aplicaciones.findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(
                primera.familia().getId(), segunda.dispositivo().getId())).isEmpty();
    }

    @Test
    void laBaseRechazaUnaRelacionEntreFamilias() {
        var primera = crearDatos("Familia Segura", "equipo-seguro");
        var segunda = crearDatos("Familia Ajena", "equipo-ajeno");
        var invalido = new Dispositivo(primera.familia(), "Equipo invalido", "equipo-invalido",
                TipoDispositivo.WINDOWS);
        invalido.setMenor(segunda.menor());

        assertThatThrownBy(() -> dispositivos.saveAndFlush(invalido))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void lasContrasenasSeGuardanComoHashBcrypt() {
        var familia = familias.save(new Familia("Familia Hash", "America/Lima"));
        String hash = passwordEncoder.encode("una-clave-segura-de-prueba");
        var usuario = usuarios.saveAndFlush(new Usuario(familia, "Tutor Hash", "hash@example.test",
                hash, RolUsuario.PADRE));

        assertThat(usuario.getPasswordHash()).startsWith("$2").doesNotContain("una-clave-segura-de-prueba");
        assertThat(passwordEncoder.matches("una-clave-segura-de-prueba", usuario.getPasswordHash())).isTrue();
    }

    private Datos crearDatos(String nombreFamilia, String identificador) {
        var familia = familias.save(new Familia(nombreFamilia, "America/Lima"));
        var menor = menores.save(new Menor(familia, "Menor " + identificador));
        var dispositivo = new Dispositivo(familia, "PC " + identificador, identificador, TipoDispositivo.WINDOWS);
        dispositivo.setMenor(menor);
        dispositivos.save(dispositivo);
        var aplicacion = aplicaciones.save(new Aplicacion(familia, dispositivo, "Navegador " + identificador,
                "C:\\Program Files\\" + identificador + "\\app.exe"));
        return new Datos(familia, menor, dispositivo, aplicacion);
    }

    private record Datos(Familia familia, Menor menor, Dispositivo dispositivo, Aplicacion aplicacion) {
    }
}
