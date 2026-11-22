# FamilyGuard - Progreso del proyecto

Fecha de revision: 15 de noviembre de 2026 (America/Lima)

## Estado de la etapa 6: monitoreo automatico del tiempo de uso

El agente reutiliza la deteccion nativa de la Etapa 5 y observa exclusivamente el PID de la
ventana activa cada dos segundos. Abre una sesion al comenzar el uso, la cierra al cambiar
de aplicacion, bloquear Windows, detener el agente, detectar una pausa/suspension superior a
diez segundos o cruzar la medianoche local. Solo se contabiliza la aplicacion en primer
plano; los procesos abiertos en segundo plano no suman tiempo.

Las sesiones terminadas reciben un UUID y se guardan en un diario local cifrado con Windows
DPAPI. El estado abierto se actualiza periodicamente para recuperarse tras un reinicio. Al
volver la conexion, el agente envia lotes de hasta 200 registros autenticados y elimina solo
los UUID confirmados. El backend valida duracion/fechas, aplica unicidad por UUID y asocia
familia, hijo, dispositivo y aplicacion desde el token del agente, nunca desde el JSON.

Flyway V4 (`V4__monitoreo_tiempo_uso.sql`) se aplico en MySQL Community 8.4.3 real. Reutiliza
`usos_aplicacion` y agrega `registro_uuid`, `fecha_local`, `zona_horaria`, restriccion unica e
indices para reportes por familia, fecha, aplicacion y dispositivo. No se modificaron V1-V3
ni se eliminaron datos. El esquema conserva 14 tablas contando el historial.

El dashboard muestra el tiempo real de hoy y los reportes diario, semanal, mensual, ranking
por aplicacion y resumen por hijo/dispositivo. Los filtros incluyen fechas, hijo,
dispositivo y aplicacion. No existen estadisticas ni listas de aplicaciones inventadas.

Validaciones completadas:

- `mvn clean verify`: `BUILD SUCCESS` con 64 pruebas (43 backend, 3 administrador y 18
  agente), 0 fallos, 0 errores y 0 omitidas;
- calculo, acumulacion, cambios rapidos, bloqueo, suspension, cambio de fecha y reinicio;
- cola offline cifrada, recuperacion y confirmacion idempotente;
- persistencia, estadisticas, filtro de aplicacion y aislamiento entre familias;
- V4 aplicada en MySQL, Hibernate `validate`, Spring Boot en 8080 y health `UP`;
- observacion real de la ventana activa ejecutada en Windows sin leer titulo ni contenido.
- comprobacion manual final: Windows devolvio `chrome.exe` como aplicacion activa real.

La cuenta `familyguard_app` sigue pendiente de un secreto elegido por el usuario. La prueba
MySQL utilizo temporalmente la administracion local de Laragon sin guardar credenciales.
No se implementaron bloqueos, limites ni cierres de aplicaciones de la Etapa 7.

Documentacion detallada: `docs/MONITOREO_TIEMPO_USO.md`.

## Estado de la etapa 5: deteccion real de aplicaciones y procesos Windows

Etapa implementada sin avanzar al control de tiempos o bloqueos de la Etapa 6. El agente
Windows ahora es una aplicacion JavaFX visible, de una sola instancia y controlable por el
usuario. Lee aplicaciones instaladas desde las vistas HKCU/HKLM del Registro mediante JNA,
enumera procesos con OSHI y obtiene el PID de la ventana activa con User32. Los errores de
acceso a procesos o claves no detienen el ciclo.

Se implemento vinculacion por codigo aleatorio de ocho caracteres, un solo uso y diez
minutos; MySQL conserva su hash. El agente recibe un token independiente de 256 bits,
protegido localmente con DPAPI, y el backend guarda solo SHA-256. El padre puede revocar el
equipo y el token deja de ser valido inmediatamente. La API limita inventarios, consolida
duplicados, aplica frecuencia minima y mantiene el aislamiento por familia.

La interfaz administrativa permite generar el codigo, ver equipos conectados/desconectados,
consultar ultima comunicacion, revocar dispositivos y filtrar aplicaciones por detenida, en
ejecucion o ventana activa. No contiene aplicaciones ni telemetria simuladas.

Verificacion real realizada:

- MySQL Community 8.4.3 de Laragon iniciado en `G:\laragon`, TCP/TLS 3306;
- `familyguard_db` existente, Flyway V3 aplicada con exito y tres migraciones validas;
- 14 tablas contando `flyway_schema_history`, 26 claves foraneas y 97 entradas de indices;
- Hibernate 7.4.13 valido el esquema; Spring Boot 4.1.2 inicio en 8080 y health fue `UP`;
- deteccion nativa real de Registro, procesos y ventana activa ejecutada en Windows;
- comprobacion manual de Google Chrome y Microsoft Edge instalados y en ejecucion;
- agente JavaFX inspeccionado visualmente, visible, con vinculacion y aviso de privacidad;
- H2 permanece exclusivamente en pruebas y la ejecucion normal conserva MySQL sin fallback.

Resultado final de `mvn clean verify`: `BUILD SUCCESS` en los tres modulos, 48 pruebas
exitosas (38 backend, 3 administrador y 7 agente), 0 fallos, 0 errores y 0 omitidas. Las
pruebas de agente se ejecutaron realmente sobre Windows e incluyen Registro, procesos,
ventana activa, duplicados, caida/recuperacion de red, cache sin conexion e instancia unica.

