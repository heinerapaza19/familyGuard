package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.Dispositivo;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface DispositivoRepository extends JpaRepository<Dispositivo, Long> {
    List<Dispositivo> findAllByFamilia_IdAndActivoTrue(Long familiaId);
    Optional<Dispositivo> findByFamilia_IdAndId(Long familiaId, Long id);
    long countByFamilia_IdAndActivoTrue(Long familiaId);
    boolean existsByIdentificador(String identificador);
    /** Bloquea el registro para que dos revinculaciones simultáneas no emitan credenciales en paralelo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Dispositivo> findByIdentificador(String identificador);
    Optional<Dispositivo> findByAgenteTokenHashAndAgenteRevocadoFalseAndActivoTrue(String tokenHash);
}
