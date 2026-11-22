package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.UsoAplicacion;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsoAplicacionRepository extends JpaRepository<UsoAplicacion, Long> {
    boolean existsByRegistroUuid(String registroUuid);
    List<UsoAplicacion> findAllByFamilia_IdAndDispositivo_IdAndInicioEnGreaterThanEqualAndInicioEnLessThan(
            Long familiaId, Long dispositivoId, Instant desde, Instant hasta);
    List<UsoAplicacion> findAllByFamilia_IdAndInicioEnGreaterThanEqualAndInicioEnLessThanAndActivoTrue(
            Long familiaId, Instant desde, Instant hasta);
    List<UsoAplicacion> findAllByFamilia_IdAndActivoTrue(Long familiaId);

    @Query("""
            select u.aplicacion.id, coalesce(sum(u.duracionSegundos), 0), max(u.finEn)
            from UsoAplicacion u
            where u.familia.id = :familiaId and u.activo = true
            group by u.aplicacion.id
            """)
    List<Object[]> resumirUsoPorAplicacion(@Param("familiaId") Long familiaId);

    @Query("""
            select coalesce(sum(u.duracionSegundos), 0)
            from UsoAplicacion u
            where u.familia.id = :familiaId
              and u.inicioEn >= :desde and u.inicioEn < :hasta
              and u.activo = true
            """)
    long sumarDuracionPorFamiliaYPeriodo(@Param("familiaId") Long familiaId,
                                         @Param("desde") Instant desde,
                                         @Param("hasta") Instant hasta);

    @Query("""
            select coalesce(sum(u.duracionSegundos), 0)
            from UsoAplicacion u
            where u.familia.id = :familiaId
              and u.dispositivo.id = :dispositivoId
              and u.inicioEn >= :desde and u.inicioEn < :hasta
              and u.activo = true
            """)
    long sumarDuracionPorDispositivoYPeriodo(@Param("familiaId") Long familiaId,
                                             @Param("dispositivoId") Long dispositivoId,
                                             @Param("desde") Instant desde,
                                             @Param("hasta") Instant hasta);

    @Query("""
            select coalesce(sum(u.duracionSegundos), 0)
            from UsoAplicacion u
            where u.familia.id = :familiaId and u.menor.id = :menorId
              and u.aplicacion.id = :aplicacionId
              and u.inicioEn >= :desde and u.inicioEn < :hasta and u.activo = true
            """)
    long sumarDuracionPorAplicacionYPeriodo(@Param("familiaId") Long familiaId,
                                             @Param("menorId") Long menorId,
                                             @Param("aplicacionId") Long aplicacionId,
                                             @Param("desde") Instant desde,
                                             @Param("hasta") Instant hasta);
}
