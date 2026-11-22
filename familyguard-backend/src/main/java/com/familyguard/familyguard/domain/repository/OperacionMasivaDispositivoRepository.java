package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.OperacionMasivaDispositivo;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperacionMasivaDispositivoRepository extends JpaRepository<OperacionMasivaDispositivo, Long> {
    List<OperacionMasivaDispositivo> findAllByOperacion_IdAndFamiliaIdOrderByIdAsc(Long operacionId, Long familiaId);
}
