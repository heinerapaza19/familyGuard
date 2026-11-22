# Modelo de datos de FamilyGuard

## Principios

El backend usa MySQL 8.x en produccion, Flyway como unica autoridad para crear o modificar el esquema y Hibernate en modo `validate`. H2 2.3.232 se usa solo en pruebas, con modo de compatibilidad MySQL.

Todas las tablas funcionales incluyen `familia_id`. Las relaciones entre datos de una familia utilizan claves foraneas compuestas `(familia_id, id_relacionado)`, de modo que la base rechaza una referencia a registros de otra familia incluso si la aplicacion cometiera un error. Los repositorios tambien exigen `familiaId` en las consultas operativas.

## Entidades

| Entidad | Tabla | Proposito | Relaciones principales |
|---|---|---|---|
| `Familia` | `familias` | Tenant y datos generales | Raiz de todos los datos funcionales |
| `Usuario` | `usuarios` | Padres y tutores | Pertenece a una familia; email globalmente unico |
| `Menor` | `menores` | Perfil supervisado | Pertenece a una familia |
| `Dispositivo` | `dispositivos` | Equipo Windows o Android | Pertenece a una familia y opcionalmente a un menor de la misma familia |
| `Aplicacion` | `aplicaciones` | Programa detectado | Pertenece a un dispositivo de la misma familia |
| `UsoAplicacion` | `usos_aplicacion` | Intervalo de uso registrado | Familia, menor, dispositivo y aplicacion |
| `LimiteTiempo` | `limites_tiempo` | Minutos permitidos por periodo | Menor y aplicacion de la misma familia |
| `SitioBloqueado` | `sitios_bloqueados` | Dominio restringido | Menor de la misma familia |
| `HorarioRestriccion` | `horarios_restriccion` | Franja restringida por dia | Menor de la misma familia |
| `EventoAuditoria` | `eventos_auditoria` | Trazabilidad administrativa | Familia; usuario y dispositivo opcionales |

`BaseEntity` aporta `id`, `activo`, `creado_en` y `actualizado_en`. Los eventos agregan `ocurrido_en`; las aplicaciones, `detectada_en`; y los dispositivos, `ultima_conexion`.

## Relaciones

```text
Familia
├── Usuario
├── Menor
│   ├── Dispositivo
│   │   ├── Aplicacion
│   │   └── UsoAplicacion
│   ├── LimiteTiempo ── Aplicacion
│   ├── SitioBloqueado
│   └── HorarioRestriccion
└── EventoAuditoria ── Usuario / Dispositivo
```

`UsoAplicacion` se vincula al `Menor`, que es el perfil supervisado. `Usuario` se reserva para padres o tutores con credenciales administrativas.

## Integridad e indices

- No hay eliminacion en cascada. Las claves foraneas usan `ON DELETE RESTRICT` para evitar perdida accidental de historial.
- Email, identificador de dispositivo y reglas que no deben duplicarse tienen restricciones `UNIQUE`.
- La ruta ejecutable admite 700 caracteres. Este limite permite indexarla de forma unica junto con familia y dispositivo sin superar los 3072 bytes de una clave InnoDB con `utf8mb4`.
- Los valores enumerados y rangos basicos tienen restricciones `CHECK` compatibles con MySQL 8.
- Los intervalos de uso deben terminar despues de comenzar; limites y duraciones no pueden ser negativos.
- Los indices comienzan por `familia_id` y cubren consultas por dispositivo, menor, fecha, dia y estado activo.
- `password_hash` exige al menos 50 caracteres y el backend expone un `PasswordEncoder` BCrypt con coste 12. El texto original nunca debe asignarse a ese campo.

## Migraciones

La migracion inicial esta en:

`familyguard-backend/src/main/resources/db/migration/V1__crear_modelo_inicial.sql`

Reglas para migraciones futuras:

