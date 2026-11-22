package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.LimiteTiempo;
import com.familyguard.familyguard.domain.model.PeriodoLimite;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LimiteTiempoRepository extends JpaRepository<LimiteTiempo, Long> {
    List<LimiteTiempo> findAllByFamilia_IdAndMenor_IdAndActivoTrue(Long familiaId, Long menorId);
    Optional<LimiteTiempo> findByFamilia_IdAndMenor_IdAndAplicacion_IdAndPeriodoAndActivoTrue(
            Long familiaId, Long menorId, Long aplicacionId, PeriodoLimite periodo);
    Optional<LimiteTiempo> findByFamilia_IdAndMenor_IdAndAplicacion_IdAndPeriodo(
            Long familiaId, Long menorId, Long aplicacionId, PeriodoLimite periodo);
    long countByFamilia_IdAndActivoTrue(Long familiaId);
    List<LimiteTiempo> findAllByFamilia_IdAndActivoTrue(Long familiaId);
    List<LimiteTiempo> findAllByFamilia_Id(Long familiaId);
    Optional<LimiteTiempo> findByIdAndFamilia_Id(Long id, Long familiaId);
}
