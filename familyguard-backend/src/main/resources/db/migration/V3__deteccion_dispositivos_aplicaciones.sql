ALTER TABLE dispositivos ADD COLUMN agente_token_hash VARCHAR(64) NULL;
ALTER TABLE dispositivos ADD COLUMN agente_revocado BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dispositivos ADD COLUMN ultima_sincronizacion TIMESTAMP(6) NULL;
ALTER TABLE dispositivos ADD CONSTRAINT uq_dispositivos_agente_token UNIQUE (agente_token_hash);

ALTER TABLE aplicaciones MODIFY COLUMN ruta_ejecutable VARCHAR(700) NULL;
ALTER TABLE aplicaciones ADD COLUMN tipo_instalacion VARCHAR(32) NULL;
ALTER TABLE aplicaciones ADD COLUMN en_ejecucion BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE aplicaciones ADD COLUMN ventana_activa BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE aplicaciones ADD COLUMN ultimo_pid BIGINT NULL;
ALTER TABLE aplicaciones ADD COLUMN ultima_deteccion TIMESTAMP(6) NULL;

CREATE TABLE codigos_vinculacion (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    menor_id BIGINT NOT NULL,
    codigo_hash VARCHAR(64) NOT NULL,
    expira_en TIMESTAMP(6) NOT NULL,
    usado_en TIMESTAMP(6) NULL,
    creado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_codigos_vinculacion PRIMARY KEY (id),
    CONSTRAINT uq_codigos_hash UNIQUE (codigo_hash),
    CONSTRAINT fk_codigos_familia FOREIGN KEY (familia_id) REFERENCES familias(id) ON DELETE RESTRICT,
    CONSTRAINT fk_codigos_menor FOREIGN KEY (familia_id, menor_id)
        REFERENCES menores(familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_codigos_expiracion CHECK (expira_en > creado_en)
);
CREATE INDEX ix_codigos_validacion ON codigos_vinculacion (codigo_hash, expira_en, usado_en);

CREATE TABLE procesos_detectados (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    dispositivo_id BIGINT NOT NULL,
    pid BIGINT NOT NULL,
    nombre VARCHAR(180) NOT NULL,
    ruta_ejecutable VARCHAR(700) NULL,
    en_ejecucion BOOLEAN NOT NULL DEFAULT TRUE,
    ventana_activa BOOLEAN NOT NULL DEFAULT FALSE,
    observado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_procesos_detectados PRIMARY KEY (id),
    CONSTRAINT uq_procesos_dispositivo_pid UNIQUE (dispositivo_id, pid),
    CONSTRAINT fk_procesos_familia FOREIGN KEY (familia_id) REFERENCES familias(id) ON DELETE RESTRICT,
    CONSTRAINT fk_procesos_dispositivo FOREIGN KEY (familia_id, dispositivo_id)
        REFERENCES dispositivos(familia_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_procesos_estado ON procesos_detectados (familia_id, dispositivo_id, en_ejecucion, ventana_activa);
