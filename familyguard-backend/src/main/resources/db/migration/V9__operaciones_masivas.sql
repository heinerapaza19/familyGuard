-- Etapa 4 (restricciones masivas). Migración ADITIVA autorizada el 19/11/2026.
-- No modifica ni elimina datos existentes: solo agrega dos columnas con valor por defecto y dos tablas nuevas.

-- 1. Entrega verificable: revisión de políticas que el Agente descargó (se registra en el backend al
--    entregar las reglas web, último paso del ciclo de descarga). No cambia el protocolo del Agente.
ALTER TABLE dispositivos ADD COLUMN revision_recibida BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dispositivos ADD COLUMN politica_recibida_en TIMESTAMP(6) NULL;
ALTER TABLE dispositivos ADD CONSTRAINT ck_dispositivos_revision_recibida CHECK (revision_recibida >= 0);

-- 2. Operación masiva idempotente (un identificador por operación y familia).
CREATE TABLE operaciones_masivas (
  id BIGINT NOT NULL AUTO_INCREMENT,
  familia_id BIGINT NOT NULL,
  usuario_id BIGINT NOT NULL,
  operacion_uuid VARCHAR(36) NOT NULL,
  huella_solicitud VARCHAR(64) NOT NULL,
  tipo VARCHAR(24) NOT NULL,
  objetivo VARCHAR(253) NOT NULL,
  parametros VARCHAR(1000) NULL,
  total_dispositivos INT NOT NULL,
  estado VARCHAR(16) NOT NULL,
  cancelada_en TIMESTAMP(6) NULL,
  activo BOOLEAN NOT NULL DEFAULT TRUE,
  creado_en TIMESTAMP(6) NOT NULL,
  actualizado_en TIMESTAMP(6) NOT NULL,
  CONSTRAINT pk_operaciones_masivas PRIMARY KEY (id),
  CONSTRAINT uq_operaciones_masivas_uuid UNIQUE (familia_id, operacion_uuid),
  CONSTRAINT fk_opm_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
  CONSTRAINT fk_opm_usuario FOREIGN KEY (familia_id, usuario_id) REFERENCES usuarios (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT ck_opm_tipo CHECK (tipo IN ('BLOQUEAR_SITIO','RETIRAR_SITIO','LIMITAR_APLICACION','RETIRAR_LIMITE')),
  CONSTRAINT ck_opm_estado CHECK (estado IN ('REGISTRADA','CANCELADA')),
  CONSTRAINT ck_opm_total CHECK (total_dispositivos BETWEEN 1 AND 200)
);
CREATE INDEX ix_opm_familia_creado ON operaciones_masivas (familia_id, creado_en);

-- 3. Resultado inicial y revisión objetivo por equipo. El estado vivo (pendiente, recibida, aplicada,
--    error, desconectado) se calcula con datos reales; nunca se guarda «aplicada» por adelantado.
--    aplicacion_id referencia aplicaciones (borrado lógico); no se referencia limites_tiempo porque
--    su retiro elimina la fila.
CREATE TABLE operaciones_masivas_dispositivos (
  id BIGINT NOT NULL AUTO_INCREMENT,
  operacion_id BIGINT NOT NULL,
  familia_id BIGINT NOT NULL,
  dispositivo_id BIGINT NOT NULL,
  sitio_id BIGINT NULL,
  aplicacion_id BIGINT NULL,
  revision_objetivo BIGINT NULL,
  resultado VARCHAR(32) NOT NULL,
  detalle VARCHAR(500) NULL,
  activo BOOLEAN NOT NULL DEFAULT TRUE,
  creado_en TIMESTAMP(6) NOT NULL,
  actualizado_en TIMESTAMP(6) NOT NULL,
  CONSTRAINT pk_opm_dispositivos PRIMARY KEY (id),
  CONSTRAINT uq_opm_dispositivo UNIQUE (operacion_id, dispositivo_id),
  CONSTRAINT fk_omd_operacion FOREIGN KEY (operacion_id) REFERENCES operaciones_masivas (id) ON DELETE RESTRICT,
  CONSTRAINT fk_omd_dispositivo FOREIGN KEY (familia_id, dispositivo_id) REFERENCES dispositivos (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT fk_omd_sitio FOREIGN KEY (sitio_id) REFERENCES sitios_bloqueados (id) ON DELETE RESTRICT,
  CONSTRAINT fk_omd_aplicacion FOREIGN KEY (familia_id, aplicacion_id) REFERENCES aplicaciones (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT ck_omd_resultado CHECK (resultado IN ('PROGRAMADA','SIN_CAMBIOS','APLICACION_NO_REGISTRADA',
                                                   'APLICACION_AMBIGUA','CANCELADA')),
  CONSTRAINT ck_omd_revision CHECK (revision_objetivo IS NULL OR revision_objetivo >= 0)
);
CREATE INDEX ix_omd_dispositivo ON operaciones_masivas_dispositivos (familia_id, dispositivo_id);