1. Crear un archivo nuevo `V<numero>__<descripcion>.sql`; nunca editar una migracion aplicada.
2. No usar `DROP`, `TRUNCATE` ni `clean` en produccion. `spring.flyway.clean-disabled=true` esta configurado.
3. Hacer cambios compatibles hacia adelante cuando existan agentes o clientes desplegados.
4. Probar primero con H2 y despues con una instancia MySQL 8 desechable antes de produccion.

## Limitaciones de la prueba H2

H2 en modo MySQL valida la migracion, claves, indices y mapeos JPA, pero no reproduce exactamente el motor InnoDB, colaciones `utf8mb4`, planes de ejecucion, bloqueos ni todas las reglas de MySQL. La migracion debe probarse contra MySQL 8 real en CI o en un entorno de integracion antes de desplegarse.
# Ampliacion de la Etapa 5 (Flyway V3)

`V3__deteccion_dispositivos_aplicaciones.sql` conserva el modelo anterior y agrega:

- `codigos_vinculacion`: familia, menor, hash unico, creacion, expiracion y uso;
- `procesos_detectados`: dispositivo, PID, nombre, ruta opcional, ejecucion, ventana activa
  y hora de observacion;
- en `dispositivos`: hash del token del agente, revocacion y ultima sincronizacion;
- en `aplicaciones`: tipo de instalacion, ejecucion, ventana activa, ultimo PID y ultima
  deteccion; la ruta pasa a ser opcional porque Windows no siempre la expone.

Las nuevas claves foraneas compuestas mantienen el aislamiento por `familia_id`. El token y
el codigo nunca se almacenan en claro. El esquema real queda en 13 tablas funcionales y 14
contando `flyway_schema_history`.

## Ampliacion de la Etapa 6 (Flyway V4)

La tabla existente `usos_aplicacion` recibe:

- `registro_uuid`: identificador global de la sesion, unico e idempotente;
- `fecha_local`: fecha calculada en la zona horaria familiar;
- `zona_horaria`: zona utilizada al persistir el intervalo;
- indices `(familia_id, fecha_local, aplicacion_id)` y
  `(familia_id, dispositivo_id, fecha_local)`.

Los campos son nulos para conservar compatibilidad con datos anteriores a V4. Todos los
nuevos registros del agente incluyen UUID, fecha y zona. Las claves foraneas compuestas de
V1 siguen garantizando que familia, menor, dispositivo y aplicacion pertenezcan al mismo
tenant.

## Ampliacion de la Etapa 7 (Flyway V5)

`limites_tiempo` incorpora bloqueo total, advertencia y revision;
`horarios_restriccion` incorpora aplicacion, tipo `BLOQUEAR`/`PERMITIR` y revision; y
`eventos_auditoria.evento_uuid` evita duplicar reintentos del agente.

## Ampliación de la Etapa 8 (Flyway V6)

V6 conserva `sitios_bloqueados` y agrega categoría, modo, vigencia y revisión. Crea:

- `sitios_dispositivos`: asignación explícita y fecha de sincronización;
- `horarios_sitios`: días, intervalos y reglas `BLOQUEAR`/`PERMITIR`;
- `categorias_sitios` y `dominios_categoria`: catálogo versionable con fuente;
- `eventos_sitios`: metadatos mínimos e idempotencia por UUID.

El esquema MySQL real contiene 19 tablas contando `flyway_schema_history`.

## Ampliación de la Etapa 9 (Flyway V7)

V7 amplía `dispositivos` con usuario y versión del agente, heartbeat e intervalo, último
error, revisiones deseada/aplicada, desvinculación y versión de credencial. Amplía los códigos
con contadores de seguridad y crea:

- `confirmaciones_sincronizacion`: UUID único, dispositivo/familia, revisión y resultado;
- `intentos_vinculacion`: huella SHA-256, contador y expiración del bloqueo, sin conservar el
  identificador original.

MySQL 8.4.3 real aplicó V7. El esquema contiene 21 tablas contando
`flyway_schema_history`, 34 claves foráneas y siete migraciones exitosas.

