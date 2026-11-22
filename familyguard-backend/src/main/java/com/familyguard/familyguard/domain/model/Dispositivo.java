package com.familyguard.familyguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@Entity
@EntityListeners(com.familyguard.familyguard.realtime.DispositivoCambiosListener.class)
@Table(name = "dispositivos", uniqueConstraints = {
        @UniqueConstraint(name = "uq_dispositivos_identificador", columnNames = "identificador")
})
public class Dispositivo extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "familia_id", nullable = false)
    private Familia familia;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menor_id")
    private Menor menor;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String nombre;

    @NotBlank
    @Size(max = 64)
    @Column(nullable = false, length = 64)
    private String identificador;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TipoDispositivo tipo;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private EstadoDispositivo estado = EstadoDispositivo.PENDIENTE;

    @Size(max = 120)
    @Column(name = "sistema_operativo", length = 120)
    private String sistemaOperativo;

    @Size(max = 80)
    @Column(name = "version_sistema", length = 80)
    private String versionSistema;

    @Column(name = "ultima_conexion")
    private Instant ultimaConexion;

    @Size(max = 64)
    @Column(name = "agente_token_hash", length = 64, unique = true)
    private String agenteTokenHash;

    @Column(name = "agente_revocado", nullable = false)
    private boolean agenteRevocado;

    @Column(name = "ultima_sincronizacion")
    private Instant ultimaSincronizacion;
    @Size(max=160) @Column(name="usuario_local",length=160) private String usuarioLocal;
    @Size(max=40) @Column(name="version_agente",length=40) private String versionAgente;
    @Column(name="ultimo_heartbeat") private Instant ultimoHeartbeat;
    @Column(name="intervalo_heartbeat_segundos",nullable=false) private int intervaloHeartbeatSegundos=30;
    @Size(max=500) @Column(name="ultimo_error_comunicacion",length=500) private String ultimoErrorComunicacion;
    @Column(name="revision_deseada",nullable=false) private long revisionDeseada;
    /** V9: revisión de políticas entregada al Agente (descarga), distinta de la aplicada. */
    @Column(name="revision_recibida",nullable=false) private long revisionRecibida;
    @Column(name="politica_recibida_en") private Instant politicaRecibidaEn;
    @Column(name="revision_aplicada",nullable=false) private long revisionAplicada;
    @Column(name="desvinculado_en") private Instant desvinculadoEn;
    @Column(name="version_credencial",nullable=false) private int versionCredencial=1;

    protected Dispositivo() {
    }

    public Dispositivo(Familia familia, String nombre, String identificador, TipoDispositivo tipo) {
        this.familia = familia;
        this.nombre = nombre;
        this.identificador = identificador;
        this.tipo = tipo;
    }

    public Familia getFamilia() { return familia; }
    public void setFamilia(Familia familia) { this.familia = familia; }
    public Menor getMenor() { return menor; }
    public void setMenor(Menor menor) { this.menor = menor; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getIdentificador() { return identificador; }
    public void setIdentificador(String identificador) { this.identificador = identificador; }
    public TipoDispositivo getTipo() { return tipo; }
    public void setTipo(TipoDispositivo tipo) { this.tipo = tipo; }
    public EstadoDispositivo getEstado() { return estado; }
    public void setEstado(EstadoDispositivo estado) { this.estado = estado; }
    public String getSistemaOperativo() { return sistemaOperativo; }
    public void setSistemaOperativo(String sistemaOperativo) { this.sistemaOperativo = sistemaOperativo; }
    public String getVersionSistema() { return versionSistema; }
    public void setVersionSistema(String versionSistema) { this.versionSistema = versionSistema; }
    public Instant getUltimaConexion() { return ultimaConexion; }
    public void setUltimaConexion(Instant ultimaConexion) { this.ultimaConexion = ultimaConexion; }
    public String getAgenteTokenHash() { return agenteTokenHash; }
    public void setAgenteTokenHash(String agenteTokenHash) { this.agenteTokenHash = agenteTokenHash; }
    public boolean isAgenteRevocado() { return agenteRevocado; }
    public void setAgenteRevocado(boolean agenteRevocado) { this.agenteRevocado = agenteRevocado; }
    public Instant getUltimaSincronizacion() { return ultimaSincronizacion; }
    public void setUltimaSincronizacion(Instant ultimaSincronizacion) { this.ultimaSincronizacion = ultimaSincronizacion; }
    public String getUsuarioLocal(){return usuarioLocal;}public void setUsuarioLocal(String v){usuarioLocal=v;}
    public String getVersionAgente(){return versionAgente;}public void setVersionAgente(String v){versionAgente=v;}
    public Instant getUltimoHeartbeat(){return ultimoHeartbeat;}public void setUltimoHeartbeat(Instant v){ultimoHeartbeat=v;}
    public int getIntervaloHeartbeatSegundos(){return intervaloHeartbeatSegundos;}public void setIntervaloHeartbeatSegundos(int v){intervaloHeartbeatSegundos=v;}
    public String getUltimoErrorComunicacion(){return ultimoErrorComunicacion;}public void setUltimoErrorComunicacion(String v){ultimoErrorComunicacion=v;}
    public long getRevisionDeseada(){return revisionDeseada;}public void incrementarRevisionDeseada(){revisionDeseada++;}public void setRevisionDeseada(long v){revisionDeseada=v;}
    public long getRevisionRecibida(){return revisionRecibida;}
    public Instant getPoliticaRecibidaEn(){return politicaRecibidaEn;}
    /** El Agente descargó las políticas vigentes: nunca retrocede. Recibir no equivale a aplicar. */
    public void marcarPoliticaRecibida(Instant ahora){if(revisionDeseada>revisionRecibida)revisionRecibida=revisionDeseada;politicaRecibidaEn=ahora;}
    public long getRevisionAplicada(){return revisionAplicada;}public void setRevisionAplicada(long v){revisionAplicada=v;}
    public Instant getDesvinculadoEn(){return desvinculadoEn;}public void setDesvinculadoEn(Instant v){desvinculadoEn=v;}
    public int getVersionCredencial(){return versionCredencial;}

    /**
     * Emite una nueva credencial para un dispositivo revocado, conservando su identidad,
     * familia, perfil, historial y revisiones. El hash anterior deja de existir.
     */
    public void renovarCredencial(String nuevoTokenHash, Instant ahora) {
        this.agenteTokenHash = nuevoTokenHash;
        this.agenteRevocado = false;
        this.estado = EstadoDispositivo.AUTORIZADO;
        this.desvinculadoEn = null;
        this.ultimoErrorComunicacion = null;
        this.versionCredencial++;
        this.ultimaConexion = ahora;
        this.ultimaSincronizacion = ahora;
        // El heartbeat de la credencial anterior no prueba que el nuevo agente esté activo.
        this.ultimoHeartbeat = null;
    }
}
