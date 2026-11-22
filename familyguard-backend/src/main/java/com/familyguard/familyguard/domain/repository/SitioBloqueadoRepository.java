package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.SitioBloqueado;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SitioBloqueadoRepository extends JpaRepository<SitioBloqueado, Long> {
    List<SitioBloqueado> findAllByFamilia_IdAndMenor_IdAndActivoTrueOrderByDominio(
            Long familiaId, Long menorId);
    long countByFamilia_IdAndActivoTrue(Long familiaId);
    List<SitioBloqueado> findAllByFamilia_IdOrderByDominio(Long familiaId);
    java.util.Optional<SitioBloqueado> findByIdAndFamilia_Id(Long id,Long familiaId);
    java.util.Optional<SitioBloqueado> findByFamilia_IdAndMenor_IdAndDominio(Long familiaId, Long menorId, String dominio);
}
