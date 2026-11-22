package com.familyguard.familyguard.realtime;

import com.familyguard.familyguard.domain.model.Dispositivo;
import com.familyguard.familyguard.domain.model.EstadoDispositivo;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Detecta cada actualización de un dispositivo (nueva revisión deseada, revocación o credencial renovada) en
 * un único punto, sin modificar los servicios de restricciones, sitios web, operaciones masivas ni vinculación.
 * El aviso se envía solo después del commit: si la transacción se revierte, no se notifica nada.
 *
 * <p>Hibernate crea este listener a través del contenedor de beans de Spring (configurado por Spring Boot),
 * que inyecta el {@link AgentEventHub}.
 */
public class DispositivoCambiosListener {
    @Autowired(required = false)
    private AgentEventHub hub;

    @PostUpdate
    void actualizado(Dispositivo d) {
        if (hub == null || d.getId() == null) return;
        long id = d.getId();
        long revision = d.getRevisionDeseada();
        int credencial = d.getVersionCredencial();
        boolean autorizado = d.isActivo() && !d.isAgenteRevocado() && d.getEstado() == EstadoDispositivo.AUTORIZADO;
        AgentEventHub destino = hub;
        Runnable aviso = () -> destino.cambio(id, revision, credencial, autorizado);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { aviso.run(); }
            });
        } else {
            aviso.run();
        }
    }
}