La cuenta dedicada `familyguard_app` continua pendiente porque no se proporciono una
contrasena elegida por el usuario; no se invento ni persistio ningun secreto. La validacion
MySQL se hizo temporalmente con la cuenta administrativa local de Laragon. El procedimiento
de minimo privilegio permanece documentado en `docs/CONFIGURACION_MYSQL.md`.

Documentacion: `docs/AGENTE_WINDOWS.md`, `docs/ARQUITECTURA.md` y
`docs/CONFIGURACION_MYSQL.md`.

## Estado de la etapa 4: dashboard profesional JavaFX

Se implemento un dashboard de escritorio oscuro con menu lateral navegable: Inicio, Mis
hijos, Dispositivos, Aplicaciones, Reportes, Alertas, Mi perfil, Configuracion y Cerrar
sesion. No contiene botones decorativos: cada opcion cambia a una vista funcional o ejecuta
el cierre de sesion.

El backend agrega endpoints de solo lectura para resumen, dispositivos, aplicaciones,
reportes y alertas. Todos derivan `familiaId` del principal autenticado. No fue necesaria una
migracion V3: V1 y V2 ya contienen las tablas requeridas y no fueron modificadas.

Funciones verificadas:

- tarjetas con hijos, dispositivos, aplicaciones, uso del dia, restricciones y eventos;
- CRUD de hijos integrado y consulta de sus dispositivos;
- tablas de dispositivos y aplicaciones con filtros reales;
- reportes diario, semanal, mensual, ranking y resumen mediante JavaFX Charts;
- eventos de auditoria reutilizados como alertas, sin generar datos ficticios;
- perfil seguro de solo lectura y configuracion local de apariencia/densidad/API;
- expiracion de sesion, errores HTTP, servidor no disponible, indicadores de carga y estados
  vacios;
- limite de 366 dias para reportes y 100 eventos recientes para alertas.

Las pruebas automatizadas son ahora 32: 29 en backend y 3 en JavaFX. Las 13 nuevas cubren
familia vacia, hijos existentes, dispositivos, aplicaciones, reportes con y sin datos,
aislamiento, acceso anonimo, sesion expirada, rango de fechas, navegacion y error de red.
`mvn clean verify` termino con `BUILD SUCCESS` el 15 de noviembre de 2026 a las 06:50
(America/Lima); compilaron backend, administrador y agente. Un arranque adicional contra
MySQL Community 8.4.3 valido Flyway V1/V2, Hibernate y los nuevos repositorios, e inicio
Tomcat correctamente en el puerto aleatorio 51823.

Comprobacion visual: se inicio la aplicacion JavaFX real y se inspeccionaron login y registro,
incluido el cambio de vista, campos, scroll y tema oscuro. El dashboard autenticado no se
declara inspeccionado visualmente porque la cuenta MySQL `familyguard_app` sigue pendiente y
no se crearon usuarios o estadisticas ficticias para eludir esa limitacion.

Documentacion detallada: `docs/DASHBOARD_JAVAFX.md`.

## Estado de la etapa 3: autenticacion, usuarios y seguridad

Se implementaron el registro transaccional de padre y familia, login con Spring Security,
sesiones opacas revocables, cierre de sesion, control de intentos fallidos y gestion de
perfiles de hijos. Las rutas privadas obtienen siempre `familiaId` de la identidad
autenticada; ninguna operacion confia en un identificador de familia enviado por el cliente.

La aplicacion JavaFX ya contiene pantallas de login y registro, mostrar/ocultar contrasena,
panel familiar y alta, consulta, edicion y desactivacion de hijos. El cliente HTTP usa
timeouts, llamadas asincronas y conserva el token solamente en memoria. Su URL se configura
con `FAMILYGUARD_API_URL`.

Seguridad implementada:

- BCrypt con coste 12; nunca se devuelven entidades ni hashes.
- Token aleatorio de 256 bits; en MySQL solo se guarda su SHA-256.
- Sesiones de 8 horas y revocacion inmediata al cerrar sesion.
- Bloqueo durante 15 minutos tras 5 intentos fallidos.
- Respuestas genericas para credenciales invalidas.
- Roles operativos `PADRE`, `TUTOR` y `ADMIN_SISTEMA`; el registro publico siempre crea
  `PADRE` y rechaza campos inesperados como `rol`.
- `PADRE` y `TUTOR` pueden administrar solo su familia; `ADMIN_SISTEMA` no recibe acceso
  implicito a datos familiares.

Flyway V2 (`V2__autenticacion_y_sesiones.sql`) se aplico correctamente en MySQL Community
8.4.3 real. Agrega estado de intentos/bloqueo a `usuarios` y la tabla
`sesiones_autenticacion`, con hash unico, expiracion, indices y claves foraneas familiares.
El esquema tiene ahora 11 tablas funcionales y 12 contando `flyway_schema_history`.
Hibernate valido el esquema y Spring Boot arranco completamente en un puerto aleatorio.

Pruebas actuales: 19 exitosas (13 escenarios REST/seguridad y las 6 comprobaciones
anteriores), sin fallos ni errores. Incluyen registro, duplicados, BCrypt, login valido e
invalido, acceso anonimo, sesiones, hijos, cierre de sesion, aislamiento entre familias,
roles no autorizados, bloqueo por intentos y validacion. `mvn clean verify` termino con
`BUILD SUCCESS` el 15 de noviembre de 2026 a las 06:34 (America/Lima); los tres modulos
compilaron correctamente.

