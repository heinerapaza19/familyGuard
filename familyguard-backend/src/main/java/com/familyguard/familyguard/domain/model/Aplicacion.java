package com.familyguard.familyguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@Entity
@Table(name = "aplicaciones")
public class Aplicacion extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "familia_id", nullable = false)
    private Familia familia;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispositivo_id", nullable = false)
    private Dispositivo dispositivo;

    @NotBlank
    @Size(max = 180)
    @Column(nullable = false, length = 180)
    private String nombre;

    @Size(max = 700)
    @Column(name = "ruta_ejecutable", length = 700)
    private String rutaEjecutable;

    @Size(max = 128)
    @Column(name = "hash_ejecutable", length = 128)
    private String hashEjecutable;

    @Size(max = 80)
    @Column(length = 80)
    private String version;

    @Size(max = 160)
    @Column(length = 160)
    private String editor;

    @Column(name = "detectada_en", nullable = false)
    private Instant detectadaEn = Instant.now();

    @Size(max = 32)
    @Column(name = "tipo_instalacion", length = 32)
    private String tipoInstalacion;

    @Column(name = "en_ejecucion", nullable = false)
    private boolean enEjecucion;

    @Column(name = "ventana_activa", nullable = false)
    private boolean ventanaActiva;

    @Column(name = "ultimo_pid")
    private Long ultimoPid;

    @Column(name = "ultima_deteccion")
    private Instant ultimaDeteccion;

    protected Aplicacion() {
    }

    public Aplicacion(Familia familia, Dispositivo dispositivo, String nombre, String rutaEjecutable) {
        this.familia = familia;
        this.dispositivo = dispositivo;
        this.nombre = nombre;
        this.rutaEjecutable = rutaEjecutable;
    }

    public Familia getFamilia() { return familia; }
    public void setFamilia(Familia familia) { this.familia = familia; }
    public Dispositivo getDispositivo() { return dispositivo; }
    public void setDispositivo(Dispositivo dispositivo) { this.dispositivo = dispositivo; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getRutaEjecutable() { return rutaEjecutable; }
    public void setRutaEjecutable(String rutaEjecutable) { this.rutaEjecutable = rutaEjecutable; }
    public String getHashEjecutable() { return hashEjecutable; }
    public void setHashEjecutable(String hashEjecutable) { this.hashEjecutable = hashEjecutable; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getEditor() { return editor; }
    public void setEditor(String editor) { this.editor = editor; }
    public Instant getDetectadaEn() { return detectadaEn; }
    public void setDetectadaEn(Instant detectadaEn) { this.detectadaEn = detectadaEn; }
    public String getTipoInstalacion() { return tipoInstalacion; }
    public void setTipoInstalacion(String tipoInstalacion) { this.tipoInstalacion = tipoInstalacion; }
    public boolean isEnEjecucion() { return enEjecucion; }
    public void setEnEjecucion(boolean enEjecucion) { this.enEjecucion = enEjecucion; }
    public boolean isVentanaActiva() { return ventanaActiva; }
    public void setVentanaActiva(boolean ventanaActiva) { this.ventanaActiva = ventanaActiva; }
    public Long getUltimoPid() { return ultimoPid; }
    public void setUltimoPid(Long ultimoPid) { this.ultimoPid = ultimoPid; }
    public Instant getUltimaDeteccion() { return ultimaDeteccion; }
    public void setUltimaDeteccion(Instant ultimaDeteccion) { this.ultimaDeteccion = ultimaDeteccion; }
}
