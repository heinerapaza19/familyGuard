package com.familyguard.familyguard.service;

import com.familyguard.familyguard.api.dto.PerfilResponse;
import com.familyguard.familyguard.api.dto.RegistroPadreRequest;
import com.familyguard.familyguard.domain.model.EstadoCuenta;
import com.familyguard.familyguard.domain.model.Familia;
import com.familyguard.familyguard.domain.model.RolUsuario;
import com.familyguard.familyguard.domain.model.Usuario;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.familyguard.familyguard.service.exception.ConflictException;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistroService {

    private final FamiliaRepository familias;
    private final UsuarioRepository usuarios;
    private final PasswordEncoder passwordEncoder;

    public RegistroService(FamiliaRepository familias, UsuarioRepository usuarios,
                           PasswordEncoder passwordEncoder) {
        this.familias = familias;
        this.usuarios = usuarios;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public PerfilResponse registrarPadre(RegistroPadreRequest request) {
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        if (usuarios.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Ese usuario ya existe; elige otro");
        }
        var familia = familias.save(new Familia(request.nombreFamilia().strip(), "America/Lima"));
        var usuario = new Usuario(familia, request.nombre().strip(), email,
                passwordEncoder.encode(request.password()), RolUsuario.PADRE);
        usuario.setEstadoCuenta(EstadoCuenta.ACTIVA);
        usuarios.save(usuario);
        return perfil(usuario);
    }

    static PerfilResponse perfil(Usuario usuario) {
        return new PerfilResponse(usuario.getId(), usuario.getNombre(), usuario.getEmail(),
                usuario.getRol().name(), usuario.getFamilia().getId(), usuario.getFamilia().getNombre(),
                usuario.getEstadoCuenta().name());
    }
}