Pendiente externo obligatorio: la instancia no contiene todavia la cuenta
`familyguard_app`, porque no se proporciono un secreto elegido por el usuario. La migracion
y el arranque real se verificaron temporalmente con la cuenta administrativa local de
Laragon, sin persistirla. Debe completarse el paso interactivo descrito en
`docs/CONFIGURACION_MYSQL.md`; hasta entonces no se declara lista la ejecucion habitual con
minimo privilegio.

La documentacion funcional completa esta en `docs/AUTENTICACION_USUARIOS.md`.

## Historial: estado final de la etapa 2

FamilyGuard conserva sus tres modulos Maven y ahora dispone de un modelo de persistencia completo en `familyguard-backend`. El esquema se administra con Flyway, Hibernate solo lo valida y las pruebas usan H2 de forma aislada. No se modificaron las aplicaciones JavaFX ni el agente Windows.

La configuracion previa a la etapa 3 quedo validada contra una instancia real MySQL Community 8.4.3 de Laragon. `familyguard_db` existe, Flyway V1 esta aplicada y Spring Boot completo su arranque usando MySQL.

Componentes existentes:

- `familyguard-backend`: Spring Boot 4.1.2, API futura, Spring Security, JPA, Flyway y MySQL.
- `familyguard-admin`: base JavaFX 21.0.13 sin cambios funcionales en esta etapa.
- `familyguard-agent`: base del agente Windows sin monitoreo, bloqueos ni servicios ocultos.

## Modelo implementado

Se implementaron las entidades `Familia`, `Usuario`, `Menor`, `Dispositivo`, `Aplicacion`, `UsoAplicacion`, `LimiteTiempo`, `SitioBloqueado`, `HorarioRestriccion` y `EventoAuditoria`.

Todas heredan identificador, estado activo y fechas de auditoria desde `BaseEntity`. Las relaciones usan carga diferida y validaciones Jakarta. Los estados y tipos se modelaron como enumeraciones legibles en la base.

El aislamiento entre familias se aplica en dos niveles:

1. Los repositorios operativos reciben `familiaId` en sus consultas.
2. Las claves foraneas compuestas incluyen `familia_id`, por lo que la base rechaza relaciones entre registros de familias distintas.

El detalle se encuentra en `docs/MODELO_DATOS.md`.

## Base de datos y migraciones

- Base productiva prevista: MySQL 8.x, esquema `familyguard_db`.
- Migracion disponible: `V1__crear_modelo_inicial.sql`.
- Flyway: 12.4.0, administrado por Spring Boot 4.1.2.
- Modulos: `spring-boot-starter-flyway` y `flyway-mysql`.
- `spring.jpa.hibernate.ddl-auto=validate`: Hibernate nunca crea ni actualiza el esquema.
- `spring.flyway.clean-disabled=true`: se deshabilita la limpieza destructiva.
- H2 2.3.232: fijado solo para pruebas porque es la version maxima que Flyway 12.4.0 declara como verificada.

Verificacion MySQL real:

- Instalacion: Laragon en `G:\laragon`, sin servicio de Windows.
- MySQL: 8.4.3, puerto 3306; X Protocol en 33060.
- Base: `familyguard_db`, `utf8mb4`, `utf8mb4_0900_ai_ci`.
- TLS negociado: `TLS_AES_128_GCM_SHA256`.
- V1 aplicada con exito y registrada en `flyway_schema_history`.
- 10 tablas de dominio (11 contando el historial), 20 claves foraneas y 37 indices.

La migracion crea diez tablas, relaciones, restricciones `CHECK`/`UNIQUE`, claves foraneas e indices orientados a consultas por familia, dispositivo, menor y fechas. No contiene `DROP`, `TRUNCATE` ni operaciones sobre datos existentes.

## Repositorios y consultas

Se agregaron diez repositorios Spring Data JPA. Incluyen consultas para:

- dispositivos activos de una familia;
- aplicaciones instaladas por dispositivo y familia;
- sesiones y suma del uso diario por dispositivo;
- limites por menor, aplicacion y periodo;
- sitios bloqueados de un menor;
- horarios por menor y dia;
- usuarios, menores y eventos de auditoria siempre dentro de su familia.

## Seguridad preparada

- `Usuario.passwordHash` esta marcado como sensible y no se serializa como JSON.
- Se configuro BCrypt con coste 12 mediante `PasswordEncoder`.
- La migracion exige hashes de al menos 50 caracteres.
- No existen contrasenas reales en el repositorio ni se registran credenciales.
- Spring Security aun utiliza su usuario temporal de desarrollo; la autenticacion real corresponde a la etapa 3.

## Pruebas automatizadas

Pruebas implementadas:

- arranque completo del backend con Tomcat en puerto aleatorio;
- aplicacion y validacion de la migracion Flyway V1;
- validacion del esquema mediante Hibernate;
- persistencia de todas las relaciones principales;
- consultas de dispositivos, aplicaciones, uso diario, limites, sitios y horarios;
- aislamiento de resultados entre dos familias;
- rechazo por clave foranea de una relacion entre familias;
- generacion y verificacion de hashes BCrypt.

