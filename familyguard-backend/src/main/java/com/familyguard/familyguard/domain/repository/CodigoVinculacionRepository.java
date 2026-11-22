package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.CodigoVinculacion;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodigoVinculacionRepository extends JpaRepository<CodigoVinculacion, Long> {
    Optional<CodigoVinculacion> findByCodigoHashAndUsadoEnIsNullAndExpiraEnAfter(String hash, Instant ahora);
    Optional<CodigoVinculacion> findByCodigoHash(String hash);
    Optional<CodigoVinculacion> findByIdAndFamilia_Id(Long id, Long familiaId);
    /** Búsqueda usada al vincular: excluye códigos usados, anulados o vencidos. */
    Optional<CodigoVinculacion> findByCodigoHashAndUsadoEnIsNullAndBloqueadoEnIsNullAndExpiraEnAfter(String hash, Instant ahora);
    java.util.List<CodigoVinculacion> findAllByFamilia_IdAndMenor_IdAndUsadoEnIsNullAndBloqueadoEnIsNullAndExpiraEnAfter(
            Long familiaId, Long menorId, Instant ahora);
}
