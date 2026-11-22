package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.Aplicacion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AplicacionRepository extends JpaRepository<Aplicacion, Long> {
    List<Aplicacion> findAllByFamilia_IdAndDispositivo_IdAndActivoTrue(Long familiaId, Long dispositivoId);
    List<Aplicacion> findAllByFamilia_IdAndActivoTrue(Long familiaId);
    long countByFamilia_IdAndActivoTrue(Long familiaId);
    Optional<Aplicacion> findByDispositivo_IdAndRutaEjecutable(Long dispositivoId, String rutaEjecutable);
}
