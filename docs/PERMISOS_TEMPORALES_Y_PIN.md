# Permisos temporales, PIN parental, pausas, desinstalación y avisos (entrega final)

## 1. Qué puede hacer el padre

| Acción (Administrador → «Permisos y PIN») | Efecto en el equipo del hijo | Cuándo termina |
|---|---|---|
| **Tiempo extra hoy** (15–120 min por vez, máximo 4 h por día y aplicación) | Suma minutos al límite diario de la aplicación | Al terminar el día (medianoche en la zona horaria de la familia) |
| **Usar fuera de horario** | Ignora los horarios de la aplicación (no el bloqueo total ni el límite diario) | A la hora autorizada (15 min a 4 h) |
| **Desbloquear aplicación** | Quita el bloqueo total, los horarios y el límite de la aplicación | A la hora autorizada |
| **Permitir un sitio web bloqueado** | Retira ese dominio del bloque de `hosts` | A la hora autorizada |

Un permiso se puede dar a una o varias computadoras del mismo hijo. Si una computadora no tiene la aplicación,
se informa como «No se aplicó en…». La tabla muestra el estado real por equipo: *Activo* (confirmado por el
Agente), *Enviando al equipo*, *Pendiente (equipo sin conexión)*, *Error*, *Terminado* o *Cancelado*.
«Cancelar permiso» lo retira en todos los equipos; «Ver por equipo» permite cancelarlo en uno solo.

Otras acciones:
- **PIN parental**: configurar/cambiar (pide la contraseña), **Ver PIN** (pide la contraseña, se muestra 30 s y
  queda registrado). Tras 5 contraseñas incorrectas seguidas, «Ver/Cambiar PIN» se bloquea 15 minutos.
- **Detalle del dispositivo → «Autorizar pausa…»** (15 min a 4 h) y **«Autorizar desinstalación»** (válida 1 hora).
- **Alertas**: aparecen *SIN_CONEXION* (más de 5 minutos sin contacto, un solo aviso por desconexión),
  *RECONECTADO*, *PERMISO_TEMPORAL_FINALIZADO/ERROR*, *PIN_INCORRECTO*, *PIN_BLOQUEADO*, *PAUSA_LOCAL*,
  *PAUSA_INICIADA*, *DESINSTALACION_LOCAL/INICIADA* y las acciones administrativas.

## 2. Reglas de caducidad (prioridad absoluta)

- El servidor fija la hora de fin absoluta. El Agente solo recibe permisos vigentes y no cancelados.
- **Reloj de confianza del Agente**: hora = máximo entre el reloj de Windows, la hora de inicio del Agente más el
  tiempo monotónico, la hora del servidor más el tiempo monotónico y la última hora guardada. Atrasar la hora
  de Windows no prolonga nada; adelantarla solo acorta.
- Si al arrancar la hora de Windows está más de 2 minutos por detrás de la última guardada, **ningún permiso
  vale** hasta recibir la hora del servidor (se informa *ERROR* al padre). Restricciones normales mientras tanto.
- Cada permiso tiene además un **presupuesto monotónico** guardado cifrado (DPAPI): reiniciar no lo renueva.
- **Sin Internet** se usa la última lista descargada, pero cada permiso vence a su hora. Una cancelación del padre
  llega en cuanto el equipo se conecta (aviso SSE o ciclo de 30 s).
- **Caché dañado o error de cálculo**: sin permisos (restricciones normales) y aviso al padre.
- Avisos al hijo (notificación de Windows): a los 10, 5 y 1 minuto del fin del tiempo diario o de un permiso; al
  bloquearse una aplicación que estaba en uso, **30 segundos de margen** antes de cerrarla.

## 3. PIN parental en el equipo del hijo

Se pide para: pausar la supervisión, cerrar la ventana del Agente (sin PIN se minimiza), cambiar el servidor,
quitar el inicio automático y «Opciones del padre → Desinstalar». El equipo guarda solo el verificador PBKDF2
(120 000 iteraciones); el PIN en claro solo existe cifrado con AES-GCM en el servidor. 5 PIN incorrectos → bloqueo
de 5 min (10, 20, 40, máx. 60 en los siguientes); el bloqueo sobrevive a reinicios. Si el equipo no tiene el PIN
guardado lo consulta al servidor; **sin conexión, la acción no se permite**. Si el padre nunca configuró un PIN, se
pide confirmación.

La desinstalación usa los mecanismos normales de Windows: el asistente quita el inicio automático, retira el
bloque de `hosts` (puede pedir UAC) y abre «Aplicaciones instaladas». FamilyGuard no impide desinstalar desde
Windows ni oculta procesos; si alguien cierra el Agente con el Administrador de tareas, el padre recibe
*SIN_CONEXION*.

## 4. Base de datos

| Migración | Contenido | Dónde se aplica |
|---|---|---|
| V11 `db/pendiente/V11__permisos_temporales.sql` | 3 claves únicas compuestas aditivas, `permisos_temporales`, `permisos_temporales_dispositivos` (FKs compuestas por familia, hijo, equipo, aplicación y regla web; CHECKs de tipo, minutos, ventana, objetivo único y revocación) | Pruebas (H2), perfil `dev` (`familyguard_dev`) y perfil `remote` |
| V12 `db/pendiente/V12__pin_parental_y_autorizaciones.sql` | `pines_parentales`, `autorizaciones_dispositivo` | Igual que V11 |

