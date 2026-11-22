CREATE TABLE IF NOT EXISTS familias (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nombre VARCHAR(120) NOT NULL,
    descripcion VARCHAR(255),
    zona_horaria VARCHAR(64) NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP(6) NOT NULL,
    actualizado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_familias PRIMARY KEY (id),
    CONSTRAINT ck_familias_nombre CHECK (CHAR_LENGTH(TRIM(nombre)) > 0)
);

CREATE TABLE IF NOT EXISTS usuarios (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    nombre VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    rol VARCHAR(32) NOT NULL,
    estado_cuenta VARCHAR(24) NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP(6) NOT NULL,
    actualizado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_usuarios PRIMARY KEY (id),
    CONSTRAINT uq_usuarios_familia_id UNIQUE (familia_id, id),
    CONSTRAINT uq_usuarios_email UNIQUE (email),
    CONSTRAINT fk_usuarios_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
    CONSTRAINT ck_usuarios_password_hash CHECK (CHAR_LENGTH(password_hash) >= 50),
    CONSTRAINT ck_usuarios_rol CHECK (rol IN ('PADRE', 'MADRE', 'TUTOR', 'ADMINISTRADOR_FAMILIAR')),
    CONSTRAINT ck_usuarios_estado CHECK (estado_cuenta IN ('PENDIENTE', 'ACTIVA', 'BLOQUEADA', 'SUSPENDIDA'))
);

