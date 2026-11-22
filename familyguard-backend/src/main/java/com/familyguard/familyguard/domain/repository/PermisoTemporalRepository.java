package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.PermisoTemporal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermisoTemporalRepository extends JpaRepository<PermisoTemporal, Long> {
    Optional<PermisoTemporal> findByFamilia_IdAndConcesionUuid(Long familiaId, String concesionUuid);
}