Resultado final de `./mvnw.cmd -B clean verify`: `BUILD SUCCESS` en el POM raiz y los tres modulos. Se compilaron 29 fuentes backend y 2 fuentes de prueba con Java 17. Se ejecutaron 6 pruebas: 0 fallos, 0 errores y 0 omitidas. Flyway aplico V1, Hibernate valido el esquema, Tomcat arranco en un puerto aleatorio y el intento intencional de relacion entre familias fue rechazado por `fk_dispositivos_menor`.

Comprobacion adicional con MySQL real: Hikari obtuvo una conexion MySQL, Flyway valido/aplico V1, Hibernate identifico `MySQLDialect` y la version 8.4.3, el `EntityManagerFactory` se inicializo y Tomcat arranco completamente en el puerto aleatorio 53842. Una ejecucion deliberada sin credenciales termino con codigo 1 durante la conexion MySQL; no se produjo fallback a H2.

## Configuracion MySQL

Las instrucciones completas y el inventario detectado estan en `docs/CONFIGURACION_MYSQL.md`.

Las variables requeridas son:

- `FAMILYGUARD_DB_URL`
- `FAMILYGUARD_DB_USERNAME`
- `FAMILYGUARD_DB_PASSWORD`

Ejemplo de preparacion del esquema con una cuenta administrativa de MySQL; sustituya usuario, host y secreto segun su entorno:

```sql
CREATE DATABASE familyguard_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER 'familyguard_app'@'localhost' IDENTIFIED BY '<secreto-seguro>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON familyguard_db.* TO 'familyguard_app'@'localhost';
```

Variables para PowerShell:

```powershell
$env:FAMILYGUARD_DB_URL = 'jdbc:mysql://localhost:3306/familyguard_db?useSSL=true&requireSSL=true&serverTimezone=UTC'
$env:FAMILYGUARD_DB_USERNAME = 'familyguard_app'
$env:FAMILYGUARD_DB_PASSWORD = '<secreto-seguro>'
.\mvnw.cmd -pl familyguard-backend spring-boot:run
```

En desarrollo local sin TLS configurado puede ser necesario adaptar la URL. Produccion debe exigir una conexion TLS validada y una cuenta con privilegios minimos. `config/.env.example` solo documenta nombres y no se carga automaticamente.

## Limitaciones y pendientes

- H2 en modo MySQL no reproduce InnoDB, colaciones, planes de ejecucion, bloqueos ni todas las diferencias SQL de MySQL. Se requiere una prueba MySQL 8 desechable en CI antes de produccion.
- Maven mostro avisos HTTP 401 al consultar metadatos opcionales de repositorios declarados por Flyway, pero todos los artefactos se resolvieron desde Maven Central y la compilacion termino correctamente.
- La instancia Laragon permite administracion local sin una credencial de aplicacion dedicada. La validacion se hizo temporalmente con esa cuenta local, sin persistirla. El usuario debe elegir localmente un secreto para `familyguard_app`, crear la cuenta siguiendo `docs/CONFIGURACION_MYSQL.md` y definir las tres variables de entorno antes de la ejecucion habitual.
- MySQL se detendra si Laragon se cierra; no existe un servicio automatico de Windows para esta instancia.
- La relacion `UsoAplicacion` identifica al perfil supervisado mediante `Menor`; `Usuario` representa padres y tutores.
- No se implementaron API REST, autenticacion, autorizacion, recuperacion de contrasena ni manejo global de errores; corresponden a etapas posteriores.
- No se implementaron monitoreo, bloqueo, sincronizacion, interfaz visual ni instalador.
- Los eventos de auditoria estan modelados, pero su generacion automatica se agregara con los casos de uso administrativos.

## Archivos creados en la etapa 2

- `docs/MODELO_DATOS.md`.
- `docs/CONFIGURACION_MYSQL.md`.
- `familyguard-backend/src/main/resources/db/migration/V1__crear_modelo_inicial.sql`.
- `familyguard-backend/src/main/java/.../config/PasswordHashingConfiguration.java`.
- `familyguard-backend/src/main/java/.../domain/model/`: `BaseEntity`, diez entidades y seis enumeraciones.
- `familyguard-backend/src/main/java/.../domain/repository/`: diez repositorios.
- `familyguard-backend/src/test/java/.../PersistenceIntegrationTests.java`.

## Archivos modificados en la etapa 2

- `familyguard-backend/pom.xml`: Flyway, modulo MySQL y H2 de prueba verificado.
- `familyguard-backend/src/main/resources/application.properties`: MySQL `familyguard_db`, Flyway, UTC y validacion Hibernate.
- `familyguard-backend/src/test/resources/application-test.properties`: H2 modo MySQL, Flyway y validacion Hibernate.
- `config/.env.example`: nombre correcto de la base y TLS.
- `docs/ARQUITECTURA.md`: capa de persistencia y aislamiento multi-tenant.
- `PROGRESO.md`: estado verificable de esta etapa.

## Ajustes de la configuracion MySQL definitiva

- `V1__crear_modelo_inicial.sql`: tablas reanudables con `IF NOT EXISTS` y ruta ejecutable ajustada al limite real de indice InnoDB/`utf8mb4`.
- `Aplicacion.java`: longitud de `rutaEjecutable` alineada a 700 caracteres con el esquema real.
- `docs/CONFIGURACION_MYSQL.md`: instalacion detectada, auditoria real, operacion con Laragon y creacion segura de la cuenta de aplicacion.
- `docs/ARQUITECTURA.md` y `docs/MODELO_DATOS.md`: resultados y limite MySQL documentados.

