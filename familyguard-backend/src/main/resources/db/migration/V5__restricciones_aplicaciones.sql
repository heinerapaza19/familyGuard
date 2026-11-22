ALTER TABLE limites_tiempo ADD COLUMN bloqueo_total BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE limites_tiempo ADD COLUMN advertencia_minutos INT NOT NULL DEFAULT 5;
ALTER TABLE limites_tiempo ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE limites_tiempo ADD CONSTRAINT ck_limites_advertencia
    CHECK (advertencia_minutos >= 0 AND advertencia_minutos <= 60);

ALTER TABLE horarios_restriccion ADD COLUMN aplicacion_id BIGINT NULL;
ALTER TABLE horarios_restriccion ADD COLUMN tipo_regla VARCHAR(16) NOT NULL DEFAULT 'BLOQUEAR';
ALTER TABLE horarios_restriccion ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE horarios_restriccion ADD CONSTRAINT fk_horarios_aplicacion
    FOREIGN KEY (familia_id, aplicacion_id) REFERENCES aplicaciones(familia_id, id) ON DELETE RESTRICT;
ALTER TABLE horarios_restriccion ADD CONSTRAINT ck_horarios_tipo
    CHECK (tipo_regla IN ('BLOQUEAR', 'PERMITIR'));
CREATE INDEX ix_horarios_aplicacion ON horarios_restriccion
    (familia_id, menor_id, aplicacion_id, activo);

ALTER TABLE eventos_auditoria ADD COLUMN evento_uuid VARCHAR(36) NULL;
ALTER TABLE eventos_auditoria ADD CONSTRAINT uq_eventos_uuid UNIQUE (evento_uuid);
