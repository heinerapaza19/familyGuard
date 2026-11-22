-- Migración aditiva: estado real de aplicación del bloqueo web por regla y dispositivo.
-- No modifica ni elimina datos existentes; las asignaciones actuales quedan en 'PENDIENTE'
-- hasta que cada agente confirme el contenido real de su archivo hosts.
ALTER TABLE sitios_dispositivos ADD COLUMN estado_aplicacion VARCHAR(16) NOT NULL DEFAULT 'PENDIENTE';
ALTER TABLE sitios_dispositivos ADD COLUMN detalle_aplicacion VARCHAR(500) NULL;
ALTER TABLE sitios_dispositivos ADD COLUMN aplicado_en TIMESTAMP(6) NULL;
ALTER TABLE sitios_dispositivos ADD COLUMN revision_aplicada BIGINT NULL;
ALTER TABLE sitios_dispositivos ADD CONSTRAINT ck_sd_estado_aplicacion
    CHECK (estado_aplicacion IN ('PENDIENTE', 'APLICADO', 'ERROR'));
