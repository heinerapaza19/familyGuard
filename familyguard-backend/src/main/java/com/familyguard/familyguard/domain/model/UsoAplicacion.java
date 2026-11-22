package com.familyguard.familyguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "usos_aplicacion")
public class UsoAplicacion extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "familia_id", nullable = false)
    private Familia familia;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menor_id", nullable = false)
    private Menor menor;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispositivo_id", nullable = false)
    private Dispositivo dispositivo;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aplicacion_id", nullable = false)
    private Aplicacion aplicacion;

    @NotNull
    @Column(name = "inicio_en", nullable = false)
    private Instant inicioEn;

    @NotNull
    @Column(name = "fin_en", nullable = false)
    private Instant finEn;

    @PositiveOrZero
    @Column(name = "duracion_segundos", nullable = false)
    private long duracionSegundos;

    @Column(name = "registro_uuid", length = 36, unique = true)
    private String registroUuid;

    @Column(name = "fecha_local")
    private LocalDate fechaLocal;

    @Column(name = "zona_horaria", length = 64)
    private String zonaHoraria;

    protected UsoAplicacion() {
    }

    public UsoAplicacion(Familia familia, Menor menor, Dispositivo dispositivo, Aplicacion aplicacion,
                         Instant inicioEn, Instant finEn) {
        this.familia = familia;
        this.menor = menor;
        this.dispositivo = dispositivo;
        this.aplicacion = aplicacion;
        this.inicioEn = inicioEn;
        this.finEn = finEn;
        this.duracionSegundos = Math.max(0, java.time.Duration.between(inicioEn, finEn).getSeconds());
    }

    public UsoAplicacion(Familia familia, Menor menor, Dispositivo dispositivo, Aplicacion aplicacion,
                         Instant inicioEn, Instant finEn, String registroUuid,
                         LocalDate fechaLocal, String zonaHoraria) {
        this(familia, menor, dispositivo, aplicacion, inicioEn, finEn);
        this.registroUuid = registroUuid;
        this.fechaLocal = fechaLocal;
        this.zonaHoraria = zonaHoraria;
    }

    @AssertTrue(message = "finEn debe ser posterior a inicioEn")
    public boolean isIntervaloValido() {
        return inicioEn == null || finEn == null || finEn.isAfter(inicioEn);
    }

    public Familia getFamilia() { return familia; }
    public Menor getMenor() { return menor; }
    public Dispositivo getDispositivo() { return dispositivo; }
    public Aplicacion getAplicacion() { return aplicacion; }
    public Instant getInicioEn() { return inicioEn; }
    public Instant getFinEn() { return finEn; }
    public long getDuracionSegundos() { return duracionSegundos; }
    public void setDuracionSegundos(long duracionSegundos) { this.duracionSegundos = duracionSegundos; }
    public String getRegistroUuid() { return registroUuid; }
    public LocalDate getFechaLocal() { return fechaLocal; }
    public String getZonaHoraria() { return zonaHoraria; }
}
