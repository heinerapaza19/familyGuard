# Configuracion de MySQL para FamilyGuard

Revision: 15 de noviembre de 2026 (America/Lima)

## Instalacion detectada

- Distribucion: MySQL Community Server 8.4.3 incluido en Laragon.
- Ejecutable: `G:\laragon\bin\mysql\mysql-8.4.3-winx64\bin\mysqld.exe`.
- Datos: `G:\laragon\data\mysql-8.4\`.
- Administracion del proceso: Laragon; no existe un servicio MySQL/MariaDB registrado en Windows.
- Puerto clasico: 3306.
- Puerto X Protocol: 33060.
- Charset/collation del servidor: `utf8mb4` / `utf8mb4_0900_ai_ci`.
- Conexion comprobada con TLS: `TLS_AES_128_GCM_SHA256`.

No se encontraron otras instalaciones registradas ni servicios MySQL/MariaDB. La instancia de Laragon ya estaba iniciada y respondio correctamente.

## Base FamilyGuard

Se creo exclusivamente `familyguard_db`, con `utf8mb4` y `utf8mb4_0900_ai_ci`. No se eliminaron ni modificaron otras bases.

Flyway aplico `V1__crear_modelo_inicial.sql` y `V2__autenticacion_y_sesiones.sql`. El resultado real es:

- 11 tablas funcionales y `flyway_schema_history` (12 en total).
- 11 claves primarias.
- 20 claves foraneas.
- 10 restricciones unicas.
- 14 restricciones `CHECK`.
- 37 indices en total, incluidos los requeridos por claves.
- Historial Flyway: versiones 1 y 2, ambas con estado exitoso.

V2 agrega `intentos_fallidos`, `bloqueado_hasta` y `ultimo_acceso` a `usuarios`, amplia
la restriccion de roles y crea `sesiones_autenticacion`. La tabla de sesiones tiene clave
primaria, hash de token unico, dos claves foraneas (incluida la relacion compuesta que
impide mezclar familias), restriccion de expiracion y dos indices de consulta.

La primera ejecucion real detecto que el indice de `ruta_ejecutable VARCHAR(1024)` excedia los 3072 bytes permitidos por InnoDB con `utf8mb4`. Se ajusto el campo a 700 caracteres. Se uso `Flyway.repair()` para retirar solo el registro fallido y reanudar V1; nunca se ejecuto `flyway clean` ni se borraron datos.

## Cuenta de aplicacion

La comprobacion se realizo temporalmente con la cuenta administrativa local existente de Laragon porque no habia una credencial de aplicacion proporcionada. No se guardo esa cuenta en archivos del proyecto.

La revision de la etapa 3 confirmo que `familyguard_app` aun no existe. Antes de usar
FamilyGuard de manera habitual, cree la cuenta dedicada. Elija el secreto localmente; no
lo copie al chat, al repositorio ni a la documentacion. Abra el cliente de MySQL con `-p`
si su cuenta administrativa tiene clave y ejecute, sustituyendo el marcador dentro de su
terminal local:

```sql
CREATE USER 'familyguard_app'@'localhost'
  IDENTIFIED WITH caching_sha2_password BY '<SECRETO_ELEGIDO_LOCALMENTE>';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON familyguard_db.* TO 'familyguard_app'@'localhost';

