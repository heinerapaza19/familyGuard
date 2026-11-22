# Restricciones masivas (Etapa 4, 19/11/2026)

Permiten aplicar o retirar una restricción en una, varias, todas las computadoras de un hijo o
todas las computadoras autorizadas de la familia, desde el panel «Mis dispositivos».

## Uso en el Administrador

1. Marque las computadoras (casillas, «Seleccionar todos», «Seleccionar equipos de un hijo» o
   «Seleccionar toda la familia»). La selección se conserva entre páginas y filtros.
2. En la barra «Con la selección:» elija:
   - **Aplicar restricción → Bloquear sitio web**: uno o varios dominios (máx. 20, uno por línea).
   - **Aplicar restricción → Limitar aplicación**: una aplicación detectada y el tiempo diario.
   - **Retirar restricción → Retirar sitio web / Retirar límite de aplicación**: solo en las
     computadoras seleccionadas; las demás conservan la regla.
   - **Consultar resultados**: historial reciente con el estado de cada equipo y la opción
     «Cancelar operación» para bloqueos de sitios aún no aplicados.
3. Antes de ejecutar se muestra la vista previa: «Vas a bloquear tiktok.com en 5 computadoras
   (3 conectadas, 2 desconectadas). ¿Deseas continuar?», con el detalle de los equipos donde no
   se aplicará o donde ya estaba aplicada.

## API (`/api/bulk-operations`, solo PADRE/TUTOR de la familia)

| Método | Ruta | Uso |
|---|---|---|
| POST | `/preview` | Vista previa sin modificar nada |
| POST | `/` | Registra la operación: 201 nueva, 200 reintento idempotente, 409 identificador reutilizado con otro contenido |
| GET | `/` | Últimas 50 operaciones con resumen |
| GET | `/{id}` | Detalle por equipo (404 si es de otra familia) |
| POST | `/{id}/cancel` | Cancela un bloqueo de sitio en los equipos que no lo aplicaron |

Tipos: `BLOQUEAR_SITIO`, `RETIRAR_SITIO` (campo `dominio`), `LIMITAR_APLICACION`
(`aplicacionId` de referencia y `minutosDiarios` 1–1440) y `RETIRAR_LIMITE` (`aplicacionId`).
Máximo 200 equipos por operación; los identificadores repetidos se ignoran.

## Seguridad y consistencia

- El backend valida cada equipo contra la familia de la sesión. Si uno no pertenece a la familia,
  la operación entera responde 404 y no se modifica nada; un equipo revocado o sin vincular
  responde 409, también sin cambios parciales.
- Las operaciones de una familia se serializan con un bloqueo de fila sobre `familias`; el
  identificador `operacionId` es único por familia (`uq_operaciones_masivas_uuid`). Un reintento
  de red con la misma solicitud devuelve el resultado guardado sin volver a modificar ni a
  incrementar revisiones.
- Bloquear solo **añade** equipos a la regla del hijo (una regla por hijo y dominio). Un equipo que
  ya tenía el bloqueo queda «Sin cambios» y conserva su estado y revisión. Una regla activa con
  horario, vigencia o modo permitir no se altera: se rechaza la operación para editarla desde
  «Sitios web».
- Retirar quita solo los equipos elegidos; si la regla se queda sin equipos, se desactiva.
- Limitar conserva los horarios y la advertencia de un límite existente y solo cambia los minutos.
  Un límite de «bloqueo total» no se reduce. Retirar usa el mismo retiro auditado que «Aplicaciones».
- Errores parciales: no existen en el registro (todo o nada). Las diferencias por equipo son
  explícitas y se ven antes de confirmar: «Sin cambios», «Aplicación no registrada» o «Ambigua».

## Identificación de aplicaciones

FamilyGuard identifica cada aplicación por la ruta del ejecutable detectada en cada equipo, y el
Agente solo cierra un proceso cuya ruta coincide exactamente. Para varias computadoras, el padre
elige una aplicación detectada de referencia y el backend busca en cada equipo una aplicación
detectada con el **mismo nombre de ejecutable**:

