# Agente Windows de FamilyGuard

## Alcance de la Etapa 5

El agente es una aplicacion JavaFX visible para Windows 10/11. Su unica funcion en esta
etapa es inventariar aplicaciones instaladas, procesos en ejecucion y la aplicacion cuya
ventana esta activa, y sincronizar esos datos con el backend autorizado. No mide tiempos de
uso ni bloquea aplicaciones; esas capacidades no pertenecen a esta etapa.

## Deteccion local

- Aplicaciones: lectura de las claves `Uninstall` de HKCU, HKLM de 64 bits y HKLM de 32
  bits mediante JNA. Se leen nombre, version, editor, icono/ruta y tipo de instalacion.
- Procesos: enumeracion con OSHI y fallback a `ProcessHandle` si una consulta nativa falla.
- Ventana activa: PID obtenido con `GetForegroundWindow` y
  `GetWindowThreadProcessId` de User32.
- Accesos denegados o metadatos ausentes se omiten sin detener la siguiente deteccion.
- Rutas y PID se consolidan antes de sincronizar; no se generan datos ficticios.

El agente no registra pulsaciones, pantallas, webcam, microfono, mensajes, documentos,
contenido de ventanas ni historial web.

Librerias: OSHI 7.7.0 para procesos, JNA/JNA Platform transitiva para Registro, User32 y
DPAPI, Java `ProcessHandle` como alternativa de lectura y Jackson para los intercambios
JSON. Todo se ejecuta con los permisos del usuario actual.

Limitaciones: los programas portatiles pueden no figurar en `Uninstall`; las aplicaciones
Microsoft Store no siempre publican metadatos equivalentes en esas claves; Windows puede
ocultar ruta o detalles de procesos protegidos. Esos casos se omiten o se muestran sin ruta,
sin solicitar elevacion ni intentar superar la proteccion del sistema.

## Vinculacion segura

1. El padre selecciona un hijo en FamilyGuard Administrador y pulsa **Vincular
   computadora**.
2. El backend entrega un codigo aleatorio de un solo uso que caduca en diez minutos. La base
   conserva solamente su SHA-256.
3. El usuario escribe el codigo y la URL del backend en el agente visible.
4. El backend registra el equipo y entrega un token aleatorio independiente. El agente lo
   protege localmente con Windows DPAPI; el backend guarda solo su hash.
5. El dispositivo puede revocarse desde el administrador. La revocacion invalida el token
   inmediatamente y exige una nueva vinculacion.

## Operacion

La pantalla de estado permite iniciar/detener la deteccion y elegir una frecuencia entre 15
y 3600 segundos. Solo puede ejecutarse una instancia por usuario. Si la red o el backend
fallan, la deteccion local continua en el siguiente ciclo y la interfaz muestra **Sin
conexion**; no hay fallback a H2 ni una confirmacion falsa. La ultima instantanea se guarda
en `.familyguard-agent/inventory-cache.json` dentro del perfil del usuario y se reemplaza de
forma atomica. El token se almacena por separado y cifrado con DPAPI.

No se instala automaticamente, no se registra como servicio, no crea tareas programadas y
no solicita elevacion. Un instalador y el inicio automatico requeriran consentimiento
explicito en una etapa posterior.

## Monitoreo de tiempo (Etapa 6)

Mientras la supervision esta iniciada, el agente consulta la ventana activa cada dos
segundos. Una aplicacion abierta en segundo plano no cuenta. Al cambiar de aplicacion se
cierra el intervalo anterior y se abre el siguiente; volver a la misma aplicacion genera
otra sesion que el backend acumula en los reportes.

Si Windows se bloquea o no existe ventana activa, la sesion se cierra. Un salto entre
observaciones superior a diez segundos se trata como suspension/reinicio y no se atribuye a
ninguna aplicacion. Las sesiones se dividen en la medianoche de la zona local para mantener
estadisticas diarias correctas.

