-- Solicitudes de desbloqueo del hijo («Justificar»): mensaje y foto opcional de evidencia que el padre revisa en el
-- Administrador. Autorizada por el usuario el 20/11/2026. Ubicada en db/pendiente: se aplica en pruebas (H2), en la
-- base de desarrollo aislada (perfil dev) y en un servidor nuevo (perfil remote); no se aplica a familyguard_db.
-- Aditiva: no modifica ni elimina tablas ni datos existentes.
CREATE TABLE solicitudes_desbloqueo (
  id BIGINT NOT NULL AUTO_INCREMENT,
  familia_id BIGINT NOT NULL,
  menor_id BIGINT NOT NULL,
  dispositivo_id BIGINT NOT NULL,
  tipo VARCHAR(8) NOT NULL,
  aplicacion_id BIGINT NULL,
  sitio_id BIGINT NULL,
  objetivo VARCHAR(255) NOT NULL,
  mensaje VARCHAR(500) NOT NULL,
  foto MEDIUMBLOB NULL,
  foto_tipo VARCHAR(16) NULL,
  estado VARCHAR(12) NOT NULL,
  respuesta VARCHAR(300) NULL,
  minutos_aprobados INT NULL,
  permiso_id BIGINT NULL,
  resuelto_por BIGINT NULL,
  creado_en TIMESTAMP(6) NOT NULL,
  resuelto_en TIMESTAMP(6) NULL,
  CONSTRAINT pk_solicitudes_desbloqueo PRIMARY KEY (id),
  CONSTRAINT fk_solicitudes_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
  CONSTRAINT fk_solicitudes_menor FOREIGN KEY (familia_id, menor_id) REFERENCES menores (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT fk_solicitudes_dispositivo FOREIGN KEY (dispositivo_id) REFERENCES dispositivos (id) ON DELETE RESTRICT,
  CONSTRAINT fk_solicitudes_aplicacion FOREIGN KEY (aplicacion_id) REFERENCES aplicaciones (id) ON DELETE SET NULL,
  CONSTRAINT fk_solicitudes_sitio FOREIGN KEY (sitio_id) REFERENCES sitios_bloqueados (id) ON DELETE SET NULL,
  CONSTRAINT fk_solicitudes_permiso FOREIGN KEY (permiso_id) REFERENCES permisos_temporales (id) ON DELETE SET NULL,
  CONSTRAINT fk_solicitudes_resuelto FOREIGN KEY (familia_id, resuelto_por) REFERENCES usuarios (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT ck_solicitudes_tipo CHECK (tipo IN ('APP', 'WEB')),
  CONSTRAINT ck_solicitudes_estado CHECK (estado IN ('PENDIENTE', 'APROBADA', 'RECHAZADA')),
  CONSTRAINT ck_solicitudes_foto_tipo CHECK (foto_tipo IS NULL OR foto_tipo IN ('image/png', 'image/jpeg')),
  CONSTRAINT ck_solicitudes_minutos CHECK (minutos_aprobados IS NULL OR minutos_aprobados BETWEEN 5 AND 720)
);

CREATE INDEX ix_solicitudes_familia_estado ON solicitudes_desbloqueo (familia_id, estado, creado_en);
CREATE INDEX ix_solicitudes_dispositivo ON solicitudes_desbloqueo (dispositivo_id, creado_en);
