package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.Usuario;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    List<Usuario> findAllByFamilia_IdAndActivoTrue(Long familiaId);
}