- exactamente una → se aplica el límite a esa aplicación de ese equipo;
- ninguna → «Aplicación no registrada»: no se crea nada. Solución: abrir la aplicación una vez en
  ese equipo (o esperar el inventario) y repetir la operación;
- varias rutas distintas → «Ambigua»: no se crea nada; configúrela desde «Aplicaciones».

Nunca se inventan rutas ni se actúa sobre procesos no verificados.

## Estados por equipo

| Estado | Evidencia real |
|---|---|
| Pendiente de entrega | Conectado (heartbeat reciente), aún no descargó la política |
| Desconectado, pendiente de sincronización | Sin heartbeat reciente ni descarga |
| Recibida por el Agente | Web: el Agente descargó la regla después de la operación. Apps: `revision_recibida` ≥ revisión objetivo |
| Aplicada | Web: evento `FILTRO_APLICADO` del Agente. Apps/retiros: confirmación `APLICADA` de la revisión objetivo |
| Error | Web: evento de error del filtro. Apps: confirmación `FALLIDA` de la revisión objetivo |
| Cancelada | Cancelada antes de aplicarse |
| Sin cambios / No registrada / Ambigua | Decidido al registrar la operación |
| Revocado / Retirada | El equipo fue revocado, o el bloqueo se retiró después |

«Aplicada en los N equipos seleccionados» solo se muestra cuando todos están Aplicada o Sin cambios.
Un equipo desconectado recibe la política al volver a comunicarse (la regla queda asignada).

## Migración V9 (aditiva, autorizada)

`dispositivos.revision_recibida` y `politica_recibida_en` (entrega verificable; se registran al
servir `/api/agent/web-rules`, último paso del ciclo de descarga del Agente), y las tablas
`operaciones_masivas` y `operaciones_masivas_dispositivos`. Sin `UPDATE`, `DELETE` ni `DROP`.

## Migración V10 (corrección autorizada)

V1 definía la unicidad de horarios por hijo, día y horas, y V5 los hizo por aplicación sin actualizarla.
Como cada computadora tiene su propio registro de aplicación, no se podía poner el mismo horario (p. ej.,
Roblox el lunes 08:00–10:00) en dos laptops del mismo hijo. V10 crea
`uq_horarios_restriccion_aplicacion (familia_id, menor_id, aplicacion_id, dia_semana, hora_inicio, hora_fin)`
y después elimina `uq_horarios_restriccion_regla`. No modifica datos. Requiere MySQL 8.0.19 o superior
(`DROP CONSTRAINT`); verificado que el equipo usa MySQL 8.4.3.

## Limitaciones conocidas

- Retirar un sitio de un equipo se verifica con la revisión que confirma el Agente para todo el
  equipo; el detalle muestra además el estado del filtro web informado por el Agente.
- Aplicaciones que cambian de carpeta al actualizarse (p. ej., Roblox en `Versions\version-…`):
  el Agente compara la ruta exacta, por lo que tras una actualización el límite puede dejar de
  reconocer el programa hasta repetir la operación. Corregirlo exige cambiar el Agente (y su
  instalador); se propone como mejora aparte.
- Solo se cancelan bloqueos de sitios; para límites se usa «Retirar restricción».
- El estado se actualiza por consulta (Etapa 5: notificaciones en tiempo real).

## Preparación para permisos temporales (no implementado)

- Cada operación es un registro independiente y el tipo es ampliable (p. ej., `PERMISO_TEMPORAL`),
  por lo que los permisos de 15 min, 30 min, 1 h o 2 h pueden reutilizar la selección, la vista
  previa, la idempotencia y los estados por equipo.
- El vencimiento debe guardarse como instante absoluto (`expira_en TIMESTAMP(6)`), nunca como
  minutos restantes. `limites_tiempo.vigente_hasta` es una fecha sin hora y no sirve para esto.
- El Agente debe guardar el permiso localmente (protegido con DPAPI) para que caduque aunque la
  laptop se reinicie o pierda Internet, y contrastar el reloj del sistema con un tiempo monotónico
  para no extender el permiso si alguien cambia la hora.
- Requerirá su propia migración (p. ej., tabla `permisos_temporales`), que se presentará para
  autorización antes de crearla.
