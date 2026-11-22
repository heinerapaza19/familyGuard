package com.familyguard.familyguard.domain.model;
import jakarta.persistence.*;import java.time.Instant;
@Entity @Table(name="intentos_vinculacion") public class IntentoVinculacion extends BaseEntity{
 @Column(name="identificador_hash",nullable=false,length=64,unique=true)private String hash;
 @Column(nullable=false)private int intentos;@Column(name="bloqueado_hasta")private Instant bloqueadoHasta;
 protected IntentoVinculacion(){}public IntentoVinculacion(String h){hash=h;}
 public String getHash(){return hash;}public int getIntentos(){return intentos;}public Instant getBloqueadoHasta(){return bloqueadoHasta;}
 public boolean bloqueado(){return bloqueadoHasta!=null&&bloqueadoHasta.isAfter(Instant.now());}
 public void fallar(){fallar(5);}
 /** Registra un fallo; al alcanzar {@code maximo} bloquea durante 15 minutos. */
 public void fallar(int maximo){if(bloqueadoHasta!=null&&bloqueadoHasta.isBefore(Instant.now())){intentos=0;bloqueadoHasta=null;}intentos++;if(intentos>=maximo)bloqueadoHasta=Instant.now().plusSeconds(900);}
}