El archivo `usage-journal.bin` contiene la sesion abierta y la cola pendiente cifradas con
DPAPI. Solo se eliminan eventos que el backend confirma por UUID, por lo que una respuesta
perdida o un reintento no duplica tiempo en MySQL.

Inicio en desarrollo:

```powershell
$env:FAMILYGUARD_API_URL = 'http://localhost:8080'
.\mvnw.cmd -pl familyguard-agent javafx:run
```

## API

- `POST /api/children/{menorId}/device-link-codes`: padre/tutor autenticado.
- `POST /api/agent/link`: consume el codigo temporal.
- `POST /api/agent/inventory`: requiere `X-Agent-Token` y acepta hasta 500 aplicaciones y
  1000 procesos.
- `POST /api/agent/usage`: requiere `X-Agent-Token`, acepta hasta 200 intervalos y devuelve
  los UUID confirmados.
- `GET /api/agent/status`: comprueba el token del agente.
- `DELETE /api/devices/{id}`: revoca desde la familia autenticada.

El backend deriva siempre la familia del usuario o del token almacenado; nunca confia en un
`familiaId` enviado por el agente.

## Verificacion realizada

En Windows se comprobaron aplicaciones reales del Registro, procesos reales incluyendo el
JVM de prueba, la llamada nativa de ventana activa, ausencia de PID/rutas duplicados y
recuperacion del cliente tras una respuesta HTTP 503. La aplicacion JavaFX se inicio e
inspecciono visualmente: muestra claramente que la deteccion es autorizada, incluye
vinculacion y aviso de privacidad, y no se oculta al usuario.

Como comprobacion manual con software conocido, el equipo devolvio Google Chrome y Microsoft
Edge en el inventario instalado y procesos activos de ambos navegadores. No se supuso ni se
declaro la presencia de Roblox.

El reactor completo termino con `BUILD SUCCESS`: 48 pruebas, 0 fallos, 0 errores y 0
omitidas (38 backend, 3 administrador y 7 agente). MySQL 8.4.3 real aplico V3 y Spring Boot
arranco con salud `UP`.

## Restricciones de la Etapa 7

El agente descarga reglas, las conserva cifradas con DPAPI y las evalua cada dos segundos,
tambien sin conexion. Repite el bloqueo tras reapertura y sincroniza eventos por UUID. La
prioridad, permisos y limitaciones estan en `docs/RESTRICCIONES_APLICACIONES.md`.

## Control web de la Etapa 8

Las reglas de dominios se guardan con DPAPI y se aplican offline mediante un bloque reversible
del archivo `hosts`. El agente necesita elevación para esa escritura y muestra un error si no
la tiene. No intercepta HTTPS ni registra navegación. Cobertura y límites completos:
`docs/RESTRICCIONES_SITIOS_WEB.md`.

## Conexión remota de la Etapa 9

El agente envía heartbeat con versión, usuario local y revisión aplicada; descarga reglas,
confirma su resultado con UUID idempotente y reintenta con retroceso exponencial de hasta
cinco minutos. La interfaz visible muestra conexión y revisión aplicada. HTTP solo se acepta
para loopback; una URL externa debe usar HTTPS con validación TLS normal.

El token individual se conserva con DPAPI. Sin red permanecen activas las cachés cifradas y
las colas pendientes. Si el servidor revoca el equipo, el token deja de autenticar; las
reglas ya sincronizadas siguen aplicándose hasta una vinculación nueva. Detalles en
`docs/CONEXION_REMOTA.md`.

## Inicio y supervisión automáticos

Después de la vinculación autorizada, la supervisión comienza inmediatamente y vuelve a
iniciarse sin pulsar ningún botón cada vez que se abre el agente. La opción presentada en la
misma vinculación registra el ejecutable visible en
`HKCU\Software\Microsoft\Windows\CurrentVersion\Run` con el argumento `--autostart`. No se
instala un servicio oculto, no se eleva el agente completo y el usuario puede abrir su ventana.

