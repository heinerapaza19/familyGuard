package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.Menor;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenorRepository extends JpaRepository<Menor, Long> {
    List<Menor> findAllByFamilia_IdAndActivoTrue(Long familiaId);
    List<Menor> findAllByFamilia_IdOrderByNombreAsc(Long familiaId);
    Optional<Menor> findByIdAndFamilia_Id(Long id, Long familiaId);
    Optional<Menor> findByIdAndFamilia_IdAndActivoTrue(Long id, Long familiaId);
    long countByFamilia_IdAndActivoTrue(Long familiaId);
}
