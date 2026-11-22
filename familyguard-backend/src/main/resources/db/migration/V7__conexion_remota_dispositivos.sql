ALTER TABLE codigos_vinculacion ADD COLUMN intentos_fallidos INT NOT NULL DEFAULT 0;
ALTER TABLE codigos_vinculacion ADD COLUMN max_intentos INT NOT NULL DEFAULT 5;
ALTER TABLE codigos_vinculacion ADD COLUMN bloqueado_en TIMESTAMP(6) NULL;
ALTER TABLE codigos_vinculacion ADD CONSTRAINT ck_codigos_intentos CHECK(intentos_fallidos >= 0 AND max_intentos BETWEEN 1 AND 10);

ALTER TABLE dispositivos ADD COLUMN usuario_local VARCHAR(160) NULL;
ALTER TABLE dispositivos ADD COLUMN version_agente VARCHAR(40) NULL;
ALTER TABLE dispositivos ADD COLUMN ultimo_heartbeat TIMESTAMP(6) NULL;
ALTER TABLE dispositivos ADD COLUMN intervalo_heartbeat_segundos INT NOT NULL DEFAULT 30;
ALTER TABLE dispositivos ADD COLUMN ultimo_error_comunicacion VARCHAR(500) NULL;
ALTER TABLE dispositivos ADD COLUMN revision_deseada BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dispositivos ADD COLUMN revision_aplicada BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dispositivos ADD COLUMN desvinculado_en TIMESTAMP(6) NULL;
ALTER TABLE dispositivos ADD COLUMN version_credencial INT NOT NULL DEFAULT 1;
ALTER TABLE dispositivos ADD CONSTRAINT ck_dispositivos_heartbeat CHECK(intervalo_heartbeat_segundos BETWEEN 15 AND 3600);
ALTER TABLE dispositivos ADD CONSTRAINT ck_dispositivos_revisiones CHECK(revision_deseada >= 0 AND revision_aplicada >= 0 AND version_credencial > 0);
CREATE INDEX ix_dispositivos_heartbeat ON dispositivos(familia_id,agente_revocado,ultimo_heartbeat);

CREATE TABLE confirmaciones_sincronizacion (
 id BIGINT NOT NULL AUTO_INCREMENT, familia_id BIGINT NOT NULL, dispositivo_id BIGINT NOT NULL,
 confirmacion_uuid VARCHAR(36) NOT NULL, revision BIGINT NOT NULL, estado VARCHAR(16) NOT NULL,
 detalle VARCHAR(500) NULL, confirmado_en TIMESTAMP(6) NOT NULL,
 activo BOOLEAN NOT NULL DEFAULT TRUE, creado_en TIMESTAMP(6) NOT NULL, actualizado_en TIMESTAMP(6) NOT NULL,
 CONSTRAINT pk_confirmaciones_sync PRIMARY KEY(id),
 CONSTRAINT uq_confirmaciones_sync_uuid UNIQUE(confirmacion_uuid),
 CONSTRAINT fk_confirmaciones_dispositivo FOREIGN KEY(familia_id,dispositivo_id) REFERENCES dispositivos(familia_id,id) ON DELETE RESTRICT,
 CONSTRAINT ck_confirmaciones_estado CHECK(estado IN ('APLICADA','FALLIDA')),
 CONSTRAINT ck_confirmaciones_revision CHECK(revision >= 0)
);
CREATE INDEX ix_confirmaciones_dispositivo ON confirmaciones_sincronizacion(familia_id,dispositivo_id,confirmado_en);

CREATE TABLE intentos_vinculacion (
 id BIGINT NOT NULL AUTO_INCREMENT, identificador_hash VARCHAR(64) NOT NULL, intentos INT NOT NULL DEFAULT 0,
 bloqueado_hasta TIMESTAMP(6) NULL, activo BOOLEAN NOT NULL DEFAULT TRUE,
 creado_en TIMESTAMP(6) NOT NULL, actualizado_en TIMESTAMP(6) NOT NULL,
 CONSTRAINT pk_intentos_vinculacion PRIMARY KEY(id),
 CONSTRAINT uq_intentos_identificador UNIQUE(identificador_hash),
 CONSTRAINT ck_intentos_cantidad CHECK(intentos >= 0)
);
