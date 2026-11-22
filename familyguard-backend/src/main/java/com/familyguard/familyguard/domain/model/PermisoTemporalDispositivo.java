package com.familyguard.familyguard.domain.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Aplicación de una concesión en un equipo: objetivo concreto, revocación y estado confirmado por el Agente. */
@Entity
@Table(name = "permisos_temporales_dispositivos")
public class PermisoTemporalDispositivo extends BaseEntity {
    public static final String PENDIENTE = "PENDIENTE", APLICADO = "APLICADO", FINALIZADO = "FINALIZADO", ERROR = "ERROR";

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "permiso_id", nullable = false)
    private PermisoTemporal permiso;
    @Column(name = "familia_id", nullable = false) private Long familiaId;
    @Column(name = "menor_id", nullable = false) private Long menorId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "dispositivo_id", nullable = false)
    private Dispositivo dispositivo;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "aplicacion_id")
    private Aplicacion aplicacion;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "sitio_id")
    private SitioBloqueado sitio;
    @Column(name = "revocado_en") private Instant revocadoEn;
    @Column(name = "revocado_por") private Long revocadoPor;
    @Column(name = "estado_agente", nullable = false, length = 16) private String estadoAgente = PENDIENTE;
    @Column(name = "agente_actualizado_en") private Instant agenteActualizadoEn;
    @Column(name = "detalle_agente", length = 500) private String detalleAgente;

    protected PermisoTemporalDispositivo() { }

    public PermisoTemporalDispositivo(PermisoTemporal permiso, Dispositivo dispositivo, Aplicacion aplicacion, SitioBloqueado sitio) {
        this.permiso = permiso; this.familiaId = permiso.getFamilia().getId(); this.menorId = permiso.getMenor().getId();
        this.dispositivo = dispositivo; this.aplicacion = aplicacion; this.sitio = sitio;
    }

    public PermisoTemporal getPermiso() { return permiso; }
    public Long getFamiliaId() { return familiaId; }
    public Long getMenorId() { return menorId; }
    public Dispositivo getDispositivo() { return dispositivo; }
    public Aplicacion getAplicacion() { return aplicacion; }
    public SitioBloqueado getSitio() { return sitio; }
    public Instant getRevocadoEn() { return revocadoEn; }
    public Long getRevocadoPor() { return revocadoPor; }
    public String getEstadoAgente() { return estadoAgente; }
    public Instant getAgenteActualizadoEn() { return agenteActualizadoEn; }
    public String getDetalleAgente() { return detalleAgente; }

    public void revocar(Long usuarioId, Instant ahora) { if (revocadoEn == null) { revocadoEn = ahora; revocadoPor = usuarioId; } }

    /** Estado informado por el Agente; FINALIZADO es definitivo y un evento antiguo nunca lo revierte. */
    public void informarAgente(String estado, String detalle, Instant cuando) {
        if (FINALIZADO.equals(estadoAgente)) return;
        if (agenteActualizadoEn != null && cuando.isBefore(agenteActualizadoEn)) return;
        estadoAgente = estado; detalleAgente = detalle; agenteActualizadoEn = cuando;
    }
}
