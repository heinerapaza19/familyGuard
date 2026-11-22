package com.familyguard.familyguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "familias")
public class Familia extends BaseEntity {

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String nombre;

    @Size(max = 255)
    @Column(length = 255)
    private String descripcion;

    @NotBlank
    @Size(max = 64)
    @Column(name = "zona_horaria", nullable = false, length = 64)
    private String zonaHoraria = "America/Lima";

    protected Familia() {
    }

    public Familia(String nombre, String zonaHoraria) {
        this.nombre = nombre;
        this.zonaHoraria = zonaHoraria;
    }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public String getZonaHoraria() { return zonaHoraria; }
    public void setZonaHoraria(String zonaHoraria) { this.zonaHoraria = zonaHoraria; }
}
