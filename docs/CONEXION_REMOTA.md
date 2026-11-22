# Conexión remota de dispositivos

## Arquitectura y alcance

FamilyGuard usa un modelo de salida: el agente Windows inicia solicitudes HTTPS al backend.
No escucha puertos, no necesita IP pública y MySQL nunca debe publicarse en Internet. Esta
etapa no incluye escritorio remoto, captura de pantalla, cámara, micrófono ni registro de
teclas.

```text
Dashboard JavaFX ----HTTPS----> proxy TLS ----HTTP loopback----> Spring Boot
Agente Windows ------HTTPS--------^                                  |
                                                                  red privada
                                                                     |
                                                                 MySQL 8
```

En local se admite `http://localhost:8080`. El agente rechaza HTTP para cualquier host que
no sea `localhost`, `127.0.0.1` o `::1` y no desactiva la validación TLS. En Internet se
necesita un dominio y certificado público válido en un proxy como Caddy, nginx o Apache.
`application-remote.properties` enlaza Spring Boot a `127.0.0.1` por defecto para que solo
el proxy del servidor pueda alcanzarlo.

## Configuración

Desarrollo local:

```powershell
$env:FAMILYGUARD_DB_URL = 'jdbc:mysql://127.0.0.1:3306/familyguard_db?serverTimezone=UTC'
$env:FAMILYGUARD_DB_USERNAME = 'familyguard_app'
$env:FAMILYGUARD_DB_PASSWORD = '<secreto-local>'
.\mvnw.cmd -pl familyguard-backend spring-boot:run
$env:FAMILYGUARD_API_URL = 'http://localhost:8080'
```

Servidor remoto detrás del proxy HTTPS:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'remote'
$env:FAMILYGUARD_BIND_ADDRESS = '127.0.0.1'
$env:FAMILYGUARD_SERVER_PORT = '8080'
$env:FAMILYGUARD_DB_URL = 'jdbc:mysql://mysql-privado:3306/familyguard_db?sslMode=VERIFY_IDENTITY&serverTimezone=UTC'
$env:FAMILYGUARD_DB_USERNAME = 'familyguard_app'
$env:FAMILYGUARD_DB_PASSWORD = '<secreto-del-gestor-de-secretos>'
.\mvnw.cmd -pl familyguard-backend spring-boot:run
```

El proxy publica exclusivamente HTTPS/443, redirige HTTP, conserva `X-Forwarded-Proto` y
reenvía a loopback. MySQL se permite solo desde la red privada del backend. Los secretos se
inyectan en el entorno o un gestor de secretos y no se versionan.

## Vinculación y credenciales

El padre genera un código aleatorio de ocho caracteres con diez minutos de vigencia. Solo se
guarda su SHA-256, es de un uso y pertenece a una familia/perfil. Cinco fallos por
identificador bloquean durante quince minutos; la huella del identificador también se guarda
como hash. Cada dispositivo recibe un token individual; el servidor conserva solo su hash y
el agente lo protege con DPAPI.

Desvincular elimina el hash, marca el equipo `REVOCADO` e invalida heartbeat, descargas y
confirmaciones. Como política segura offline, el agente conserva las últimas reglas y colas
cifradas; para volver a conectarse necesita una vinculación nueva.

## Vinculación rápida (Etapa 2, 19/11/2026)

- **Administrador → «Agregar dispositivo»:** se elige al hijo y se genera un código de 8 caracteres
  con su hora de vencimiento (10 minutos). «Generar otro código» anula el código pendiente anterior de
  ese hijo. La ventana consulta cada 3 s `GET /api/device-link-codes/{codigoId}` y muestra
  «Dispositivo vinculado correctamente: <equipo>» sin que el padre pulse nada. Estados: `PENDIENTE`,
  `VINCULADO`, `VENCIDO`, `REEMPLAZADO`.
- **Agente:** la pantalla principal solo pide el código (acepta minúsculas, espacios y guiones) y el
  botón «Vincular dispositivo». La dirección del servidor está en «Opciones avanzadas».
- **Dirección oficial:** `familyguard-agent/src/main/resources/com/familyguard/agent/server.properties`
  (`familyguard.server.url`). Está **vacía** porque aún no existe un servidor público autorizado; mientras
  tanto se usa la configuración local (`http://localhost:8080`). Prioridad: `FAMILYGUARD_API_URL`,
  `-Dfamilyguard.api.url`, `server.properties`, valor local. HTTP remoto siempre se rechaza.
