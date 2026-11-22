package com.familyguard.familyguard.service;

import com.familyguard.familyguard.api.dto.MenorRequest;
import com.familyguard.familyguard.api.dto.MenorResponse;
import com.familyguard.familyguard.domain.model.Menor;
import com.familyguard.familyguard.domain.model.RolUsuario;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.MenorRepository;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.exception.ForbiddenException;
import com.familyguard.familyguard.service.exception.ResourceNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenorService {

    private final MenorRepository menores;
    private final FamiliaRepository familias;

    public MenorService(MenorRepository menores, FamiliaRepository familias) {
        this.menores = menores;
        this.familias = familias;
    }

    @Transactional
    public MenorResponse crear(UsuarioPrincipal principal, MenorRequest request) {
        validarRolFamiliar(principal);
        var familia = familias.getReferenceById(principal.familiaId());
        var menor = new Menor(familia, request.nombre().strip());
        menor.setFechaNacimiento(request.fechaNacimiento());
        menor.setNombrePerfil(limpiar(request.nombrePerfil()));
        return respuesta(menores.save(menor));
    }

    @Transactional(readOnly = true)
    public List<MenorResponse> listar(UsuarioPrincipal principal) {
        validarRolFamiliar(principal);
        return menores.findAllByFamilia_IdOrderByNombreAsc(principal.familiaId()).stream()
                .map(MenorService::respuesta).toList();
    }

    @Transactional(readOnly = true)
    public MenorResponse detalle(UsuarioPrincipal principal, Long id) {
        validarRolFamiliar(principal);
        return respuesta(buscarIncluyendoInactivos(principal, id));
    }

    @Transactional
    public MenorResponse actualizar(UsuarioPrincipal principal, Long id, MenorRequest request) {
        validarRolFamiliar(principal);
        var menor = buscarIncluyendoInactivos(principal, id);
        menor.setNombre(request.nombre().strip());
        menor.setFechaNacimiento(request.fechaNacimiento());
        menor.setNombrePerfil(limpiar(request.nombrePerfil()));
        return respuesta(menor);
    }

    @Transactional
    public void desactivar(UsuarioPrincipal principal, Long id) {
        validarRolFamiliar(principal);
        buscar(principal, id).setActivo(false);
    }

    @Transactional
    public MenorResponse cambiarEstado(UsuarioPrincipal principal, Long id, boolean activo) {
        validarRolFamiliar(principal);
        Menor menor = buscarIncluyendoInactivos(principal, id);
        menor.setActivo(activo);
        return respuesta(menor);
    }

    private Menor buscar(UsuarioPrincipal principal, Long id) {
        return menores.findByIdAndFamilia_IdAndActivoTrue(id, principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Perfil no encontrado"));
    }

    private Menor buscarIncluyendoInactivos(UsuarioPrincipal principal, Long id) {
        return menores.findByIdAndFamilia_Id(id, principal.familiaId())
                .orElseThrow(() -> new ResourceNotFoundException("Perfil no encontrado"));
    }

    private static void validarRolFamiliar(UsuarioPrincipal principal) {
        if (principal.rol() != RolUsuario.PADRE && principal.rol() != RolUsuario.TUTOR) {
            throw new ForbiddenException("El rol no administra perfiles familiares");
        }
    }

    private static String limpiar(String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }

    private static MenorResponse respuesta(Menor menor) {
        return new MenorResponse(menor.getId(), menor.getNombre(), menor.getFechaNacimiento(),
                menor.getNombrePerfil(), menor.isActivo());
    }
}