CREATE TABLE IF NOT EXISTS menores (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    nombre VARCHAR(120) NOT NULL,
    fecha_nacimiento DATE,
    nombre_perfil VARCHAR(80),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP(6) NOT NULL,
    actualizado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_menores PRIMARY KEY (id),
    CONSTRAINT uq_menores_familia_id UNIQUE (familia_id, id),
    CONSTRAINT fk_menores_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS dispositivos (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    menor_id BIGINT,
    nombre VARCHAR(120) NOT NULL,
    identificador VARCHAR(64) NOT NULL,
    tipo VARCHAR(16) NOT NULL,
    estado VARCHAR(24) NOT NULL,
    sistema_operativo VARCHAR(120),
    version_sistema VARCHAR(80),
    ultima_conexion TIMESTAMP(6),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP(6) NOT NULL,
    actualizado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_dispositivos PRIMARY KEY (id),
    CONSTRAINT uq_dispositivos_familia_id UNIQUE (familia_id, id),
    CONSTRAINT uq_dispositivos_identificador UNIQUE (identificador),
    CONSTRAINT fk_dispositivos_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dispositivos_menor FOREIGN KEY (familia_id, menor_id) REFERENCES menores (familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_dispositivos_tipo CHECK (tipo IN ('WINDOWS', 'ANDROID')),
    CONSTRAINT ck_dispositivos_estado CHECK (estado IN ('PENDIENTE', 'AUTORIZADO', 'REVOCADO', 'DESCONECTADO'))
);

CREATE TABLE IF NOT EXISTS aplicaciones (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    dispositivo_id BIGINT NOT NULL,
    nombre VARCHAR(180) NOT NULL,
    ruta_ejecutable VARCHAR(700) NOT NULL,
    hash_ejecutable VARCHAR(128),
    version VARCHAR(80),
    editor VARCHAR(160),
    detectada_en TIMESTAMP(6) NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP(6) NOT NULL,
    actualizado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_aplicaciones PRIMARY KEY (id),
    CONSTRAINT uq_aplicaciones_familia_id UNIQUE (familia_id, id),
    CONSTRAINT uq_aplicaciones_dispositivo_ruta UNIQUE (familia_id, dispositivo_id, ruta_ejecutable),
    CONSTRAINT fk_aplicaciones_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
    CONSTRAINT fk_aplicaciones_dispositivo FOREIGN KEY (familia_id, dispositivo_id) REFERENCES dispositivos (familia_id, id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS usos_aplicacion (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    menor_id BIGINT NOT NULL,
    dispositivo_id BIGINT NOT NULL,
    aplicacion_id BIGINT NOT NULL,
    inicio_en TIMESTAMP(6) NOT NULL,
    fin_en TIMESTAMP(6) NOT NULL,
    duracion_segundos BIGINT NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP(6) NOT NULL,
    actualizado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_usos_aplicacion PRIMARY KEY (id),
    CONSTRAINT fk_usos_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
    CONSTRAINT fk_usos_menor FOREIGN KEY (familia_id, menor_id) REFERENCES menores (familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_usos_dispositivo FOREIGN KEY (familia_id, dispositivo_id) REFERENCES dispositivos (familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_usos_aplicacion FOREIGN KEY (familia_id, aplicacion_id) REFERENCES aplicaciones (familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_usos_intervalo CHECK (fin_en > inicio_en),
    CONSTRAINT ck_usos_duracion CHECK (duracion_segundos >= 0)
);

CREATE TABLE IF NOT EXISTS limites_tiempo (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    menor_id BIGINT NOT NULL,
    aplicacion_id BIGINT NOT NULL,
    periodo VARCHAR(16) NOT NULL,
    minutos_permitidos INT NOT NULL,
    vigente_desde DATE,
    vigente_hasta DATE,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP(6) NOT NULL,
    actualizado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_limites_tiempo PRIMARY KEY (id),
    CONSTRAINT uq_limites_tiempo_regla UNIQUE (familia_id, menor_id, aplicacion_id, periodo),
    CONSTRAINT fk_limites_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
    CONSTRAINT fk_limites_menor FOREIGN KEY (familia_id, menor_id) REFERENCES menores (familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_limites_aplicacion FOREIGN KEY (familia_id, aplicacion_id) REFERENCES aplicaciones (familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_limites_periodo CHECK (periodo IN ('DIARIO', 'SEMANAL')),
    CONSTRAINT ck_limites_minutos CHECK (minutos_permitidos > 0),
    CONSTRAINT ck_limites_vigencia CHECK (vigente_hasta IS NULL OR vigente_desde IS NULL OR vigente_hasta >= vigente_desde)
);

CREATE TABLE IF NOT EXISTS sitios_bloqueados (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    menor_id BIGINT NOT NULL,
    dominio VARCHAR(253) NOT NULL,
    incluir_subdominios BOOLEAN NOT NULL DEFAULT TRUE,
    motivo VARCHAR(255),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP(6) NOT NULL,
    actualizado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_sitios_bloqueados PRIMARY KEY (id),
    CONSTRAINT uq_sitios_bloqueados_regla UNIQUE (familia_id, menor_id, dominio),
    CONSTRAINT fk_sitios_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sitios_menor FOREIGN KEY (familia_id, menor_id) REFERENCES menores (familia_id, id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS horarios_restriccion (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    menor_id BIGINT NOT NULL,
    dia_semana VARCHAR(12) NOT NULL,
    hora_inicio TIME NOT NULL,
    hora_fin TIME NOT NULL,
    cruza_medianoche BOOLEAN NOT NULL DEFAULT FALSE,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP(6) NOT NULL,
    actualizado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_horarios_restriccion PRIMARY KEY (id),
    CONSTRAINT uq_horarios_restriccion_regla UNIQUE (familia_id, menor_id, dia_semana, hora_inicio, hora_fin),
    CONSTRAINT fk_horarios_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
    CONSTRAINT fk_horarios_menor FOREIGN KEY (familia_id, menor_id) REFERENCES menores (familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_horarios_dia CHECK (dia_semana IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    CONSTRAINT ck_horarios_intervalo CHECK (hora_inicio <> hora_fin)
);

CREATE TABLE IF NOT EXISTS eventos_auditoria (
    id BIGINT NOT NULL AUTO_INCREMENT,
    familia_id BIGINT NOT NULL,
    usuario_id BIGINT,
    dispositivo_id BIGINT,
    tipo_evento VARCHAR(80) NOT NULL,
    tipo_entidad VARCHAR(80),
    entidad_id BIGINT,
    descripcion VARCHAR(2000) NOT NULL,
    resultado VARCHAR(16) NOT NULL,
    direccion_ip VARCHAR(45),
    ocurrido_en TIMESTAMP(6) NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP(6) NOT NULL,
    actualizado_en TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_eventos_auditoria PRIMARY KEY (id),
    CONSTRAINT fk_eventos_familia FOREIGN KEY (familia_id) REFERENCES familias (id) ON DELETE RESTRICT,
    CONSTRAINT fk_eventos_usuario FOREIGN KEY (familia_id, usuario_id) REFERENCES usuarios (familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_eventos_dispositivo FOREIGN KEY (familia_id, dispositivo_id) REFERENCES dispositivos (familia_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_eventos_resultado CHECK (resultado IN ('EXITO', 'RECHAZADO', 'ERROR', 'INFORMATIVO'))
);

CREATE INDEX ix_usuarios_familia_activo ON usuarios (familia_id, activo);
CREATE INDEX ix_menores_familia_activo ON menores (familia_id, activo);
CREATE INDEX ix_dispositivos_familia_activo ON dispositivos (familia_id, activo);
CREATE INDEX ix_aplicaciones_dispositivo_activo ON aplicaciones (familia_id, dispositivo_id, activo);
CREATE INDEX ix_usos_dispositivo_inicio ON usos_aplicacion (familia_id, dispositivo_id, inicio_en);
CREATE INDEX ix_usos_menor_inicio ON usos_aplicacion (familia_id, menor_id, inicio_en);
CREATE INDEX ix_limites_consulta ON limites_tiempo (familia_id, menor_id, activo);
CREATE INDEX ix_sitios_consulta ON sitios_bloqueados (familia_id, menor_id, activo);
CREATE INDEX ix_horarios_consulta ON horarios_restriccion (familia_id, menor_id, dia_semana, activo);
CREATE INDEX ix_eventos_familia_fecha ON eventos_auditoria (familia_id, ocurrido_en);