- **Mensajes claros:** código incorrecto, vencido, ya utilizado, reemplazado, demasiados intentos y
  falta de conexión se distinguen sin detalles técnicos.
- **Intentos excesivos:** además de 5 fallos por identificador del equipo, se cuentan 20 fallos por
  dirección IP del cliente (en hash, tabla `intentos_vinculacion` existente). La IP proviene de la
  conexión o, con el perfil `remote`, del proxy de confianza; nunca del cuerpo de la petición. Las
  direcciones de bucle local no se limitan para no bloquear el desarrollo.
- **Auditoría:** cada vinculación o revinculación registra `DISPOSITIVO_VINCULADO` con el equipo y el
  código usado. No se añadieron migraciones.
- **Anulación definitiva:** un código reemplazado se marca en la columna existente `bloqueado_en` y la
  búsqueda de vinculación exige `usado_en`, `bloqueado_en` nulos y `expira_en` posterior al instante actual.
  No depende del reloj: acortar el vencimiento a «creación + 1 ms» dejaba el código válido dentro del mismo
  salto del reloj de Windows (~15 ms). Se respeta `ck_codigos_expiracion` (`expira_en > creado_en`).

## Panel de múltiples dispositivos (Etapa 3, 19/11/2026)

`GET /api/devices/panel` y `GET /api/devices/{id}/detail` (autenticados, solo PADRE/TUTOR) devuelven cada
equipo de la familia con conexión, revisión aplicada/deseada, restricciones de aplicaciones
(aplicadas/pendientes/con error) y reglas web por estado. Se calculan con consultas agregadas sobre tablas
existentes (`limites_tiempo`, `aplicaciones`, `confirmaciones_sincronizacion`, `sitios_dispositivos`,
`sitios_bloqueados`), filtradas por la familia de la sesión. No se añadieron migraciones. Los equipos de
otra familia responden 404. Detalle de uso en `DASHBOARD_JAVAFX.md`.

## Avisos en tiempo real (Etapa 5, 19/11/2026)

**Arquitectura.** `GET /api/agent/events` abre un canal Server-Sent Events (SSE) con `SseEmitter` de Spring MVC,
sin dependencias nuevas ni migraciones. La credencial va en la cabecera `X-Agent-Token` (nunca en la URL) y se
valida igual que el resto de la API del Agente. Cada conexión pertenece a un único dispositivo.

- Al conectarse, el Agente recibe `POLICY_CHANGED` con la revisión deseada vigente: así recupera los cambios
  ocurridos mientras estaba desconectado.
- Cuando cambia la revisión deseada de un equipo (restricción de aplicación, regla web, operación masiva,
  retiro), un listener JPA (`DispositivoCambiosListener`) avisa **después del commit** solo a ese equipo. Cada
  equipo de una operación masiva recibe su propio aviso. Un heartbeat u otra actualización sin cambio de
  revisión no genera avisos, y un aviso repetido no se reenvía a la misma conexión.
- Datos del evento: `{"tipo":"POLICY_CHANGED","dispositivoId":N,"revision":R}`; sin tokens, contraseñas, PIN
  ni datos personales.
- Revocar el equipo o renovar su credencial (revinculación) cierra sus conexiones; la credencial antigua
  recibe 401 al reconectar.
- Recursos: máximo 2 conexiones por equipo (la más antigua se cierra) y 5000 en total (503 con `Retry-After`);
  caducidad de 10 min con reconexión automática; señal de mantenimiento cada 25 s que libera conexiones
  abandonadas. Cabeceras `Cache-Control: no-store` y `X-Accel-Buffering: no` para proxy inverso HTTPS.

**Agente.** `AgentRealtimeSync` mantiene el canal en un hilo propio. Un aviso de su equipo con una revisión aún
no aplicada adelanta el ciclo normal (descarga autenticada, aplicación y confirmación de la revisión
realmente aplicada). El aviso nunca marca nada como aplicado. `SyncCoalescer` agrupa avisos consecutivos en una
sola repetición del ciclo. Avisos de otro equipo o con formato inválido se ignoran y se registran.

