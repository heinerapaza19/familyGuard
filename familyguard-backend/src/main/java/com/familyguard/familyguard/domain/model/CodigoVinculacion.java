package com.familyguard.familyguard.domain.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "codigos_vinculacion")
public class CodigoVinculacion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "familia_id") private Familia familia;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "menor_id") private Menor menor;
    @Column(name = "codigo_hash", nullable = false, length = 64, unique = true) private String codigoHash;
    @Column(name = "expira_en", nullable = false) private Instant expiraEn;
    @Column(name = "usado_en") private Instant usadoEn;
    @Column(name = "creado_en", nullable = false, updatable = false) private Instant creadoEn;
    @Column(name="intentos_fallidos",nullable=false) private int intentosFallidos;
    @Column(name="max_intentos",nullable=false) private int maxIntentos=5;
    @Column(name="bloqueado_en") private Instant bloqueadoEn;

    protected CodigoVinculacion() { }
    public CodigoVinculacion(Familia familia, Menor menor, String codigoHash, Instant expiraEn) {
        this.familia = familia; this.menor = menor; this.codigoHash = codigoHash;
        this.creadoEn = Instant.now(); this.expiraEn = expiraEn;
    }
    public Long getId() { return id; }
    public Familia getFamilia() { return familia; }
    public Menor getMenor() { return menor; }
    public String getCodigoHash() { return codigoHash; }
    public Instant getExpiraEn() { return expiraEn; }
    public Instant getUsadoEn() { return usadoEn; }
    public Instant getCreadoEn() { return creadoEn; }
    public void usar() { usadoEn = Instant.now(); }
    /**
     * Anula el código de forma definitiva (por ejemplo, al generar otro para el mismo hijo).
     * No depende del reloj: en Windows el instante actual puede avanzar a saltos de ~15 ms, por lo que
     * acortar el vencimiento a «creación + 1 ms» dejaba el código válido dentro del mismo salto.
     * El vencimiento se acorta además respetando {@code ck_codigos_expiracion} (expira_en > creado_en).
     */
    public void expirar() {
        Instant ahora = Instant.now();
        if (bloqueadoEn == null) bloqueadoEn = ahora;
        Instant minimo = creadoEn.plusMillis(1);
        Instant nuevo = ahora.isAfter(minimo) ? ahora : minimo;
        if (expiraEn == null || expiraEn.isAfter(nuevo)) expiraEn = nuevo;
    }
    public boolean anulado() { return bloqueadoEn != null; }
    /** Utilizable solo si no se usó, no fue anulado y no ha vencido. */
    public boolean vigente(Instant ahora) { return usadoEn == null && bloqueadoEn == null && expiraEn.isAfter(ahora); }
    public int getIntentosFallidos(){return intentosFallidos;}public int getMaxIntentos(){return maxIntentos;}public Instant getBloqueadoEn(){return bloqueadoEn;}
    public void fallar(){intentosFallidos++;if(intentosFallidos>=maxIntentos)bloqueadoEn=Instant.now();}
}
