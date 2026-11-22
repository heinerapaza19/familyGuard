package com.familyguard.familyguard.domain.model;

import jakarta.persistence.*;

/** PIN parental de una familia: cifrado reversible solo en el servidor y verificador PBKDF2 para los Agentes. */
@Entity
@Table(name = "pines_parentales")
public class PinParental extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "familia_id", nullable = false)
    private Familia familia;
    @Column(name = "pin_cifrado", nullable = false, length = 512) private String pinCifrado;
    @Column(nullable = false, length = 255) private String verificador;
    @Column(nullable = false) private int version;
    @Column(name = "actualizado_por", nullable = false) private Long actualizadoPor;

    protected PinParental() { }
    public PinParental(Familia familia) { this.familia = familia; }

    public Familia getFamilia() { return familia; }
    public String getPinCifrado() { return pinCifrado; }
    public String getVerificador() { return verificador; }
    public int getVersion() { return version; }
    public void cambiar(String cifrado, String verificador, Long usuarioId) {
        this.pinCifrado = cifrado; this.verificador = verificador; this.actualizadoPor = usuarioId; this.version++;
    }
}
