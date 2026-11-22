package com.familyguard.familyguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** Operación masiva idempotente (Etapa 4): el identificador se repite en los reintentos. */
@Entity
@Table(name = "operaciones_masivas")
public class OperacionMasiva extends BaseEntity {
    public static final String REGISTRADA = "REGISTRADA", CANCELADA = "CANCELADA";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "familia_id", nullable = false)
    private Familia familia;
    @Column(name = "usuario_id", nullable = false) private Long usuarioId;
    @Column(name = "operacion_uuid", nullable = false, length = 36) private String operacionUuid;
    @Column(name = "huella_solicitud", nullable = false, length = 64) private String huellaSolicitud;
    @Column(nullable = false, length = 24) private String tipo;
    @Column(nullable = false, length = 253) private String objetivo;
    @Column(length = 1000) private String parametros;
    @Column(name = "total_dispositivos", nullable = false) private int totalDispositivos;
    @Column(nullable = false, length = 16) private String estado = REGISTRADA;
    @Column(name = "cancelada_en") private Instant canceladaEn;

    protected OperacionMasiva() { }

    public OperacionMasiva(Familia familia, Long usuarioId, String operacionUuid, String huellaSolicitud, String tipo,
                           String objetivo, String parametros, int totalDispositivos) {
        this.familia = familia; this.usuarioId = usuarioId; this.operacionUuid = operacionUuid;
        this.huellaSolicitud = huellaSolicitud; this.tipo = tipo; this.objetivo = objetivo;
        this.parametros = parametros; this.totalDispositivos = totalDispositivos;
    }

    public Familia getFamilia() { return familia; }
    public Long getUsuarioId() { return usuarioId; }
    public String getOperacionUuid() { return operacionUuid; }
    public String getHuellaSolicitud() { return huellaSolicitud; }
    public String getTipo() { return tipo; }
    public String getObjetivo() { return objetivo; }
    public String getParametros() { return parametros; }
    public int getTotalDispositivos() { return totalDispositivos; }
    public String getEstado() { return estado; }
    public Instant getCanceladaEn() { return canceladaEn; }
    public void cancelar(Instant ahora) { estado = CANCELADA; canceladaEn = ahora; }
}
