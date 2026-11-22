-- Plantilla de usuarios MySQL para el servidor (Etapa 7). NO se ha ejecutado.
-- Reemplace <CONTRASENA_APP> y <CONTRASENA_MIGRACION> por contraseñas generadas en el servidor.
-- No use root para la aplicación. MySQL solo debe escuchar en 127.0.0.1 (bind-address).
CREATE DATABASE IF NOT EXISTS familyguard_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Aplicación: solo datos, sin cambios de estructura ni permisos globales.
CREATE USER 'familyguard_app'@'localhost' IDENTIFIED BY '<CONTRASENA_APP>';
GRANT SELECT, INSERT, UPDATE, DELETE ON familyguard_db.* TO 'familyguard_app'@'localhost';

-- Migraciones Flyway: estructura solo en esta base (sin DROP de tablas; flyway clean está deshabilitado).
CREATE USER 'familyguard_migracion'@'localhost' IDENTIFIED BY '<CONTRASENA_MIGRACION>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON familyguard_db.* TO 'familyguard_migracion'@'localhost';
