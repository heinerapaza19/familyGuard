package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.AutorizacionDispositivo;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutorizacionDispositivoRepository extends JpaRepository<AutorizacionDispositivo, Long> {
    List<AutorizacionDispositivo> findTop50ByFamiliaIdAndDispositivo_IdOrderByIdDesc(Long familiaId, Long dispositivoId);
    Optional<AutorizacionDispositivo> findByIdAndFamiliaId(Long id, Long familiaId);
}
