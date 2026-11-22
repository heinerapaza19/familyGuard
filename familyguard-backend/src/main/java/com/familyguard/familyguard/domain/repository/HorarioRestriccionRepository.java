package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.HorarioRestriccion;
import java.time.DayOfWeek;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HorarioRestriccionRepository extends JpaRepository<HorarioRestriccion, Long> {
    List<HorarioRestriccion> findAllByFamilia_IdAndMenor_IdAndActivoTrueOrderByDiaSemanaAscHoraInicioAsc(
            Long familiaId, Long menorId);
    List<HorarioRestriccion> findAllByFamilia_IdAndMenor_IdAndDiaSemanaAndActivoTrue(
            Long familiaId, Long menorId, DayOfWeek diaSemana);
    long countByFamilia_IdAndActivoTrue(Long familiaId);
    List<HorarioRestriccion> findAllByFamilia_IdAndMenor_IdAndAplicacion_IdAndActivoTrueOrderByDiaSemanaAscHoraInicioAsc(
            Long familiaId, Long menorId, Long aplicacionId);
    List<HorarioRestriccion> findAllByFamilia_IdAndMenor_IdAndAplicacion_Id(
            Long familiaId, Long menorId, Long aplicacionId);
}
