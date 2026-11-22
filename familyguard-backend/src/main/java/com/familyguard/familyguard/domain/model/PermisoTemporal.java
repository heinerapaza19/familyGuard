package com.familyguard.familyguard.domain.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Concesión de un permiso temporal para un hijo: tipo, ventana absoluta y quién lo autorizó. */
@Entity
@Table(name = "permisos_temporales")
public class PermisoTemporal extends BaseEntity {
    public static final String TIEMPO_EXTRA = "TIEMPO_EXTRA", FUERA_HORARIO = "FUERA_HORARIO",
            DESBLOQUEO_TEMPORAL = "DESBLOQUEO_TEMPORAL", PERMITIR_SITIO = "PERMITIR_SITIO";

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "familia_id", nullable = false)
    private Familia familia;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "menor_id", nullable = false)
    private Menor menor;
    @Column(name = "concesion_uuid", nullable = false, length = 36) private String concesionUuid;
    @Column(name = "huella_solicitud", nullable = false, length = 64) private String huellaSolicitud;
    @Column(nullable = false, length = 24) private String tipo;
    @Column(name = "minutos_extra") private Integer minutosExtra;
    @Column(name = "inicio_en", nullable = false) private Instant inicioEn;
    @Column(name = "expira_en", nullable = false) private Instant expiraEn;
    @Column(name = "otorgado_por", nullable = false) private Long otorgadoPor;
    @Column(length = 200) private String motivo;

    protected PermisoTemporal() { }

    public PermisoTemporal(Familia familia, Menor menor, String concesionUuid, String huella, String tipo,
                           Integer minutosExtra, Instant inicioEn, Instant expiraEn, Long otorgadoPor, String motivo) {
        this.familia = familia; this.menor = menor; this.concesionUuid = concesionUuid; this.huellaSolicitud = huella;
        this.tipo = tipo; this.minutosExtra = minutosExtra; this.inicioEn = inicioEn; this.expiraEn = expiraEn;
        this.otorgadoPor = otorgadoPor; this.motivo = motivo;
    }

    public Familia getFamilia() { return familia; }
    public Menor getMenor() { return menor; }
    public String getConcesionUuid() { return concesionUuid; }
    public String getHuellaSolicitud() { return huellaSolicitud; }
    public String getTipo() { return tipo; }
    public Integer getMinutosExtra() { return minutosExtra; }
    public Instant getInicioEn() { return inicioEn; }
    public Instant getExpiraEn() { return expiraEn; }
    public Long getOtorgadoPor() { return otorgadoPor; }
    public String getMotivo() { return motivo; }
}
