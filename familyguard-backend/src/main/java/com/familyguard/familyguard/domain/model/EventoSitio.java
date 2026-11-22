package com.familyguard.familyguard.domain.model;
import jakarta.persistence.*;import java.time.Instant;
@Entity @Table(name="eventos_sitios")
public class EventoSitio extends BaseEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="familia_id") private Familia familia;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="sitio_id") private SitioBloqueado sitio;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="dispositivo_id") private Dispositivo dispositivo;
 @Column(name="evento_uuid",nullable=false,length=36,unique=true) private String uuid;
 @Column(nullable=false,length=253) private String dominio;@Column(nullable=false,length=32) private String tipo;
 @Column(length=500) private String detalle;@Column(name="ocurrido_en",nullable=false) private Instant ocurridoEn;
 @Column(nullable=false) private boolean exitoso;
 protected EventoSitio(){}
 public EventoSitio(Familia f,SitioBloqueado s,Dispositivo d,String u,String domain,String type,String detail,Instant at,boolean ok){familia=f;sitio=s;dispositivo=d;uuid=u;dominio=domain;tipo=type;detalle=detail;ocurridoEn=at;exitoso=ok;}
 public String getUuid(){return uuid;}public SitioBloqueado getSitio(){return sitio;}public Dispositivo getDispositivo(){return dispositivo;}public String getDominio(){return dominio;}public String getTipo(){return tipo;}public String getDetalle(){return detalle;}public Instant getOcurridoEn(){return ocurridoEn;}public boolean isExitoso(){return exitoso;}
}
