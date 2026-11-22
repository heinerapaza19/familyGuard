-- Corrección autorizada el 19/11/2026 (MySQL 8.4.3 / H2 en pruebas).
-- V1 definió la unicidad de horarios por hijo (familia, menor, día, inicio, fin) y V5 hizo los horarios
-- por aplicación sin actualizarla: el mismo horario para la misma aplicación en dos computadoras del hijo
-- (cada equipo tiene su propio registro de aplicación) se rechazaba. La unicidad pasa a ser por aplicación.
-- No modifica ni elimina datos: los existentes ya cumplen la regla anterior, más estricta.
-- Primero se crea la nueva restricción y luego se elimina la anterior, para que la clave foránea
-- (familia_id, menor_id) hacia menores conserve siempre un índice de respaldo en MySQL.
ALTER TABLE horarios_restriccion ADD CONSTRAINT uq_horarios_restriccion_aplicacion
    UNIQUE (familia_id, menor_id, aplicacion_id, dia_semana, hora_inicio, hora_fin);
ALTER TABLE horarios_restriccion DROP CONSTRAINT uq_horarios_restriccion_regla;
