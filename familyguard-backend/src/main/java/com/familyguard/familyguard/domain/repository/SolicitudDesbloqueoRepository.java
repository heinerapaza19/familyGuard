package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.SolicitudDesbloqueo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SolicitudDesbloqueoRepository extends JpaRepository<SolicitudDesbloqueo, Long> {
    List<SolicitudDesbloqueo> findAllByFamilia_IdOrderByCreadoEnDesc(Long familiaId, Pageable page);
    List<SolicitudDesbloqueo> findAllByDispositivo_IdOrderByCreadoEnDesc(Long dispositivoId, Pageable page);
    Optional<SolicitudDesbloqueo> findByIdAndFamilia_Id(Long id, Long familiaId);
    long countByFamilia_IdAndEstado(Long familiaId, String estado);
    long countByDispositivo_IdAndCreadoEnAfter(Long dispositivoId, Instant desde);
    boolean existsByDispositivo_IdAndTipoAndAplicacionIdAndEstado(Long dispositivoId, String tipo, Long aplicacionId, String estado);
    boolean existsByDispositivo_IdAndTipoAndSitioIdAndEstado(Long dispositivoId, String tipo, Long sitioId, String estado);
}
