package com.familyguard.familyguard.domain.model;
import jakarta.persistence.*;import java.time.Instant;
@Entity @Table(name="confirmaciones_sincronizacion") public class ConfirmacionSincronizacion extends BaseEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="familia_id")private Familia familia;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="dispositivo_id")private Dispositivo dispositivo;
 @Column(name="confirmacion_uuid",nullable=false,length=36,unique=true)private String uuid;
 @Column(nullable=false)private long revision;@Column(nullable=false,length=16)private String estado;
 @Column(length=500)private String detalle;@Column(name="confirmado_en",nullable=false)private Instant confirmadoEn;
 protected ConfirmacionSincronizacion(){}
 public ConfirmacionSincronizacion(Familia f,Dispositivo d,String u,long r,String e,String detail,Instant at){familia=f;dispositivo=d;uuid=u;revision=r;estado=e;detalle=detail;confirmadoEn=at;}
 public String getUuid(){return uuid;}public long getRevision(){return revision;}public String getEstado(){return estado;}public String getDetalle(){return detalle;}public Instant getConfirmadoEn(){return confirmadoEn;}
}