## Preparacion para la etapa 3

La etapa 3 puede implementar registro/invitacion de tutores, login, autorizacion por familia y gestion de usuarios sobre `UsuarioRepository` y `PasswordEncoder`. Toda operacion autenticada debe obtener `familiaId` del principal de seguridad, nunca de un parametro confiado del cliente, y registrar cambios relevantes en `EventoAuditoria`.

## Etapa 7 - control de aplicaciones Windows

- Dashboard: alta, modificación y baja de reglas desde aplicaciones reales, presets,
  bloqueo total, advertencia, horario y consulta de consumido/restante.
- Backend: CRUD seguro por familia, reglas por token de dispositivo y eventos idempotentes.
- Agente: caché DPAPI, evaluación offline, prioridad determinista, ruta/usuario verificados,
  protección de procesos críticos y bloqueo repetido tras reapertura.
- Datos: migración V5 aditiva; ninguna aplicación queda restringida por defecto.
- Documentación técnica: `docs/RESTRICCIONES_APLICACIONES.md`.

Verificación final del 16/11/2026: MySQL Community 8.4.3 de Laragon quedó activo en
`localhost:3306`; `familyguard_db` contiene 14 tablas y Flyway V1-V5 con estado exitoso.
Spring Boot arrancó sobre MySQL real, Hibernate `validate` terminó correctamente y salud
respondió `UP`. `mvn clean verify` terminó `BUILD SUCCESS` con 78 pruebas (46 backend,
3 administrador y 29 agente), sin fallos ni omitidas. Una prueba manual adicional inició
Bloc de notas, validó ruta/usuario, cerró su PID y comprobó su terminación.

## Etapa 8 — control de sitios web

- Flyway V6 amplía reglas web y crea asignaciones por dispositivo, horarios, categorías,
  catálogo de dominios y eventos idempotentes. MySQL 8.4.3 real quedó en V6 con 19 tablas.
- JavaFX incorpora «Control de sitios web» con CRUD, filtros, vigencia, horarios, estado de
  sincronización y eventos mínimos del filtro.
- El agente usa caché DPAPI y un bloque reversible de `hosts`; no intercepta HTTPS ni captura
  historial. El bloqueo cubre resolución DNS del sistema y requiere elevación administrativa.
- Las categorías automáticas quedan explícitamente no disponibles hasta incorporar una fuente
  real de clasificación; solo las reglas personalizadas son operativas.
- La prueba manual real con `example.com` fue intentada sin elevación: Windows denegó la
  escritura antes de modificar `hosts`. Se confirmó que no quedaron entradas ni temporales.
  Debe repetirse desde PowerShell como administrador para validar el bloqueo real del equipo.

Verificación automática final: `mvn clean verify` terminó `BUILD SUCCESS` con 86 pruebas
(49 backend, 3 administrador y 34 agente), 0 fallos, 0 errores y 0 omitidas. La prueba
manual elevada se mantiene separada (`WindowsManualWebFilterIT`) para no modificar el
sistema durante la suite ordinaria.

## Etapa 9 — conexión remota y dispositivos

- Flyway V7 agrega heartbeats, versiones deseada/aplicada, revocación, confirmaciones
  idempotentes y limitación persistente sin guardar códigos o identificadores en claro.
- El dashboard incorpora «Dispositivos conectados» con búsqueda, filtros, metadatos,
  presencia real, sincronización, código temporal y desvinculación confirmada.
- El agente envía heartbeat, confirma revisiones, exige HTTPS fuera de loopback, conserva
  reglas/colas cifradas y recupera la comunicación con retroceso exponencial.
- El perfil `remote` queda enlazado a loopback por defecto para operar detrás de un proxy
  con certificado válido. MySQL permanece privado.

Validación real del 16/11/2026: Laragon MySQL Community 8.4.3 en `127.0.0.1:3306`,
`familyguard_db` migró de V6 a V7 sin limpieza y quedó con 21 tablas. Hibernate
`ddl-auto=validate` terminó correctamente y `/actuator/health` respondió `UP`.

La prueba manual elevada de Etapa 8 **no** se registra como exitosa: Windows denegó el
intento disponible antes de modificar `hosts`. Aunque el enunciado pedía anotar `BUILD
SUCCESS`, no existe evidencia de una ejecución elevada aprobada y debe repetirse desde una
terminal administradora. Tampoco se dispuso de servidor HTTPS autorizado ni dos redes; la
conexión por Internet queda preparada, no verificada. No se avanzó a la Etapa 10.

Verificación automática final de Etapa 9: `mvn clean verify` terminó `BUILD SUCCESS` con
92 pruebas (53 backend, 3 administrador y 36 agente), 0 fallos, 0 errores y 0 omitidas. La
integración local adicional levantó un backend HTTP real en puerto aleatorio y usó el
`AgentApiClient` del módulo Windows para completar vinculación, autenticación, heartbeat,
inventario y confirmación de revisión.

## Renovación visual JavaFX

Antes de generar el ejecutable se renovó exclusivamente `familyguard-admin`: login y
registro divididos, branding propio, dashboard con mayor jerarquía, sidebar agrupado,
tarjetas, tablas responsivas, chips de estado, formularios, diálogos, empty states y
transiciones suaves. No se modificó la lógica del backend ni se añadieron datos simulados.
La interfaz fue abierta e inspeccionada visualmente en Windows a 1220×780; se corrigieron
los recortes detectados en el titular y la composición del login. No se avanzó a Etapa 10.

