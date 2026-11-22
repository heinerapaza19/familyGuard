package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.OperacionMasiva;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperacionMasivaRepository extends JpaRepository<OperacionMasiva, Long> {
    Optional<OperacionMasiva> findByFamilia_IdAndOperacionUuid(Long familiaId, String operacionUuid);
    Optional<OperacionMasiva> findByIdAndFamilia_Id(Long id, Long familiaId);
    List<OperacionMasiva> findTop50ByFamilia_IdOrderByCreadoEnDescIdDesc(Long familiaId);
}
