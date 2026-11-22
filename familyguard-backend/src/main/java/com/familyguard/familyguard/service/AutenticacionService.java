package com.familyguard.familyguard.service;

import com.familyguard.familyguard.api.dto.LoginRequest;
import com.familyguard.familyguard.api.dto.LoginResponse;
import com.familyguard.familyguard.api.dto.PerfilResponse;
import com.familyguard.familyguard.api.dto.ActualizarPerfilRequest;
import com.familyguard.familyguard.api.dto.CambiarPasswordRequest;
import com.familyguard.familyguard.domain.model.EstadoCuenta;
import com.familyguard.familyguard.domain.model.SesionAutenticacion;
import com.familyguard.familyguard.domain.model.Usuario;
import com.familyguard.familyguard.domain.repository.SesionAutenticacionRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.exception.UnauthorizedException;
import com.familyguard.familyguard.service.exception.ConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutenticacionService {

    private static final int MAX_INTENTOS = 5;
    private static final Duration BLOQUEO = Duration.ofMinutes(15);
    private static final Duration DURACION_SESION = Duration.ofHours(8);

    private final UsuarioRepository usuarios;
    private final SesionAutenticacionRepository sesiones;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String hashSimulado;

    public AutenticacionService(UsuarioRepository usuarios, SesionAutenticacionRepository sesiones,
                                PasswordEncoder passwordEncoder) {
        this.usuarios = usuarios;
        this.sesiones = sesiones;
        this.passwordEncoder = passwordEncoder;
        this.hashSimulado = passwordEncoder.encode("FamilyGuard-Dummy-Password-Only-For-Timing");
    }

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResponse login(LoginRequest request) {
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        Optional<Usuario> encontrado = usuarios.findByEmailIgnoreCase(email);
        Usuario usuario = encontrado.orElse(null);
        Instant ahora = Instant.now();

        if (usuario == null) {
            passwordEncoder.matches(request.password(), hashSimulado);
            throw credencialesInvalidas();
        }
        if (!usuario.isActivo() || usuario.getEstadoCuenta() != EstadoCuenta.ACTIVA
                || (usuario.getBloqueadoHasta() != null && usuario.getBloqueadoHasta().isAfter(ahora))) {
            passwordEncoder.matches(request.password(), usuario.getPasswordHash());
            throw credencialesInvalidas();
        }
        if (!passwordEncoder.matches(request.password(), usuario.getPasswordHash())) {
            registrarFallo(usuario, ahora);
            throw credencialesInvalidas();
        }

        usuario.setIntentosFallidos(0);
        usuario.setBloqueadoHasta(null);
        usuario.setUltimoAcceso(ahora);
        String token = nuevoToken();
        Instant expira = ahora.plus(DURACION_SESION);
        sesiones.save(new SesionAutenticacion(usuario.getFamilia(), usuario, hashToken(token), expira));
        return new LoginResponse(token, "Bearer", expira, RegistroService.perfil(usuario));
    }

    @Transactional(readOnly = true)
    public Optional<UsuarioPrincipal> autenticar(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sesiones.findByTokenHashAndRevocadaEnIsNullAndExpiraEnAfter(hashToken(token), Instant.now())
                .filter(s -> s.getUsuario().isActivo())
                .filter(s -> s.getUsuario().getEstadoCuenta() == EstadoCuenta.ACTIVA)
                .map(s -> new UsuarioPrincipal(s.getUsuario().getId(), s.getFamilia().getId(),
                        s.getUsuario().getNombre(), s.getUsuario().getEmail(), s.getUsuario().getRol()));
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sesiones.findByTokenHashAndRevocadaEnIsNullAndExpiraEnAfter(hashToken(token), Instant.now())
                .ifPresent(SesionAutenticacion::revocar);
    }

    @Transactional(readOnly = true)
    public PerfilResponse perfil(UsuarioPrincipal principal) {
        Usuario usuario = usuarios.findById(principal.usuarioId()).orElseThrow(AutenticacionService::credencialesInvalidas);
        return RegistroService.perfil(usuario);
    }

    @Transactional
    public PerfilResponse actualizarPerfil(UsuarioPrincipal principal, ActualizarPerfilRequest request) {
        Usuario usuario = usuario(principal);
        verificarPassword(usuario, request.passwordActual());
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        usuarios.findByEmailIgnoreCase(email)
                .filter(otro -> !otro.getId().equals(usuario.getId()))
                .ifPresent(otro -> { throw new ConflictException("Ese usuario ya existe; elige otro"); });
        usuario.setNombre(request.nombre().strip());
        usuario.setEmail(email);
        return RegistroService.perfil(usuario);
    }

    @Transactional
    public void cambiarPassword(UsuarioPrincipal principal, CambiarPasswordRequest request) {
        Usuario usuario = usuario(principal);
        verificarPassword(usuario, request.passwordActual());
        if (!request.passwordNuevo().equals(request.confirmarPassword()))
            throw new IllegalArgumentException("La confirmación de la nueva contraseña no coincide");
        if (passwordEncoder.matches(request.passwordNuevo(), usuario.getPasswordHash()))
            throw new IllegalArgumentException("La nueva contraseña debe ser diferente de la actual");
        usuario.setPasswordHash(passwordEncoder.encode(request.passwordNuevo()));
        sesiones.findAllByUsuario_IdAndRevocadaEnIsNull(usuario.getId()).forEach(SesionAutenticacion::revocar);
    }

    private Usuario usuario(UsuarioPrincipal principal) {
        return usuarios.findById(principal.usuarioId()).filter(Usuario::isActivo)
                .orElseThrow(AutenticacionService::credencialesInvalidas);
    }

    private void verificarPassword(Usuario usuario, String password) {
        if (!passwordEncoder.matches(password, usuario.getPasswordHash()))
            throw new UnauthorizedException("La contraseña actual no es correcta");
    }

    private void registrarFallo(Usuario usuario, Instant ahora) {
        int intentos = usuario.getIntentosFallidos() + 1;
        usuario.setIntentosFallidos(intentos);
        if (intentos >= MAX_INTENTOS) {
            usuario.setBloqueadoHasta(ahora.plus(BLOQUEO));
        }
    }

    private String nuevoToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no esta disponible", exception);
        }
    }

    private static UnauthorizedException credencialesInvalidas() {
        return new UnauthorizedException("Credenciales invalidas");
    }
}