## Rediseño visual nativo final

- Las dos referencias de `img` se analizaron sin incorporarlas al producto ni copiar sus
  pixeles. No existen cargas de `Image` o `ImageView` en el rediseño.
- `FamilyIllustration` recrea mediante `Canvas`, curvas Bezier, degradados y sombras una
  familia de cuatro integrantes alrededor de un portatil protegido.
- `VectorIcon` aporta iconos SVG reutilizables al login, prestaciones, sidebar y tarjetas.
- Login y registro usan composicion dividida; los campos, mostrar contraseña y acciones
  siguen siendo controles JavaFX funcionales.
- Inicio presenta familia activa, avatar, seis metricas reales en 3×2 y estado de plataforma.
  El mismo sistema visual se aplica a tablas, filtros, formularios, graficos y dialogos del
  resto de pantallas sin retirar funciones de las etapas 1 a 9.
- Se generaron capturas reales en `docs/captures/login.png` y
  `docs/captures/dashboard.png`. El dashboard se conecto al backend H2 aislado del perfil
  `test`; sus valores cero son respuestas reales, no estadisticas inventadas. MySQL y Flyway
  no se modificaron.
- La ilustracion final es una version vectorial original y deliberadamente no pixel-identica;
  no se avanzo a la Etapa 10 ni se genero instalador.
- Verificacion final: `mvn clean verify` termino correctamente en los tres modulos con 92
  pruebas (53 backend, 36 agente y 3 administrador), 0 fallos, 0 errores y 0 omitidas.

## Modo claro premium

- Se incorporo `ThemeManager` como unico punto de aplicacion, cambio en vivo y persistencia
  local de `Claro`/`Oscuro`; no almacena contraseñas ni informacion de la cuenta.
- El tema puede elegirse desde el login y desde Configuracion. Escenas y dialogos registrados
  se actualizan inmediatamente, y la preferencia reaparece al abrir otro proceso JVM.
- `familyguard-light.css` cubre login, registro, sidebar, encabezado, dashboard, tarjetas,
  tablas, filtros, formularios, emergentes, fechas, combos, checks, pestañas, graficos,
  progreso, badges, errores y estados vacios. El CSS oscuro original permanece disponible.
- La ilustracion Canvas se redibuja con colores claros u oscuros; los iconos SVG se adaptan
  por CSS. No se agregaron imagenes estaticas ni datos simulados.
- Se revisaron capturas reales a 1220×780, ventana 1366×768 y ventana 1920×1080, ademas de
  Configuracion, tabla vacia y retorno en vivo al modo oscuro. La decoracion de Windows
  explica la diferencia entre tamaño de ventana y area interna de las dos ultimas capturas.
- Evidencias: `login-light.png`, `dashboard-light.png`, `dashboard-light-1920x1080.png`,
  `settings-light.png`, `table-light.png` y `theme-switch-dark.png` en `docs/captures`.
- MySQL, Flyway, backend y funciones de control parental no se modificaron. No se avanzo a
  la Etapa 10 ni se genero instalador.
- `mvn clean verify`: `BUILD SUCCESS` con 94 pruebas (53 backend, 36 agente y 5
  administrador), sin fallos, errores u omisiones.

## Modo claro multicolor premium

- Se sustituyeron las superficies predominantemente blancas por fondos y paneles azulados,
  celestes, turquesa, lavanda y menta, manteniendo contraste azul profundo.
- Login y registro usan un hero azul-celeste-turquesa, Canvas multicolor, prestaciones azul,
  morada, verde y coral, tarjeta de acceso azul/lavanda e inputs azulados.
- Las seis metricas tienen gradientes propios: turquesa, azul cielo, morado, ambar, verde y
  coral. Sus numeros siguen procediendo del backend aislado y no se simularon estadisticas.
- Sidebar, encabezado, familia activa, plataforma, paneles, filtros, tablas, pestañas,
  graficos y dialogos comparten ahora la paleta multicolor sin utilizar rojo decorativo para
  controles normales.
- Las capturas claras fueron regeneradas y el retorno en vivo al modo oscuro se verifico sin
  cambios en su apariencia. Backend, MySQL, Flyway y control parental permanecen intactos.

## Refinamiento del acceso premium

- La ventana inicial es adaptable al area visual de Windows y escala hasta 1500×880.
- Login agrega logo FG centrado, encabezado equilibrado, placeholders finales, ojo nativo
  para contraseña, iconos SVG en acciones y halos decorativos construidos por CSS JavaFX.
- Registro comparte la misma identidad y fue compactado de forma especifica para mantener
  visibles campos, confirmacion, accion y regreso sin recortes ni superposiciones.
- `FamilyIllustration` continua siendo Canvas nativo; no se incorporaron capturas, fondos
  bitmap ni datos falsos. La autenticacion y navegacion conservan su comportamiento.
- Capturas reales actualizadas: `docs/captures/login-light.png` y
  `docs/captures/registration-light.png`.

## Integracion de la familia local

- Se localizo y verifico `img/ico.png`; el archivo contiene la familia correcta y tambien
  elementos ambientales que no debian duplicarse.
- Se genero de forma no destructiva `images/family-cutout.png` con transparencia real,
  conservando las cuatro personas y la laptop. El archivo original permanece intacto.
