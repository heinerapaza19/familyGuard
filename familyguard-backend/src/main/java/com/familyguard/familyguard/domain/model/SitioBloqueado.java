package com.familyguard.familyguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "sitios_bloqueados")
public class SitioBloqueado extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "familia_id", nullable = false)
    private Familia familia;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menor_id", nullable = false)
    private Menor menor;

    @NotBlank
    @Size(max = 253)
    @Pattern(regexp = "^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    @Column(nullable = false, length = 253)
    private String dominio;

    @Column(name = "incluir_subdominios", nullable = false)
    private boolean incluirSubdominios = true;

    @Size(max = 255)
    @Column(length = 255)
    private String motivo;

    @Enumerated(EnumType.STRING) @Column(nullable=false,length=32)
    private CategoriaSitio categoria=CategoriaSitio.PERSONALIZADO;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16)
    private TipoReglaSitio modo=TipoReglaSitio.BLOQUEAR;
    @Column(nullable=false) private boolean permanente=true;
    @Column(name="vigente_desde") private Instant vigenteDesde;
    @Column(name="vigente_hasta") private Instant vigenteHasta;
    @Column(nullable=false) private long revision=1;
    @ManyToMany
    @JoinTable(name="sitios_dispositivos",joinColumns=@JoinColumn(name="sitio_id"),inverseJoinColumns={
            @JoinColumn(name="dispositivo_id",referencedColumnName="id"),@JoinColumn(name="familia_id",referencedColumnName="familia_id")})
    private Set<Dispositivo> dispositivos=new LinkedHashSet<>();

    protected SitioBloqueado() {
    }

    public SitioBloqueado(Familia familia, Menor menor, String dominio) {
        this.familia = familia;
        this.menor = menor;
        this.dominio = normalizar(dominio);
    }

    private static String normalizar(String dominio) {
        return dominio.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceFirst("^https?://", "").replaceFirst("/.*$", "");
    }

    public Familia getFamilia() { return familia; }
    public Menor getMenor() { return menor; }
    public String getDominio() { return dominio; }
    public void setDominio(String dominio) { this.dominio = normalizar(dominio); }
    public boolean isIncluirSubdominios() { return incluirSubdominios; }
    public void setIncluirSubdominios(boolean incluirSubdominios) { this.incluirSubdominios = incluirSubdominios; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public CategoriaSitio getCategoria(){return categoria;} public void setCategoria(CategoriaSitio v){categoria=v;}
    public TipoReglaSitio getModo(){return modo;} public void setModo(TipoReglaSitio v){modo=v;}
    public boolean isPermanente(){return permanente;} public void setPermanente(boolean v){permanente=v;}
    public Instant getVigenteDesde(){return vigenteDesde;} public void setVigenteDesde(Instant v){vigenteDesde=v;}
    public Instant getVigenteHasta(){return vigenteHasta;} public void setVigenteHasta(Instant v){vigenteHasta=v;}
    public long getRevision(){return revision;} public void incrementarRevision(){revision++;}
    public Set<Dispositivo> getDispositivos(){return dispositivos;}
}
