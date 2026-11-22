ALTER TABLE usos_aplicacion ADD COLUMN registro_uuid VARCHAR(36) NULL;
ALTER TABLE usos_aplicacion ADD COLUMN fecha_local DATE NULL;
ALTER TABLE usos_aplicacion ADD COLUMN zona_horaria VARCHAR(64) NULL;
ALTER TABLE usos_aplicacion ADD CONSTRAINT uq_usos_registro_uuid UNIQUE (registro_uuid);

CREATE INDEX ix_usos_familia_fecha_aplicacion
    ON usos_aplicacion (familia_id, fecha_local, aplicacion_id);
CREATE INDEX ix_usos_dispositivo_fecha
    ON usos_aplicacion (familia_id, dispositivo_id, fecha_local);
