package com.familyguard.familyguard.domain.model;
import jakarta.persistence.*;import java.time.*;
@Entity @Table(name="horarios_sitios")
public class HorarioSitio extends BaseEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="sitio_id") private SitioBloqueado sitio;
 @Enumerated(EnumType.STRING) @Column(name="dia_semana",nullable=false,length=12) private DayOfWeek dia;
 @Column(name="hora_inicio",nullable=false) private LocalTime inicio;
 @Column(name="hora_fin",nullable=false) private LocalTime fin;
 @Column(name="cruza_medianoche",nullable=false) private boolean cruzaMedianoche;
 @Enumerated(EnumType.STRING) @Column(name="tipo_regla",nullable=false,length=16) private TipoReglaSitio tipo;
 protected HorarioSitio(){}
 public HorarioSitio(SitioBloqueado s,DayOfWeek d,LocalTime i,LocalTime f,TipoReglaSitio t){sitio=s;dia=d;inicio=i;fin=f;tipo=t;cruzaMedianoche=!f.isAfter(i);}
 public SitioBloqueado getSitio(){return sitio;}public DayOfWeek getDia(){return dia;}public LocalTime getInicio(){return inicio;}public LocalTime getFin(){return fin;}public boolean isCruzaMedianoche(){return cruzaMedianoche;}public TipoReglaSitio getTipo(){return tipo;}
}
