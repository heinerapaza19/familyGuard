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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

@Entity
@Table(name = "limites_tiempo")
public class LimiteTiempo extends BaseEntity {

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
    @JoinColumn(name = "aplicacion_id", nullable = false)
    private Aplicacion aplicacion;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PeriodoLimite periodo;

    @Min(1)
    @Column(name = "minutos_permitidos", nullable = false)
    private int minutosPermitidos;

    @Column(name = "vigente_desde")
    private LocalDate vigenteDesde;

    @Column(name = "vigente_hasta")
    private LocalDate vigenteHasta;

    @Column(name = "bloqueo_total", nullable = false)
    private boolean bloqueoTotal;

    @Column(name = "advertencia_minutos", nullable = false)
    private int advertenciaMinutos = 5;

    @Column(nullable = false)
    private long revision = 1;

    protected LimiteTiempo() {
    }

    public LimiteTiempo(Familia familia, Menor menor, Aplicacion aplicacion,
                        PeriodoLimite periodo, int minutosPermitidos) {
        this.familia = familia;
        this.menor = menor;
        this.aplicacion = aplicacion;
        this.periodo = periodo;
        this.minutosPermitidos = minutosPermitidos;
    }

    @AssertTrue(message = "vigenteHasta no puede ser anterior a vigenteDesde")
    public boolean isVigenciaValida() {
        return vigenteDesde == null || vigenteHasta == null || !vigenteHasta.isBefore(vigenteDesde);
    }

    public Familia getFamilia() { return familia; }
    public Menor getMenor() { return menor; }
    public Aplicacion getAplicacion() { return aplicacion; }
    public PeriodoLimite getPeriodo() { return periodo; }
    public int getMinutosPermitidos() { return minutosPermitidos; }
    public void setMinutosPermitidos(int minutosPermitidos) { this.minutosPermitidos = minutosPermitidos; }
    public LocalDate getVigenteDesde() { return vigenteDesde; }
    public void setVigenteDesde(LocalDate vigenteDesde) { this.vigenteDesde = vigenteDesde; }
    public LocalDate getVigenteHasta() { return vigenteHasta; }
    public void setVigenteHasta(LocalDate vigenteHasta) { this.vigenteHasta = vigenteHasta; }
    public boolean isBloqueoTotal() { return bloqueoTotal; }
    public void setBloqueoTotal(boolean bloqueoTotal) { this.bloqueoTotal = bloqueoTotal; }
    public int getAdvertenciaMinutos() { return advertenciaMinutos; }
    public void setAdvertenciaMinutos(int advertenciaMinutos) { this.advertenciaMinutos = advertenciaMinutos; }
    public long getRevision() { return revision; }
    public void incrementarRevision() { revision++; }
}