- `FamilyIllustration` mantiene el Canvas para fondo, escudo, planta y lampara, mientras un
  `ImageView` de classpath muestra el recorte con proporcion preservada y suavizado.
- Se revisaron capturas reales en claro y oscuro: no existen bordes rectangulares, deformacion,
  recortes de personas ni superposicion sobre identidad o tarjetas.
- No se modificaron backend, autenticacion, MySQL, Flyway, dashboard ni control parental.

## Etapa 10 — Instaladores Windows

- Generados en `dist` los instaladores 1.0.0 independientes de Administrador y Agente con
  runtime Java 17, recursos JavaFX, icono multirresolución, Inicio, escritorio y desinstalador.
- Verificados jpackage 17.0.12 y WiX 3.14.1.8722 portable. El proceso reproducible está en
  `scripts/installer/build-installers.ps1`.
- El Administrador incorpora primera configuración local/remota y actualización de API. El
  Agente conserva vinculación visible, DPAPI, AppData e inicio automático opcional por usuario.
- `mvn clean verify`: 94 pruebas correctas. Instalación, apertura instalada, accesos y
  desinstalación de ambos paquetes: código 0.
- Pendiente externo: VM limpia, firma Authenticode, backend HTTPS público, prueba de
  vinculación/heartbeat y restauración elevada de `hosts`. No se declaran como verificadas.

## Mejora integral de escritorio — 17/11/2026

- Mi perfil permite editar nombre/correo con contraseña actual, valida duplicados y ofrece
  cambio de contraseña con revocación de todas las sesiones. Cerrar sesión está disponible
  en perfil y sidebar; salir de la aplicación es una acción diferente.
- Mis hijos muestra perfiles activos e inactivos, permite crear, editar, desactivar y
  reactivar sin borrar datos asociados.
- Aplicaciones utiliza pestañas para separar inventario y restricciones, búsqueda por nombre
  o ejecutable, filtros «Todos», tooltips y acciones diferenciadas para configurar, editar,
  desbloquear, reactivar y eliminar una política conservando la auditoría.
- El agente incorpora aplicaciones MSIX/UWP publicadas en Inicio y usa el título visible para
  nombrar hosts genéricos como ApplicationFrameHost. Estos hosts permanecen protegidos: no se
  cierran procesos compartidos de Windows cuando no existe una ruta ejecutable segura.
- Fechas, meses y días de semana usan español de Perú. La ventana del agente ahora tiene
  desplazamiento adaptable para evitar recortes con escalado de Windows.
- No se modificaron las cuatro líneas protegidas del datasource, las migraciones ni los datos
  MySQL. `mvn clean verify`: 99 pruebas, 0 fallos, 0 errores y 0 omitidas.

## Reestructuración UI/UX y paginación — 18/11/2026

- Se incorporó paginación funcional reutilizable (10, 25, 50 y 100 filas, total, página
  actual, primera, anterior, siguiente y última) en hijos, dispositivos, inventario de
  aplicaciones, restricciones, reglas web, eventos web, detalle de reportes y alertas.
  Los filtros reinician la página y la ordenación se aplica al conjunto completo antes de
  extraer cada página.
- Mis hijos utiliza un diseño maestro-detalle: lista compacta a la izquierda y pestañas de
  información, dispositivos, restricciones y actividad a la derecha. Ya no apila tablas.
- Aplicaciones separa inventario y políticas en pestañas, conserva acciones y muestra el
  estado de sincronización calculado desde las revisiones reales del dispositivo.
- Reportes dispone de filtros adaptables, cuatro resúmenes, gráficos diario/semanal/mensual/
  por aplicación y detalle paginado en una pestaña independiente. No se simulan datos.
- Mi perfil usa dos columnas; `Cerrar sesión` y `Salir de FamilyGuard` permanecen visibles
  y separados en el pie de la navegación. Cerrar sesión sigue limpiando la sesión real.
- Se revisaron capturas de las pantallas modificadas en 1366×768, 1600×900 y 1920×1080,
  además de escalas JavaFX forzadas de 125 % y 150 %. Los toolbars adaptables evitan acciones
  truncadas, el perfil fluye con desplazamiento cuando falta altura y el paginador permanece visible.
- `mvn clean verify`: 102 pruebas correctas (56 backend, 38 agente y 8 administrador), sin
  fallos, errores u omisiones. Se añadieron regresiones de límites, tamaño, filtrado y orden.
- El instalador del Administrador fue regenerado, instalado, iniciado y desinstalado con
  código 0. El Agente no cambió y su instalador se conservó. No se alteraron migraciones,
  datos MySQL ni las cuatro propiedades protegidas del datasource.

## Inicio automático y supervisión remota — 18/11/2026

- La vinculación ofrece autorización explícita para HKCU Run y guarda esa preferencia junto
  con la identidad protegida por DPAPI. Al abrirse, el agente reanuda la supervisión sin el
  antiguo clic diario en «Iniciar detección» y corrige la entrada de autoarranque instalada.
- Continúan activos el bloqueo de instancia duplicada, heartbeat, descarga de reglas, cachés
  offline y colas UUID. El reintento exponencial queda acotado a cinco minutos y los errores
  técnicos se registran localmente sin secretos.
- Una credencial DPAPI ilegible ya no crea una vinculación falsa; una revocación 401/403 se
  diferencia de un corte de Internet y solo entonces se ofrece renovar el código.
