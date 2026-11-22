ALTER TABLE usuarios ADD COLUMN intentos_fallidos INT NOT NULL DEFAULT 0;
ALTER TABLE usuarios ADD COLUMN bloqueado_hasta TIMESTAMP(6) NULL;
ALTER TABLE usuarios ADD COLUMN ultimo_acceso TIMESTAMP(6) NULL;

ALTER TABLE usuarios DROP CONSTRAINT ck_usuarios_rol;
ALTER TABLE usuarios ADD CONSTRAINT ck_usuarios_rol
    CHECK (rol IN ('PADRE', 'MADRE', 'TUTOR', 'ADMINISTRADOR_FAMILIAR', 'ADMIN_SISTEMA'));

CREATE TABLE sesiones_autenticacion (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    usuario_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    creada_en TIMESTAMP(6) NOT NULL,
    expira_en TIMESTAMP(6) NOT NULL,
    revocada_en TIMESTAMP(6),
    ultima_actividad_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_sesiones_autenticacion PRIMARY KEY (id),
    CONSTRAINT uq_sesiones_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_sesiones_familia FOREIGN KEY (familia_id)
        REFERENCES familias (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sesiones_usuario FOREIGN KEY (familia_id, usuario_id)
        REFERENCES usuarios (familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_sesiones_expiracion CHECK (expira_en > creada_en)
);

CREATE INDEX ix_sesiones_validacion
    ON sesiones_autenticacion (token_hash, revocada_en, expira_en);
CREATE INDEX ix_sesiones_usuario
    ON sesiones_autenticacion (familia_id, usuario_id, expira_en);