`familyguard_db` **no** recibe V11 ni V12: su perfil sigue con `spring.flyway.locations=classpath:db/migration`.
Mientras no se autorice, el backend no arranca contra `familyguard_db` (Hibernate valida que faltan las tablas
nuevas) y la base no se modifica. Para autorizarlo más adelante: respaldar la base y añadir
`,classpath:db/pendiente` a esa línea (no es una de las líneas protegidas).

## 5. API

Padre (sesión): `POST/GET /api/temporary-permissions`, `GET /api/temporary-permissions/{id}`,
`POST /api/temporary-permissions/{id}/revoke[?dispositivoId=]`, `GET/PUT /api/parental-pin`,
`POST /api/parental-pin/reveal`, `POST/GET /api/device-authorizations`, `POST /api/device-authorizations/{id}/cancel`.

Agente (`X-Agent-Token`): `GET /api/agent/permissions`, `POST /api/agent/permission-events`,
`GET /api/agent/parental-pin`, `GET /api/agent/authorizations`, `POST /api/agent/authorizations/{id}/use`,
`POST /api/agent/local-events`.

Clave del PIN: `familyguard.pin.clave` / `FAMILYGUARD_PIN_KEY` (obligatoria en el perfil `remote`); en `dev` se
crea `%USERPROFILE%\.familyguard\pin-servidor-dev.key`. Nunca en archivos versionados.

## 6. Plan de pruebas manuales en Windows (base aislada)

Preparación: MySQL local encendido. Desde la carpeta del proyecto:
`.\mvnw.cmd clean verify` → `.\scripts\backend\start-backend-dev.ps1` (crea `familyguard_dev`). Abrir el
Administrador (`.\mvnw.cmd -pl familyguard-admin javafx:run`) y el Agente en el equipo del hijo (o el mismo equipo
con otra cuenta de Windows). Servidor: `http://127.0.0.1:8080`.

| # | Paso | Resultado esperado |
|---|---|---|
| 1 | Registrar cuenta, entrar | Panel vacío, sin hijos de ejemplo |
| 2 | «Mis hijos» → «Agregar hijo»; «Agregar dispositivo» → código; vincular el Agente | El equipo aparece en «Mis dispositivos»; primero sin conexión y luego conectado |
| 3 | «Permisos y PIN» → Configurar PIN (contraseña + PIN 4826) | «PIN parental activo (versión 1)» |
| 4 | «Ver PIN» con la contraseña | Se ve 30 s y desaparece; en «Alertas» aparece PIN_PARENTAL_CONSULTADO |
| 5 | En «Aplicaciones», limitar Bloc de notas a 15 min con advertencia | Con 10, 5 y 1 min restantes aparecen notificaciones; al llegar a 0 aviso «se cerrará en 30 segundos» y luego se cierra |
| 6 | «Dar permiso temporal» → Tiempo extra 15 min | Estado *Activo* en el equipo; el Bloc de notas vuelve a abrirse 15 min más |
| 7 | Horario BLOQUEAR ahora + «Usar fuera de horario» 15 min | Abre durante 15 min; aviso a 10/5/1; al terminar, 30 s y se cierra |
| 8 | Reiniciar el equipo del hijo durante el permiso | Continúa con el tiempo restante, no se renueva |
| 9 | Desconectar Internet del equipo del hijo y esperar el fin del permiso | Se cierra a la hora prevista sin conexión |
| 10 | Con el Agente cerrado, atrasar la hora de Windows 1 h y abrirlo sin Internet | «Permisos temporales: En pausa…»; restricciones normales; al reconectar se corrige |
| 11 | Cancelar un permiso activo | El equipo lo retira en segundos (SSE) o en ≤30 s |
| 12 | Bloquear youtube.com y dar «Permitir sitio» 15 min | El sitio abre durante 15 min y vuelve a bloquearse |
| 13 | En el Agente: «Pausar (PIN del padre)» con PIN incorrecto 5 veces | Bloqueo de 5 min; alerta PIN_BLOQUEADO en el Administrador |
| 14 | Pausar 15 min con el PIN correcto | Aplicaciones y sitios sin bloqueo; se reanuda solo; alerta PAUSA_LOCAL |
| 15 | Cerrar la ventana del Agente (X) sin PIN | La ventana se minimiza; con PIN se cierra y el padre recibe SIN_CONEXION a los 5 min |
| 16 | Detalle del dispositivo → «Autorizar pausa…» 15 min | El Agente entra en pausa al sincronizar; alerta PAUSA_INICIADA |
| 17 | «Autorizar desinstalación» → en el Agente aparece «Desinstalar (autorizado por el padre)» | Se quita el inicio automático, se retira el bloque de hosts y se abre «Aplicaciones instaladas» |
| 18 | Apagar el equipo del hijo 6 minutos y encenderlo | Una alerta SIN_CONEXION y luego una RECONECTADO |
| 19 | Comprobar que `familyguard_db` no cambió | `SHOW TABLES` en `familyguard_db` no muestra `permisos_temporales` ni `pines_parentales` |
