# Monitoreo automatico del tiempo de uso

## Alcance

La Etapa 6 mide cuanto tiempo permanece en primer plano una aplicacion Windows. Chrome o
Edge se registran como navegador; no se inspeccionan YouTube, TikTok, Facebook ni otras
paginas. No hay listas fijas, bloqueos, limites ni cierres automaticos.

## Medicion

El agente obtiene el PID de `GetForegroundWindow` con `GetWindowThreadProcessId` y consulta
nombre/ruta mediante `ProcessHandle`. Muestrea cada dos segundos y mantiene una sola sesion
activa. Un cambio de ejecutable cierra el intervalo anterior. Multiples procesos con la
misma ruta se consolidan como la misma aplicacion mientras sigue activa.

No se contabiliza:

- procesos abiertos en segundo plano;
- tiempo con Windows bloqueado o sin ventana activa;
- pausas entre observaciones mayores de diez segundos, que representan suspension,
  hibernacion, cierre o retraso excesivo;
- intervalos de duracion cero.

Al cruzar la medianoche local, la sesion se divide. El backend conserva inicio, fin,
duracion, fecha y zona horaria.

## Funcionamiento sin conexion

`UsageJournal` guarda la sesion abierta y la cola de intervalos en
`%USERPROFILE%\.familyguard-agent\usage-journal.bin`. El archivo completo se cifra con
Windows DPAPI para el usuario actual y se reemplaza atomicamente. Tras un reinicio se
recupera la ultima observacion; si la pausa fue larga, se cierra en el ultimo instante
confirmado para no sumar tiempo apagado.

Cada intervalo tiene UUID. El agente envia lotes y conserva lo no confirmado. MySQL tiene
una restriccion unica sobre ese UUID, así que reintentar una solicitud no duplica tiempo.

## API y validacion

`POST /api/agent/usage` recibe como maximo 200 intervalos con UUID, aplicacion, ruta
opcional, PID, inicio, fin y duracion. El servidor:

1. autentica `X-Agent-Token`;
2. obtiene familia, hijo y dispositivo de ese token;
3. rechaza dispositivos sin hijo, fechas futuras e intervalos inconsistentes;
4. busca/crea la aplicacion dentro del dispositivo autorizado;
5. guarda `UsoAplicacion` y devuelve los UUID confirmados.

El padre consulta `/api/dashboard` y `/api/reports/usage`. El reporte acepta `desde`,
`hasta`, `menorId`, `dispositivoId` y `aplicacionId`, siempre subordinados a la familia de
la sesion Bearer.

## Dashboard

Inicio muestra el total real de hoy. Reportes presenta serie diaria, agrupaciones semanal y
mensual, ranking de aplicaciones y resumen por hijo/dispositivo. Los filtros permiten
seleccionar fecha, hijo, dispositivo y aplicacion. Un periodo sin datos muestra cero y no
genera valores ficticios.

## Pruebas y limitaciones

Se probaron calculo, acumulacion, cambio de aplicacion, bloqueo, salto de suspension,
medianoche, reinicio, cola cifrada, perdida/recuperacion de red, UUID duplicado, MySQL,
reportes y aislamiento. La precision nominal es de aproximadamente dos segundos y depende
de que el agente visible este iniciado en la sesion supervisada.

Resultado final de `mvn clean verify`: `BUILD SUCCESS`, 64 pruebas exitosas, 0 fallos, 0
errores y 0 omitidas: 43 de backend, 3 del administrador y 18 del agente. Incluye la
comprobacion nativa de aplicacion activa y los cortes parciales para actualizar el dashboard
sin esperar a un cambio de ventana.

La comprobacion manual nativa en Windows identifico `chrome.exe` como la aplicacion activa
real. La prueba uso solamente nombre de ejecutable y PID; no leyo titulo, pagina ni contenido
del navegador.

No se supervisan otras sesiones de Windows ni se intenta atravesar el escritorio seguro. El
agente no captura teclas, pantalla, audio, mensajes, archivos, titulo de ventanas o contenido
web. La configuracion de restricciones corresponde exclusivamente a la Etapa 7.
