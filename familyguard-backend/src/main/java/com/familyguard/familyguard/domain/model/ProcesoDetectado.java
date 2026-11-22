package com.familyguard.familyguard.domain.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "procesos_detectados", uniqueConstraints =
        @UniqueConstraint(name = "uq_procesos_dispositivo_pid", columnNames = {"dispositivo_id", "pid"}))
public class ProcesoDetectado {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "familia_id") private Familia familia;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "dispositivo_id") private Dispositivo dispositivo;
    @Column(nullable = false) private long pid;
    @Column(nullable = false, length = 180) private String nombre;
    @Column(name = "ruta_ejecutable", length = 700) private String rutaEjecutable;
    @Column(name = "en_ejecucion", nullable = false) private boolean enEjecucion = true;
    @Column(name = "ventana_activa", nullable = false) private boolean ventanaActiva;
    @Column(name = "observado_en", nullable = false) private Instant observadoEn;

    protected ProcesoDetectado() { }
    public ProcesoDetectado(Familia familia, Dispositivo dispositivo, long pid) {
        this.familia = familia; this.dispositivo = dispositivo; this.pid = pid;
    }
    public void actualizar(String nombre, String ruta, boolean activa, Instant observado) {
        this.nombre = nombre; this.rutaEjecutable = ruta; this.enEjecucion = true;
        this.ventanaActiva = activa; this.observadoEn = observado;
    }
    public Long getId() { return id; } public long getPid() { return pid; }
    public String getNombre() { return nombre; } public String getRutaEjecutable() { return rutaEjecutable; }
    public boolean isEnEjecucion() { return enEjecucion; } public boolean isVentanaActiva() { return ventanaActiva; }
    public Instant getObservadoEn() { return observadoEn; }
    public void detener(Instant observado) { enEjecucion = false; ventanaActiva = false; observadoEn = observado; }
}
