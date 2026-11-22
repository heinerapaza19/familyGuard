package com.familyguard.familyguard.domain.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Solicitud del hijo para que se desbloquee una aplicación o un sitio («Justificar»). La foto de evidencia se guarda en
 * la misma tabla, pero se lee y escribe aparte (columnas «foto» y «foto_tipo»), para no cargarla al listar.
 */
@Entity
@Table(name = "solicitudes_desbloqueo")
public class SolicitudDesbloqueo {
    public static final String APP = "APP", WEB = "WEB";
    public static final String PENDIENTE = "PENDIENTE", APROBADA = "APROBADA", RECHAZADA = "RECHAZADA";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "familia_id", nullable = false) private Familia familia;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "menor_id", nullable = false) private Menor menor;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "dispositivo_id", nullable = false) private Dispositivo dispositivo;
    @Column(nullable = false, length = 8) private String tipo;
    @Column(name = "aplicacion_id") private Long aplicacionId;
    @Column(name = "sitio_id") private Long sitioId;
    @Column(nullable = false, length = 255) private String objetivo;
    @Column(nullable = false, length = 500) private String mensaje;
    @Column(name = "foto_tipo", length = 16) private String fotoTipo;
    @Column(nullable = false, length = 12) private String estado = PENDIENTE;
    @Column(length = 300) private String respuesta;
    @Column(name = "minutos_aprobados") private Integer minutosAprobados;
    @Column(name = "permiso_id") private Long permisoId;
    @Column(name = "resuelto_por") private Long resueltoPor;
    @Column(name = "creado_en", nullable = false) private Instant creadoEn;
    @Column(name = "resuelto_en") private Instant resueltoEn;

    protected SolicitudDesbloqueo() { }

    public SolicitudDesbloqueo(Familia familia, Menor menor, Dispositivo dispositivo, String tipo, Long aplicacionId,
                               Long sitioId, String objetivo, String mensaje, Instant creadoEn) {
        this.familia = familia; this.menor = menor; this.dispositivo = dispositivo; this.tipo = tipo;
        this.aplicacionId = aplicacionId; this.sitioId = sitioId; this.objetivo = objetivo; this.mensaje = mensaje;
        this.creadoEn = creadoEn;
    }

    public void aprobar(Long usuarioId, int minutos, Long permisoId, String respuesta, Instant ahora) {
        this.estado = APROBADA; this.resueltoPor = usuarioId; this.minutosAprobados = minutos; this.permisoId = permisoId;
        this.respuesta = respuesta; this.resueltoEn = ahora;
    }

    public void rechazar(Long usuarioId, String respuesta, Instant ahora) {
        this.estado = RECHAZADA; this.resueltoPor = usuarioId; this.respuesta = respuesta; this.resueltoEn = ahora;
    }

    public void conFoto(String tipo) { this.fotoTipo = tipo; }

    public Long getId() { return id; }
    public Familia getFamilia() { return familia; }
    public Menor getMenor() { return menor; }
    public Dispositivo getDispositivo() { return dispositivo; }
    public String getTipo() { return tipo; }
    public Long getAplicacionId() { return aplicacionId; }
    public Long getSitioId() { return sitioId; }
    public String getObjetivo() { return objetivo; }
    public String getMensaje() { return mensaje; }
    public String getFotoTipo() { return fotoTipo; }
    public String getEstado() { return estado; }
    public String getRespuesta() { return respuesta; }
    public Integer getMinutosAprobados() { return minutosAprobados; }
    public Long getPermisoId() { return permisoId; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getResueltoEn() { return resueltoEn; }
}
