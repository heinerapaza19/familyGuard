package com.familyguard.familyguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "sesiones_autenticacion", uniqueConstraints =
        @UniqueConstraint(name = "uq_sesiones_token_hash", columnNames = "token_hash"))
public class SesionAutenticacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "familia_id", nullable = false)
    private Familia familia;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "creada_en", nullable = false, updatable = false)
    private Instant creadaEn;

    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    @Column(name = "revocada_en")
    private Instant revocadaEn;

    @Column(name = "ultima_actividad_en", nullable = false)
    private Instant ultimaActividadEn;

    protected SesionAutenticacion() {
    }

    public SesionAutenticacion(Familia familia, Usuario usuario, String tokenHash, Instant expiraEn) {
        this.familia = familia;
        this.usuario = usuario;
        this.tokenHash = tokenHash;
        this.creadaEn = Instant.now();
        this.ultimaActividadEn = this.creadaEn;
        this.expiraEn = expiraEn;
    }

    public Long getId() { return id; }
    public Familia getFamilia() { return familia; }
    public Usuario getUsuario() { return usuario; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiraEn() { return expiraEn; }
    public void expirar() { this.expiraEn = Instant.now().minusMillis(1); }
    public Instant getRevocadaEn() { return revocadaEn; }
    public void revocar() { this.revocadaEn = Instant.now(); }
    public Instant getUltimaActividadEn() { return ultimaActividadEn; }
    public void registrarActividad() { this.ultimaActividadEn = Instant.now(); }
}
