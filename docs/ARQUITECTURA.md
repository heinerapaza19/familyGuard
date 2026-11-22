# Arquitectura de FamilyGuard

FamilyGuard se organiza como un reactor Maven con tres componentes independientes y una configuracion comun en el POM raiz.

## Componentes

- `familyguard-backend`: backend Spring Boot con API REST autenticada, seguridad y persistencia MySQL. Conserva el paquete original `com.familyguard.familyguard` para no romper referencias.
- `familyguard-admin`: aplicacion JavaFX para padres con registro, login, panel familiar y gestion de hijos mediante un cliente HTTP asincrono.
- `familyguard-agent`: aplicacion JavaFX visible y controlable para Windows. Detecta aplicaciones instaladas y procesos en ejecucion, identifica la ventana activa y sincroniza el inventario autorizado mediante la API REST.

## Limites previstos

La interfaz y el agente se comunican con el backend mediante HTTP local o HTTPS configurado.
El backend es el unico componente con acceso directo a MySQL. El agente conserva la ultima
instantanea local para continuar detectando sin conexion.

El agente usa JNA para leer de forma no destructiva el Registro y consultar
`GetForegroundWindow`/`GetWindowThreadProcessId`; OSHI enumera procesos y rutas accesibles.
No captura teclas, pantalla, audio, mensajes, documentos ni historial de navegacion. La
deteccion local continua si el backend no responde, pero el estado se presenta como
desconectado y nunca se simula una sincronizacion correcta.

## Autenticacion y autorizacion

El backend expone endpoints publicos solo para registro y login. El resto atraviesa
Spring Security en modo stateless. Al autenticar, el servidor genera 32 bytes aleatorios y
devuelve su representacion URL-safe al cliente; la base conserva unicamente SHA-256 del
token. El cliente JavaFX mantiene el token en memoria y lo elimina al cerrar sesion.

`UsuarioPrincipal` contiene usuario, rol y familia. Los servicios familiares construyen
sus consultas con el `familiaId` de ese principal. El filtro Bearer nunca acepta una familia
de headers, URL o JSON. La autorizacion se aplica en backend para `PADRE` y `TUTOR`; las
rutas tecnicas `/api/admin/**` requieren `ADMIN_SISTEMA`.

El registro de familia/usuario y la autenticacion pertenecen a servicios transaccionales.
Controladores y cliente intercambian DTO; las entidades JPA, hashes y secretos nunca forman
parte de la API. Los errores de validacion tienen una forma JSON comun.

## Persistencia y aislamiento

El backend contiene el modelo JPA en `domain.model` y los accesos en `domain.repository`. Flyway aplica migraciones versionadas antes de que Hibernate valide el mapeo. Hibernate no crea ni actualiza tablas.

`familias` es la raiz de aislamiento. Cada tabla funcional lleva `familia_id`, los repositorios consultan dentro de esa familia y las claves foraneas compuestas impiden asociaciones entre tenants. El backend es el unico componente que accede a MySQL; administrador y agente consumiran la futura API autenticada.

El detalle de entidades, relaciones, indices y migraciones se encuentra en `docs/MODELO_DATOS.md`.

La instancia local comprobada es MySQL Community 8.4.3 administrada por Laragon en el puerto 3306. La configuracion operativa, creacion de la cuenta dedicada y resultados de la migracion real se documentan en `docs/CONFIGURACION_MYSQL.md`.

## Deteccion y vinculacion del agente Windows

Un padre o tutor genera un codigo aleatorio de ocho caracteres para un menor. El backend
guarda solo SHA-256, lo limita a diez minutos y lo invalida al primer uso. Al vincularse, el
agente recibe un token independiente de 256 bits; MySQL conserva solamente su hash. La
revocacion elimina el hash y bloquea inmediatamente nuevas sincronizaciones.

`V3__deteccion_dispositivos_aplicaciones.sql` agrega codigos de vinculacion, procesos
detectados y estado de ejecucion/sincronizacion. Cada relacion mantiene `familia_id` en sus
claves foraneas. El inventario se limita a 500 aplicaciones y 1000 procesos, consolida
rutas/PID duplicados y aplica un intervalo minimo de diez segundos por dispositivo.

El token local se protege con Windows DPAPI y la configuracion vive en el perfil del usuario.
El agente tiene una unica instancia mediante bloqueo de archivo, frecuencia configurable
entre 15 y 3600 segundos y botones explicitos para iniciar y detener. No crea servicios,
tareas programadas, reglas de firewall ni persistencia oculta. El flujo completo se detalla
en `docs/AGENTE_WINDOWS.md`.

## Medicion de tiempo de uso

`WindowsActivityDetector` resuelve el PID de la ventana activa sin consultar su titulo o
contenido. `UsageTracker` implementa una maquina de estados: aplicacion activa, cambio,
bloqueo/ausencia, pausa larga, medianoche y cierre ordenado. Una pausa de mas de diez
segundos corta en la ultima observacion confirmada, evitando contar suspension o tiempo sin
ejecucion del agente.

`UsageJournal` cifra con DPAPI la sesion abierta y los eventos pendientes. Cada evento tiene
UUID, inicio, fin y duracion. `POST /api/agent/usage` autentica el dispositivo, valida el
intervalo y persiste de forma idempotente. `usos_aplicacion` sigue siendo la fuente unica de
reportes; V4 agrega sus campos de sincronizacion sin crear una tabla paralela.

## Configuracion y secretos

El backend toma URL, usuario y contrasena de MySQL desde variables de entorno. `config/.env.example` documenta los nombres sin contener secretos reales. El perfil `test` utiliza una base H2 efimera y nunca modifica una base MySQL. Las contrasenas se almacenan exclusivamente como BCrypt. La migracion V2 crea sesiones y datos de proteccion contra intentos repetidos; Hibernate permanece en `validate`.

## Instalacion

No existe un instalador en esta etapa. La futura construccion para Windows se ubicara en `scripts/installer` y debera ser explicita, firmada y compatible con Windows 10 y 11.

## Dashboard y consultas de lectura

La Etapa 4 agrega una capa `DashboardService` entre los controladores REST y los repositorios.
Esta capa calcula el resumen familiar, expone dispositivos y aplicaciones, agrega usos por
periodo y presenta eventos de auditoria como alertas. Todas las consultas reciben la familia
desde `UsuarioPrincipal`; los filtros de hijo o dispositivo solo reducen resultados dentro
de esa familia y nunca conceden acceso.

Los reportes aceptan como maximo 366 dias. El backend entrega una serie diaria, ranking de
aplicaciones y resumen por hijo/dispositivo. JavaFX deriva las agrupaciones semanal y mensual
de esa serie real, sin nuevas consultas ni valores simulados. La API limita alertas a los 100
eventos mas recientes.

`DashboardWorkspace` es el shell JavaFX principal. Separa navegacion, contenido y cliente
HTTP, utiliza controles nativos (`TableView`, `TabPane`, `LineChart`, `BarChart`, formularios
y estados vacios) y actualiza la escena mediante `Platform.runLater`. `NavigationModel` hace
la navegacion verificable sin iniciar el toolkit grafico en las pruebas.

## Motor de restricciones de la Etapa 7

`RestriccionService` autoriza CRUD por familia y genera reglas para el token del agente.
`RestrictionEngine` combina consumo del servidor y uso local; `RestrictionStore` protege
reglas/eventos con DPAPI; el controlador Windows valida ruta, PID, usuario y proceso crítico.
