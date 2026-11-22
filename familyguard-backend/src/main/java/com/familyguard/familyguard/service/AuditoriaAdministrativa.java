package com.familyguard.familyguard.service;

import com.familyguard.familyguard.domain.model.EventoAuditoria;
import com.familyguard.familyguard.domain.model.ResultadoEvento;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.EventoAuditoriaRepository;
import com.familyguard.familyguard.domain.repository.FamiliaRepository;
import com.familyguard.familyguard.domain.repository.UsuarioRepository;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro de acciones administrativas del padre o tutor (Etapa 6). Participa en la transacción de la acción:
 * si la acción se revierte, el registro también. La descripción nunca incluye tokens, contraseñas ni PIN.
 */
@Component
public class AuditoriaAdministrativa {
    private final EventoAuditoriaRepository eventos;
    private final FamiliaRepository familias;
    private final UsuarioRepository usuarios;
    private final DispositivoRepository dispositivos;

    public AuditoriaAdministrativa(EventoAuditoriaRepository eventos, FamiliaRepository familias,
                                   UsuarioRepository usuarios, DispositivoRepository dispositivos) {
        this.eventos = eventos; this.familias = familias; this.usuarios = usuarios; this.dispositivos = dispositivos;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(UsuarioPrincipal principal, String tipoEvento, String tipoEntidad, Long entidadId,
                          Long dispositivoId, String descripcion) {
        EventoAuditoria evento = new EventoAuditoria(familias.getReferenceById(principal.familiaId()), tipoEvento,
                recortar(descripcion), ResultadoEvento.EXITO);
        evento.setUsuario(usuarios.getReferenceById(principal.usuarioId()));
        if (dispositivoId != null) evento.setDispositivo(dispositivos.getReferenceById(dispositivoId));
        evento.setTipoEntidad(tipoEntidad);
        evento.setEntidadId(entidadId);
        eventos.save(evento);
    }

    private static String recortar(String texto) {
        String valor = texto == null ? "" : texto;
        return valor.length() <= 2000 ? valor : valor.substring(0, 1997) + "...";
    }
}
