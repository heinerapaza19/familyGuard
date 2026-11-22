package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.Familia;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamiliaRepository extends JpaRepository<Familia, Long> {
    /** Serializa las operaciones masivas de una misma familia (reintentos y solicitudes simultáneas). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Familia f where f.id = :id")
    Optional<Familia> bloquearParaOperacion(@Param("id") Long id);
}
