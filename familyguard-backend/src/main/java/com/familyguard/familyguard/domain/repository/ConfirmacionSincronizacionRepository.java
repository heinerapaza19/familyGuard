package com.familyguard.familyguard.domain.repository;
import com.familyguard.familyguard.domain.model.ConfirmacionSincronizacion;import org.springframework.data.jpa.repository.JpaRepository;
public interface ConfirmacionSincronizacionRepository extends JpaRepository<ConfirmacionSincronizacion,Long>{boolean existsByUuid(String uuid);}