SHOW GRANTS FOR 'familyguard_app'@'localhost';
```

Flyway necesita temporalmente permisos DDL (`CREATE`, `ALTER`, `INDEX`, `REFERENCES`) para futuras migraciones. En un despliegue profesional se recomienda separar la cuenta de migracion de la cuenta de ejecucion y retirar permisos DDL a esta ultima.

Despues, defina las variables en la misma consola y verifique sin mostrar el secreto:

```powershell
$env:FAMILYGUARD_DB_USERNAME = 'familyguard_app'
$env:FAMILYGUARD_DB_PASSWORD = Read-Host 'Password de familyguard_app' -MaskInput
./mvnw.cmd -pl familyguard-backend spring-boot:run
```

El resultado esperado incluye Flyway en version 2, inicializacion del
`EntityManagerFactory` y arranque de Tomcat. La comprobacion temporal del 15 de noviembre
de 2026 obtuvo conexion real, aplico V2, valido Hibernate y arranco Spring Boot con MySQL
8.4.3. No se guardo la cuenta administrativa usada para esa comprobacion.

## Variables de entorno

Defina las variables solo en el entorno local o en un gestor de secretos:

```powershell
$env:FAMILYGUARD_DB_URL = 'jdbc:mysql://localhost:3306/familyguard_db?useSSL=true&requireSSL=true&serverTimezone=UTC'
$env:FAMILYGUARD_DB_USERNAME = 'familyguard_app'
$env:FAMILYGUARD_DB_PASSWORD = '<SECRETO_ELEGIDO_LOCALMENTE>'
.\mvnw.cmd -pl familyguard-backend spring-boot:run
```

La URL productiva exige TLS. MySQL es la configuracion normal y no existe fallback a H2. Si faltan credenciales o MySQL esta detenido, Spring Boot finaliza con error durante Flyway/JPA.

## Iniciar MySQL

Esta instalacion no usa un servicio de Windows. Abra Laragon y pulse `Start All` o `Start MySQL`. Compruebe despues:

```powershell
& 'G:\laragon\bin\mysql\mysql-8.4.3-winx64\bin\mysqladmin.exe' `
  --protocol=TCP -h 127.0.0.1 -P 3306 -u familyguard_app -p ping
```

El parametro `-p` solicita el secreto de forma interactiva y evita escribirlo en el historial del terminal.

## Separacion de H2

- MySQL Connector/J y Flyway MySQL son dependencias de ejecucion.
- H2 tiene alcance Maven `test` y no se incluye en el JAR ejecutable.
- La URL H2 existe solo en `src/test/resources/application-test.properties`.
- Las pruebas activan explicitamente el perfil `test`.
- La configuracion normal apunta a `familyguard_db` en MySQL y falla si no puede conectarse.

## Verificacion de la Etapa 5 (15 de noviembre de 2026)

La instancia Laragon `G:\laragon` fue iniciada directamente con su configuracion existente,
sin modificarla ni registrar un servicio. MySQL Community 8.4.3 respondio por TCP/TLS en
`127.0.0.1:3306`. `familyguard_db` ya existia.

El backend se inicio temporalmente con la cuenta administrativa local no persistida. Flyway
valido V1/V2 y aplico `V3__deteccion_dispositivos_aplicaciones.sql`; Hibernate 7.4.13 valido
el esquema con `ddl-auto=validate`, Tomcat inicio en 8080 y `/actuator/health` devolvio `UP`.
El historial contiene V1, V2 y V3 con `success=1`. El esquema tiene 14 tablas contando
`flyway_schema_history`, 26 relaciones referenciales y 97 entradas de indices.

La cuenta `familyguard_app` sigue sin existir: no se creo porque requiere una contrasena
elegida por el usuario. Esta es la unica accion externa pendiente para la operacion diaria
con minimo privilegio; las migraciones y el arranque MySQL real si quedaron comprobados.

## Verificacion de la Etapa 6

Flyway aplico `V4__monitoreo_tiempo_uso.sql` sobre la misma instancia MySQL Community 8.4.3.
El historial registra V1-V4 con `success=1`. V4 amplio `usos_aplicacion` sin crear ni borrar
tablas; el esquema conserva 14 tablas contando el historial. Hibernate valido los nuevos
campos e indices y Spring Boot arranco en 8080 con health `UP`.

## Verificacion de la Etapa 7

El 16/11/2026 se inició la misma instancia Laragon sin cambiar su configuración. Flyway
validó V1-V5 con `success=1`; V5 añadió campos de restricciones y auditoría sin borrar
datos. El esquema conserva 14 tablas. Spring Boot conectó mediante MySQL Connector/J,
Hibernate identificó MySQL 8.4.3 y validó el modelo, Tomcat arrancó en el puerto aleatorio
53108 y `/actuator/health` respondió `UP`. Las credenciales de ejecución se reciben mediante
`FAMILYGUARD_DB_URL`, `FAMILYGUARD_DB_USERNAME` y `FAMILYGUARD_DB_PASSWORD`; no se guardó
ninguna contraseña real en el repositorio.
