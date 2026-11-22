-- Permisos temporales con caducidad automática (diseño autorizado en la entrega final, 19/11/2026).
-- Ubicada en db/pendiente: se aplica en pruebas, en la base de desarrollo aislada y en un servidor nuevo (perfil
-- remote). No se aplica a familyguard_db mientras su perfil no incluya classpath:db/pendiente.
-- Aditiva: no modifica ni elimina datos. Las tres claves nuevas incluyen la clave primaria, por lo que siempre son
-- únicas con los datos existentes; permiten que la base garantice familia, hijo, equipo, aplicación y regla web.
ALTER TABLE dispositivos      ADD CONSTRAINT uq_dispositivos_familia_menor_id UNIQUE (familia_id, menor_id, id);
ALTER TABLE aplicaciones      ADD CONSTRAINT uq_aplicaciones_familia_disp_id   UNIQUE (familia_id, dispositivo_id, id);
ALTER TABLE sitios_bloqueados ADD CONSTRAINT uq_sitios_familia_menor_id        UNIQUE (familia_id, menor_id, id);

-- Concesión: qué autorizó el padre, para qué hijo y hasta cuándo (hora absoluta del servidor).
CREATE TABLE permisos_temporales (
  id BIGINT NOT NULL AUTO_INCREMENT,
  familia_id BIGINT NOT NULL,
  menor_id BIGINT NOT NULL,
  concesion_uuid VARCHAR(36) NOT NULL,
  huella_solicitud VARCHAR(64) NOT NULL,
  tipo VARCHAR(24) NOT NULL,
  minutos_extra INT NULL,
  inicio_en TIMESTAMP(6) NOT NULL,
  expira_en TIMESTAMP(6) NOT NULL,
  otorgado_por BIGINT NOT NULL,
  motivo VARCHAR(200) NULL,
  activo BOOLEAN NOT NULL DEFAULT TRUE,
  creado_en TIMESTAMP(6) NOT NULL,
  actualizado_en TIMESTAMP(6) NOT NULL,
  CONSTRAINT pk_permisos_temporales PRIMARY KEY (id),
  CONSTRAINT uq_permisos_concesion UNIQUE (familia_id, concesion_uuid),
  CONSTRAINT uq_permisos_familia_menor_id UNIQUE (familia_id, menor_id, id),
  CONSTRAINT fk_permisos_menor FOREIGN KEY (familia_id, menor_id) REFERENCES menores (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT fk_permisos_otorgado FOREIGN KEY (familia_id, otorgado_por) REFERENCES usuarios (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT ck_permisos_tipo CHECK (tipo IN ('TIEMPO_EXTRA','FUERA_HORARIO','DESBLOQUEO_TEMPORAL','PERMITIR_SITIO')),
  CONSTRAINT ck_permisos_minutos CHECK ((tipo = 'TIEMPO_EXTRA' AND minutos_extra BETWEEN 1 AND 240)
                                     OR (tipo <> 'TIEMPO_EXTRA' AND minutos_extra IS NULL)),
  CONSTRAINT ck_permisos_ventana CHECK (expira_en > inicio_en)
);

-- Aplicación por equipo: objetivo, estado confirmado por el Agente y revocación propios de cada computadora.
CREATE TABLE permisos_temporales_dispositivos (
  id BIGINT NOT NULL AUTO_INCREMENT,
  permiso_id BIGINT NOT NULL,
  familia_id BIGINT NOT NULL,
  menor_id BIGINT NOT NULL,
  dispositivo_id BIGINT NOT NULL,
  aplicacion_id BIGINT NULL,
  sitio_id BIGINT NULL,
  revocado_en TIMESTAMP(6) NULL,
  revocado_por BIGINT NULL,
  estado_agente VARCHAR(16) NOT NULL DEFAULT 'PENDIENTE',
  agente_actualizado_en TIMESTAMP(6) NULL,
  detalle_agente VARCHAR(500) NULL,
  activo BOOLEAN NOT NULL DEFAULT TRUE,
  creado_en TIMESTAMP(6) NOT NULL,
  actualizado_en TIMESTAMP(6) NOT NULL,
  CONSTRAINT pk_permisos_disp PRIMARY KEY (id),
  CONSTRAINT uq_permisos_disp UNIQUE (permiso_id, dispositivo_id),
  CONSTRAINT fk_pd_permiso FOREIGN KEY (familia_id, menor_id, permiso_id)
      REFERENCES permisos_temporales (familia_id, menor_id, id) ON DELETE RESTRICT,
  CONSTRAINT fk_pd_dispositivo FOREIGN KEY (familia_id, menor_id, dispositivo_id)
      REFERENCES dispositivos (familia_id, menor_id, id) ON DELETE RESTRICT,
  CONSTRAINT fk_pd_aplicacion FOREIGN KEY (familia_id, dispositivo_id, aplicacion_id)
      REFERENCES aplicaciones (familia_id, dispositivo_id, id) ON DELETE RESTRICT,
  CONSTRAINT fk_pd_sitio FOREIGN KEY (familia_id, menor_id, sitio_id)
      REFERENCES sitios_bloqueados (familia_id, menor_id, id) ON DELETE RESTRICT,
  CONSTRAINT fk_pd_revocado FOREIGN KEY (familia_id, revocado_por) REFERENCES usuarios (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT ck_pd_objetivo CHECK ((aplicacion_id IS NULL) <> (sitio_id IS NULL)),
  CONSTRAINT ck_pd_revocacion CHECK ((revocado_en IS NULL) = (revocado_por IS NULL)),
  CONSTRAINT ck_pd_estado CHECK (estado_agente IN ('PENDIENTE','APLICADO','FINALIZADO','ERROR'))
);
CREATE INDEX ix_pd_dispositivo ON permisos_temporales_dispositivos (familia_id, dispositivo_id, revocado_en);
