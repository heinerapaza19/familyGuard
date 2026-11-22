package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.PermisoTemporalDispositivo;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermisoTemporalDispositivoRepository extends JpaRepository<PermisoTemporalDispositivo, Long> {
    List<PermisoTemporalDispositivo> findAllByPermiso_IdOrderByIdAsc(Long permisoId);
    List<PermisoTemporalDispositivo> findTop200ByFamiliaIdOrderByIdDesc(Long familiaId);
    List<PermisoTemporalDispositivo> findTop200ByFamiliaIdAndDispositivo_IdOrderByIdDesc(Long familiaId, Long dispositivoId);
    List<PermisoTemporalDispositivo> findAllByFamiliaIdAndDispositivo_IdAndRevocadoEnIsNull(Long familiaId, Long dispositivoId);
    Optional<PermisoTemporalDispositivo> findByIdAndFamiliaId(Long id, Long familiaId);
}
