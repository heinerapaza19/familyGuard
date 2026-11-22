package com.familyguard.familyguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@Entity
@Table(name = "eventos_auditoria")
public class EventoAuditoria extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "familia_id", nullable = false)
    private Familia familia;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispositivo_id")
    private Dispositivo dispositivo;

    @NotBlank
    @Size(max = 80)
    @Column(name = "tipo_evento", nullable = false, length = 80)
    private String tipoEvento;

    @Size(max = 80)
    @Column(name = "tipo_entidad", length = 80)
    private String tipoEntidad;

    @Column(name = "entidad_id")
    private Long entidadId;

    @NotBlank
    @Size(max = 2000)
    @Column(nullable = false, length = 2000)
    private String descripcion;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResultadoEvento resultado;

    @Size(max = 45)
    @Column(name = "direccion_ip", length = 45)
    private String direccionIp;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant ocurridoEn = Instant.now();

    @Column(name = "evento_uuid", length = 36, unique = true)
    private String eventoUuid;

    protected EventoAuditoria() {
    }

    public EventoAuditoria(Familia familia, String tipoEvento, String descripcion, ResultadoEvento resultado) {
        this.familia = familia;
        this.tipoEvento = tipoEvento;
        this.descripcion = descripcion;
        this.resultado = resultado;
    }

    public Familia getFamilia() { return familia; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public Dispositivo getDispositivo() { return dispositivo; }
    public void setDispositivo(Dispositivo dispositivo) { this.dispositivo = dispositivo; }
    public String getTipoEvento() { return tipoEvento; }
    public String getTipoEntidad() { return tipoEntidad; }
    public void setTipoEntidad(String tipoEntidad) { this.tipoEntidad = tipoEntidad; }
    public Long getEntidadId() { return entidadId; }
    public void setEntidadId(Long entidadId) { this.entidadId = entidadId; }
    public String getDescripcion() { return descripcion; }
    public ResultadoEvento getResultado() { return resultado; }
    public String getDireccionIp() { return direccionIp; }
    public void setDireccionIp(String direccionIp) { this.direccionIp = direccionIp; }
    public Instant getOcurridoEn() { return ocurridoEn; }
    public void setOcurridoEn(Instant ocurridoEn) { this.ocurridoEn = ocurridoEn; }
    public String getEventoUuid() { return eventoUuid; }
    public void setEventoUuid(String eventoUuid) { this.eventoUuid = eventoUuid; }
}
