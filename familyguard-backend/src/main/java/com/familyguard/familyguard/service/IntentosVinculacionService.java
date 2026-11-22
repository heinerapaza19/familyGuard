package com.familyguard.familyguard.service;

import com.familyguard.familyguard.domain.model.IntentoVinculacion;
import com.familyguard.familyguard.domain.repository.IntentoVinculacionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntentosVinculacionService {
    private final IntentoVinculacionRepository intentos;

    public IntentosVinculacionService(IntentoVinculacionRepository intentos) {
        this.intentos = intentos;
    }

    @Transactional(readOnly = true)
    public boolean bloqueado(String hash) {
        return intentos.findByHash(hash).map(IntentoVinculacion::bloqueado).orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registrarFallo(String hash) { return registrarFallo(hash, 5); }

    /** Contador persistente reutilizable para identificadores de equipo o direcciones IP (en hash). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registrarFallo(String hash, int maximo) {
        IntentoVinculacion intento = intentos.findByHash(hash)
                .orElseGet(() -> new IntentoVinculacion(hash));
        intento.fallar(maximo);
        intentos.saveAndFlush(intento);
        return intento.bloqueado();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void limpiar(String hash) {
        intentos.findByHash(hash).ifPresent(intentos::delete);
    }
}
