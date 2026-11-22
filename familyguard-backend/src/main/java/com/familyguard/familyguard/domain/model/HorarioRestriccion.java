package com.familyguard.familyguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalTime;

@Entity
@Table(name = "horarios_restriccion")
public class HorarioRestriccion extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "familia_id", nullable = false)
    private Familia familia;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menor_id", nullable = false)
    private Menor menor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aplicacion_id")
    private Aplicacion aplicacion;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "dia_semana", nullable = false, length = 12)
    private DayOfWeek diaSemana;

    @NotNull
    @Column(name = "hora_inicio", nullable = false)
    private LocalTime horaInicio;

    @NotNull
    @Column(name = "hora_fin", nullable = false)
    private LocalTime horaFin;

    @Column(name = "cruza_medianoche", nullable = false)
    private boolean cruzaMedianoche;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_regla", nullable = false, length = 16)
    private TipoReglaHorario tipoRegla = TipoReglaHorario.BLOQUEAR;

    @Column(nullable = false)
    private long revision = 1;

    protected HorarioRestriccion() {
    }

    public HorarioRestriccion(Familia familia, Menor menor, DayOfWeek diaSemana,
                              LocalTime horaInicio, LocalTime horaFin) {
        this.familia = familia;
        this.menor = menor;
        this.diaSemana = diaSemana;
        this.horaInicio = horaInicio;
        this.horaFin = horaFin;
        this.cruzaMedianoche = horaFin.isBefore(horaInicio);
    }

    public HorarioRestriccion(Familia familia, Menor menor, Aplicacion aplicacion,
                              DayOfWeek diaSemana, LocalTime horaInicio, LocalTime horaFin,
                              TipoReglaHorario tipoRegla) {
        this(familia, menor, diaSemana, horaInicio, horaFin);
        this.aplicacion = aplicacion;
        this.tipoRegla = tipoRegla;
    }

    @AssertTrue(message = "el horario debe tener una duracion mayor a cero")
    public boolean isHorarioValido() {
        return horaInicio == null || horaFin == null || !horaInicio.equals(horaFin);
    }

    public Familia getFamilia() { return familia; }
    public Menor getMenor() { return menor; }
    public DayOfWeek getDiaSemana() { return diaSemana; }
    public LocalTime getHoraInicio() { return horaInicio; }
    public LocalTime getHoraFin() { return horaFin; }
    public boolean isCruzaMedianoche() { return cruzaMedianoche; }
    public Aplicacion getAplicacion() { return aplicacion; }
    public TipoReglaHorario getTipoRegla() { return tipoRegla; }
    public long getRevision() { return revision; }
    public void setTipoRegla(TipoReglaHorario tipoRegla) { this.tipoRegla = tipoRegla; revision++; }
}
