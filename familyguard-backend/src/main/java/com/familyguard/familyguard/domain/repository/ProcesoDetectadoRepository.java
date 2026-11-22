package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.ProcesoDetectado;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcesoDetectadoRepository extends JpaRepository<ProcesoDetectado, Long> {
    Optional<ProcesoDetectado> findByDispositivo_IdAndPid(Long dispositivoId, long pid);
    List<ProcesoDetectado> findAllByDispositivo_Id(Long dispositivoId);
}
