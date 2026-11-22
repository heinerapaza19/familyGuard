package com.familyguard.familyguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Resultado inicial de una operación masiva en un equipo y la revisión que debe alcanzar. */
@Entity
@Table(name = "operaciones_masivas_dispositivos")
public class OperacionMasivaDispositivo extends BaseEntity {
    public static final String PROGRAMADA = "PROGRAMADA", SIN_CAMBIOS = "SIN_CAMBIOS",
            APLICACION_NO_REGISTRADA = "APLICACION_NO_REGISTRADA", APLICACION_AMBIGUA = "APLICACION_AMBIGUA",
            CANCELADA = "CANCELADA";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operacion_id", nullable = false)
    private OperacionMasiva operacion;
    @Column(name = "familia_id", nullable = false) private Long familiaId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispositivo_id", nullable = false)
    private Dispositivo dispositivo;
    @Column(name = "sitio_id") private Long sitioId;
    @Column(name = "aplicacion_id") private Long aplicacionId;
    @Column(name = "revision_objetivo") private Long revisionObjetivo;
    @Column(nullable = false, length = 32) private String resultado;
    @Column(length = 500) private String detalle;

    protected OperacionMasivaDispositivo() { }

    public OperacionMasivaDispositivo(OperacionMasiva operacion, Dispositivo dispositivo, Long sitioId, Long aplicacionId,
                                      Long revisionObjetivo, String resultado, String detalle) {
        this.operacion = operacion; this.familiaId = dispositivo.getFamilia().getId(); this.dispositivo = dispositivo;
        this.sitioId = sitioId; this.aplicacionId = aplicacionId; this.revisionObjetivo = revisionObjetivo;
        this.resultado = resultado; this.detalle = detalle;
    }

    public OperacionMasiva getOperacion() { return operacion; }
    public Long getFamiliaId() { return familiaId; }
    public Dispositivo getDispositivo() { return dispositivo; }
    public Long getSitioId() { return sitioId; }
    public Long getAplicacionId() { return aplicacionId; }
    public Long getRevisionObjetivo() { return revisionObjetivo; }
    public String getResultado() { return resultado; }
    public String getDetalle() { return detalle; }
    public void cancelar(String detalle) { this.resultado = CANCELADA; this.detalle = detalle; }
}
