package com.familyguard.familyguard.domain.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Autorización remota del padre para pausar la supervisión o desinstalar el Agente de un equipo. */
@Entity
@Table(name = "autorizaciones_dispositivo")
public class AutorizacionDispositivo extends BaseEntity {
    public static final String PAUSA = "PAUSA", DESINSTALACION = "DESINSTALACION";

    @Column(name = "familia_id", nullable = false) private Long familiaId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "dispositivo_id", nullable = false)
    private Dispositivo dispositivo;
    @Column(nullable = false, length = 16) private String tipo;
    @Column(name = "autorizado_por", nullable = false) private Long autorizadoPor;
    @Column(name = "autorizado_en", nullable = false) private Instant autorizadoEn;
    @Column(name = "expira_en", nullable = false) private Instant expiraEn;
    @Column(name = "usada_en") private Instant usadaEn;
    @Column(name = "cancelada_en") private Instant canceladaEn;
    @Column(name = "cancelada_por") private Long canceladaPor;

    protected AutorizacionDispositivo() { }
    public AutorizacionDispositivo(Dispositivo dispositivo, String tipo, Long usuarioId, Instant ahora, Instant expira) {
        this.familiaId = dispositivo.getFamilia().getId(); this.dispositivo = dispositivo; this.tipo = tipo;
        this.autorizadoPor = usuarioId; this.autorizadoEn = ahora; this.expiraEn = expira;
    }
    public Long getFamiliaId() { return familiaId; }
    public Dispositivo getDispositivo() { return dispositivo; }
    public String getTipo() { return tipo; }
    public Instant getAutorizadoEn() { return autorizadoEn; }
    public Instant getExpiraEn() { return expiraEn; }
    public Instant getUsadaEn() { return usadaEn; }
    public Instant getCanceladaEn() { return canceladaEn; }
    public boolean vigente(Instant ahora) { return canceladaEn == null && usadaEn == null && expiraEn.isAfter(ahora); }
    public void cancelar(Long usuarioId, Instant ahora) { if (canceladaEn == null) { canceladaEn = ahora; canceladaPor = usuarioId; } }
    public void usar(Instant ahora) { if (usadaEn == null) usadaEn = ahora; }
}