`SingleInstanceLock` evita dos agentes simultáneos para el mismo usuario. La configuración
con identidad y token permanece en `%LOCALAPPDATA%\FamilyGuard\Agent\config.json`; el token
está cifrado por DPAPI. Una configuración existente ilegible no se interpreta como equipo
nuevo: se muestra el problema y solo una confirmación explícita permite renovar el vínculo.

La ventana separa `Agente ejecutándose`, `Supervisión activa` y la conexión confirmada. La
ausencia de heartbeat se informa como `Sin conexión`, porque por sí sola no demuestra que la
laptop esté apagada. Un 401/403 se presenta como autorización revocada y habilita la renovación
conservando la identidad del equipo.

Los fallos de red aplican retroceso exponencial limitado a cinco minutos. Mientras tanto se
mantienen las reglas cifradas y las colas con UUID; los diagnósticos técnicos limitados se
escriben en `agent-errors.log` sin token. No existe reinicio remoto cuando el proceso está
totalmente detenido ni un mecanismo de persistencia encubierto.
# Distribución instalada (Etapa 10)

El instalador visible incluye Java 17. La configuración, token DPAPI y colas offline se
guardan en `%LOCALAPPDATA%\FamilyGuard\Agent`. La pantalla vinculada permite probar/cambiar
la API y habilitar el arranque opcional en el inicio de sesión mediante HKCU Run. HTTP solo
se permite para loopback; un servidor externo requiere HTTPS válido.

## Identificación de aplicaciones modernas

El inventario combina desinstaladores clásicos con accesos MSIX/UWP publicados por Windows.
Cuando la ventana activa pertenece a `ApplicationFrameHost.exe` o `WWAHost.exe`, se presenta
el título legible de la aplicación si Windows lo proporciona. El host compartido sigue en la
lista de procesos críticos: FamilyGuard no afirma poder bloquearlo de forma segura sin una
ruta propia verificable y nunca termina el host genérico completo.

## Robustez del Agente: problemas N.º 4 y N.º 5 (19/11/2026)

**Problema N.º 4 — tareas que se detenían en silencio.** En Java, si una tarea periódica lanza cualquier
`Throwable` no capturado, el programador la cancela para siempre sin avisar. La supervisión local (cada 2 s) y
el ciclo de sincronización solo capturaban `RuntimeException`, y el canal en tiempo real solo `Exception`; un
`Error` (por ejemplo, de una librería nativa de Windows o de memoria) los detenía hasta reiniciar el Agente.
Ahora:
- las dos tareas y el canal en tiempo real capturan cualquier error, lo registran en `agent-errors.log` y siguen;
- las tareas se vigilan entre sí: si una quedara cancelada, la otra la vuelve a programar;
- el padre ve el aviso «La sincronización se reanudó tras un error interno» en el detalle del equipo
  (último error informado), sin tener que revisar archivos.

**Problema N.º 5 — archivos cifrados dañados impedían arrancar.** Los cachés de reglas de aplicaciones
(`restrictions.bin`), de reglas web (`web-rules.bin`) y el diario de tiempo de uso (`usage-journal.bin`) lanzaban
una excepción al abrirse si estaban dañados o cifrados por otra cuenta de Windows, y el Agente no se abría.
Ahora el archivo dañado se aparta como `<nombre>.danado-<fecha>` (nunca se borra), el Agente arranca con ese
caché vacío, vuelve a descargar las reglas del servidor en su siguiente ciclo y el padre recibe en el panel el
aviso de qué archivo se recuperó. La vinculación guardada (`agent-config.json`) ya mostraba una pantalla para
volver a vincular y no cambia.

Limitación: mientras el equipo no tenga Internet después de recuperar un caché dañado, no hay reglas locales que
aplicar; se aplican en cuanto el Agente vuelve a comunicarse. Los registros de uso que estaban en un diario
dañado no se pueden recuperar.