**Sin Internet.** La supervisión local (cada 2 s) y las reglas guardadas siguen activas. El canal reintenta con
espera exponencial de 2 s a 5 min y vuelve a 2 s tras reconectar; una conexión sin datos durante 90 s se cierra
y se reabre. La consulta periódica y el heartbeat siguen funcionando aunque SSE falle. SSE no garantiza la
entrega: la revisión enviada al conectar y la consulta periódica recuperan cualquier aviso perdido.

**Administrador.** Se mantiene la actualización periódica de «Mis dispositivos» cada 30 s. Añadir SSE al
Administrador exigiría otro canal autenticado con el token de sesión del padre (que caduca y se renueva) y
gestionar reconexiones en JavaFX; el beneficio es menor que el riesgo en esta etapa. La actualización periódica
conserva la selección, los filtros y la página.

## Heartbeat, sincronización y recuperación

El heartbeat incluye versión, usuario local, error reciente, intervalo configurable
(15–3600 segundos) y revisión aplicada. Se considera conectado solo mientras su antigüedad
no supere el mayor valor entre 45 segundos y tres intervalos.

Cada cambio de restricciones incrementa `revision_deseada`. El agente descarga reglas, las
guarda cifradas, intenta aplicarlas y confirma mediante UUID una revisión `APLICADA` o
`FALLIDA`. No se duplican confirmaciones y se rechazan revisiones superiores a la autorizada.
Sin conexión mantiene reglas y eventos; reintenta con retroceso exponencial hasta cinco
minutos. La revisión del servidor es la autoridad para resolver conflictos.

Al iniciar sesión en Windows, HKCU Run abre el agente visible. Este carga la misma identidad
DPAPI, inicia automáticamente el muestreo, envía heartbeat, descarga políticas y vacía colas.
La interfaz del padre muestra por separado última conexión, última sincronización, revisión
aplicada/deseada y error. Un heartbeat vencido significa `Sin conexión`, no `Laptop apagada`.

## Datos y endpoints

Se transmiten identificación del equipo, Windows/agente, usuario local, inventario/procesos
necesarios, intervalos de uso, heartbeat, reglas y resultados técnicos. No se transmiten
contraseñas, pantalla, teclas, audio, vídeo ni historial de navegación.

- `POST /api/children/{menorId}/device-link-codes`: código del padre/tutor.
- `POST /api/agent/link`: vinculación del agente.
- `POST /api/agent/heartbeat`: presencia y revisión.
- `GET /api/agent/rules`, `GET /api/agent/web-rules`: reglas autorizadas.
- `POST /api/agent/sync-confirmations`: confirmación idempotente.
- `GET /api/devices`, `DELETE /api/devices/{id}`: consulta y revocación familiar.

Administración usa Bearer y rol padre/tutor; el agente usa `X-Agent-Token`. La familia se
deriva siempre de la credencial validada, no de un dato confiado del cliente.

## Verificación y límites

El 16/11/2026 se verificó localmente vinculación, heartbeat, inventario, reglas,
confirmaciones y recuperación tras HTTP 503. Flyway V7 se aplicó en MySQL Community 8.4.3
real y Spring Boot respondió `UP`.

`mvn clean verify` finalizó con 92 pruebas exitosas: 53 backend, 3 administrador y 36
agente. La prueba de integración local usó el `AgentApiClient` real del módulo Windows
contra un servidor en puerto aleatorio y completó vinculación, token, heartbeat, inventario
y confirmación.

No se proporcionó un servidor HTTPS autorizado ni dos redes independientes. La conexión
por Internet queda preparada arquitectónicamente, pero **no verificada por Internet**. Antes
de producción deben probarse DNS, certificado, proxy, firewall, copias y rotación de secretos.
# Instaladores y backend remoto (Etapa 10)

Administrador y Agente nunca conectan a MySQL: consumen la API. `localhost` se ofrece solo
como opción local explícita. Cualquier host externo exige HTTPS y validación normal del
certificado. El backend se distribuye aparte en `dist/backend`; MySQL debe permanecer en red
privada y el perfil `remote` debe exponerse mediante proxy TLS.

