package com.familyguard.familyguard.domain.repository;
import com.familyguard.familyguard.domain.model.EventoSitio;import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;
public interface EventoSitioRepository extends JpaRepository<EventoSitio,Long>{boolean existsByUuid(String uuid);List<EventoSitio> findTop100ByFamilia_IdOrderByOcurridoEnDesc(Long familiaId);void deleteAllByFamilia_Id(Long familiaId);
 /** Eventos de una regla (para «Ver detalles»), más recientes primero. */
 List<EventoSitio> findTop50BySitio_IdAndFamilia_IdOrderByOcurridoEnDesc(Long sitioId,Long familiaId);}
