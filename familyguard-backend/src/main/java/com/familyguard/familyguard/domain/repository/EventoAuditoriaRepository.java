package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.EventoAuditoria;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

public interface EventoAuditoriaRepository extends JpaRepository<EventoAuditoria, Long> {
    boolean existsByEventoUuid(String eventoUuid);
    List<EventoAuditoria> findAllByFamilia_IdAndOcurridoEnBetweenOrderByOcurridoEnDesc(
            Long familiaId, Instant desde, Instant hasta);
    List<EventoAuditoria> findAllByFamilia_IdOrderByOcurridoEnDesc(Long familiaId, Pageable pageable);
    long countByFamilia_IdAndActivoTrue(Long familiaId);
    java.util.Optional<EventoAuditoria> findFirstByFamilia_IdAndTipoEventoAndTipoEntidadAndEntidadIdOrderByOcurridoEnDesc(
            Long familiaId, String tipoEvento, String tipoEntidad, Long entidadId);
    /** Último aviso de conexión (SIN_CONEXION o RECONECTADO) de un equipo. */
    java.util.Optional<EventoAuditoria> findFirstByDispositivo_IdAndTipoEventoInOrderByOcurridoEnDescIdDesc(
            Long dispositivoId, java.util.Collection<String> tipos);
}
