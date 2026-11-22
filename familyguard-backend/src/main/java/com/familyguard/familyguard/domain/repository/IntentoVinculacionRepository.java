package com.familyguard.familyguard.domain.repository;
import com.familyguard.familyguard.domain.model.IntentoVinculacion;import java.util.Optional;import org.springframework.data.jpa.repository.JpaRepository;
public interface IntentoVinculacionRepository extends JpaRepository<IntentoVinculacion,Long>{Optional<IntentoVinculacion> findByHash(String hash);}
