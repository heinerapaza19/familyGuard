-- PIN parental y autorizaciones de pausa o desinstalación, en tablas separadas de los permisos temporales.
-- Aditiva. Preparada en la entrega final: se aplica en pruebas (H2) y en la base de desarrollo aislada
-- (perfil "dev"). No debe aplicarse a familyguard_db sin autorización expresa del usuario.

-- Un PIN por familia: cifrado AES-GCM en el servidor (para «Ver PIN» con reautenticación) y verificador PBKDF2
-- no reversible que reciben los Agentes.
CREATE TABLE pines_parentales (
  id BIGINT NOT NULL AUTO_INCREMENT,
  familia_id BIGINT NOT NULL,
  pin_cifrado VARCHAR(512) NOT NULL,
  verificador VARCHAR(255) NOT NULL,
  version INT NOT NULL,
  actualizado_por BIGINT NOT NULL,
  activo BOOLEAN NOT NULL DEFAULT TRUE,
  creado_en TIMESTAMP(6) NOT NULL,
  actualizado_en TIMESTAMP(6) NOT NULL,
  CONSTRAINT pk_pines_parentales PRIMARY KEY (id),
  CONSTRAINT uq_pines_familia UNIQUE (familia_id),
  CONSTRAINT fk_pines_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
  CONSTRAINT fk_pines_usuario FOREIGN KEY (familia_id, actualizado_por) REFERENCES usuarios (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT ck_pines_version CHECK (version >= 1)
);

-- Autorizaciones remotas del padre para un equipo: pausar la supervisión o desinstalar el Agente.
CREATE TABLE autorizaciones_dispositivo (
  id BIGINT NOT NULL AUTO_INCREMENT,
  familia_id BIGINT NOT NULL,
  dispositivo_id BIGINT NOT NULL,
  tipo VARCHAR(16) NOT NULL,
  autorizado_por BIGINT NOT NULL,
  autorizado_en TIMESTAMP(6) NOT NULL,
  expira_en TIMESTAMP(6) NOT NULL,
  usada_en TIMESTAMP(6) NULL,
  cancelada_en TIMESTAMP(6) NULL,
  cancelada_por BIGINT NULL,
  activo BOOLEAN NOT NULL DEFAULT TRUE,
  creado_en TIMESTAMP(6) NOT NULL,
  actualizado_en TIMESTAMP(6) NOT NULL,
  CONSTRAINT pk_autorizaciones_dispositivo PRIMARY KEY (id),
  CONSTRAINT fk_autd_dispositivo FOREIGN KEY (familia_id, dispositivo_id) REFERENCES dispositivos (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT fk_autd_autorizado FOREIGN KEY (familia_id, autorizado_por) REFERENCES usuarios (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT fk_autd_cancelada FOREIGN KEY (familia_id, cancelada_por) REFERENCES usuarios (familia_id, id) ON DELETE RESTRICT,
  CONSTRAINT ck_autd_tipo CHECK (tipo IN ('PAUSA','DESINSTALACION')),
  CONSTRAINT ck_autd_ventana CHECK (expira_en > autorizado_en),
  CONSTRAINT ck_autd_cancelacion CHECK ((cancelada_en IS NULL) = (cancelada_por IS NULL))
);
CREATE INDEX ix_autd_dispositivo ON autorizaciones_dispositivo (familia_id, dispositivo_id, expira_en);