- Dispositivos conectados muestra hijo, equipo, sistema, agente, conexión real, supervisión,
  última conexión, última sincronización, revisiones, pendiente y error. Nunca deduce que una
  laptop está apagada solo por faltar heartbeat.
- La confirmación inicial de revisión 0 registra la última sincronización. No se añadieron
  migraciones ni se modificaron las propiedades MySQL protegidas.
- `mvn clean verify`: 108 pruebas correctas (56 backend, 44 agente y 8 administrador), sin
  fallos, errores u omisiones.

## Conexión remota y varios equipos — Etapas 2 a 4 (19/11/2026)

- Problemas N.º 1 a 3 corregidos (revinculación de equipos revocados, sincronización de uso con
  validación por registro y estado real del bloqueo web por regla y equipo, migración V8).
- Etapa 2: vinculación rápida con código de 8 caracteres, anulación definitiva del código anterior,
  confirmación automática en el Administrador y límite de intentos por IP. Sin migraciones.
- Etapa 3: panel «Mis dispositivos» con selección múltiple (individual, todos los filtrados, por
  hijo y toda la familia), filtros, paginación y actualización automática cada 30 s. Un equipo
  recién vinculado ya no figura conectado hasta su primer heartbeat real. Sin migraciones.
- Etapa 4: restricciones masivas (bloquear/retirar sitios web, limitar/retirar aplicaciones,
  consultar resultados y cancelar bloqueos pendientes) con vista previa, confirmación,
  idempotencia por identificador de operación y estados verificables por equipo. Migración
  aditiva V9 autorizada por el usuario; detalle en `docs/RESTRICCIONES_MASIVAS.md`.
- V9 solo se aplicó en las pruebas H2. `familyguard_db` la recibirá al arrancar el backend
  contra MySQL; conviene respaldar la base antes. No se modificaron V1–V8, las propiedades
  MySQL protegidas, git ni los instaladores. El Agente no cambió en la Etapa 4.
- Corrección V10 (autorizada): los horarios de restricciones pasan a ser únicos por aplicación, de modo que
  el mismo horario puede configurarse en varias computadoras de un hijo sin duplicarse. Sin cambios de datos.
- Etapa 5: avisos en tiempo real por SSE (`/api/agent/events`, credencial en cabecera, aislamiento por equipo,
  aviso tras el commit, límites y limpieza de conexiones). El Agente adelanta la descarga al recibir
  `POLICY_CHANGED`, agrupa avisos y reintenta con espera exponencial sin afectar la supervisión local. Sin
  migraciones. El Administrador conserva la actualización cada 30 s. El Agente cambió: su instalador deberá
  regenerarse cuando se autorice.
- Etapa 6: pruebas integrales (Agente real contra backend real con SSE) y revisión de seguridad con barrido de
  aislamiento entre familias. Hallazgo corregido: faltaba auditar reglas web, revocaciones y operaciones
  masivas. Riesgos pendientes documentados en `docs/REVISION_SEGURIDAD.md` (perfil remoto, proxy, bloqueo por
  IP, instalación por equipo, PIN del padre). Sin migraciones.
- Etapa 7: plan de despliegue HTTPS e instaladores en `docs/DESPLIEGUE_HTTPS.md`, sin desplegar ni generar nada.
  Corregido: el perfil remoto exige credenciales MySQL por variables de entorno (antes heredaba root sin
  contraseña) y `start-backend.ps1` usa el perfil `remote`. Administrador con dirección oficial empaquetada.
  `build-installers.ps1` admite `-ServerUrl`, `-PerMachineAgent` y `-SignThumbprint`. Plantillas en
  `scripts/deploy/` (Nginx con SSE y límites por IP, systemd, variables, usuarios MySQL sin root).
- Problemas N.º 4 y N.º 5 corregidos en el Agente: las tareas periódicas y el canal en tiempo real ya no se
  detienen en silencio ante un `Error` (se registran, se reanudan y se vigilan entre sí), y los cachés cifrados
  dañados se apartan sin borrarse para que el Agente arranque y vuelva a descargar las reglas. El padre ve ambos
  avisos en el detalle del equipo. Sin cambios en el backend ni migraciones. Instaladores no regenerados.
- Administrador: cuenta, hijos y dispositivos son pasos independientes. Botón «Agregar hijo», menú «Mis
  dispositivos» y estados vacíos discretos («Todavía no has agregado hijos») sin formularios automáticos. Prueba
  de que una cuenta nueva empieza sin hijos, dispositivos ni datos de ejemplo. Sin cambios en el backend.

- Entrega final: permisos temporales con caducidad automática (tiempo extra hasta medianoche; fuera de horario,
  desbloqueo y sitio permitido hasta la hora autorizada) con reloj de confianza y presupuesto monotónico en el
  Agente; avisos al hijo a los 10/5/1 min y 30 s de margen; PIN parental (verificador PBKDF2 en el Agente, cifrado
  AES-GCM en el servidor, «Ver PIN» 30 s auditado, bloqueos por intentos); pausas y desinstalación autorizadas con
  mecanismos normales de Windows; avisos SIN_CONEXION/RECONECTADO. Migraciones V11 y V12 en `db/pendiente`:
  solo pruebas, perfil `dev` (`familyguard_dev`) y `remote`; `familyguard_db` no se modifica. Detalle y plan de
  pruebas manuales en `docs/PERMISOS_TEMPORALES_Y_PIN.md`. Instaladores 1.1.0 por generar con el script.
