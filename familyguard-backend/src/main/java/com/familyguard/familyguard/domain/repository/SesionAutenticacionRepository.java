package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.SesionAutenticacion;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SesionAutenticacionRepository extends JpaRepository<SesionAutenticacion, Long> {
    Optional<SesionAutenticacion> findByTokenHashAndRevocadaEnIsNullAndExpiraEnAfter(
            String tokenHash, Instant ahora);
    List<SesionAutenticacion> findAllByUsuario_IdAndRevocadaEnIsNull(Long usuarioId);
}
