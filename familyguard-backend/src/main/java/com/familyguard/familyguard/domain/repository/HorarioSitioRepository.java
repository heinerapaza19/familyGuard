package com.familyguard.familyguard.domain.repository;
import com.familyguard.familyguard.domain.model.HorarioSitio;import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;
public interface HorarioSitioRepository extends JpaRepository<HorarioSitio,Long>{List<HorarioSitio> findAllBySitio_IdAndActivoTrue(Long id);List<HorarioSitio> findAllBySitio_Id(Long id);}
