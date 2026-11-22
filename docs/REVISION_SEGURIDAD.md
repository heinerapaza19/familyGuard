# Etapa 6 — Pruebas integrales y revisión de seguridad (19/11/2026)

Punto de partida verificado: la Etapa 5 terminó con 237 pruebas sin fallos (backend 125, Agente 86,
Administrador 26), según los informes de Maven del 19/11/2026.

## Pruebas añadidas en esta etapa

| Prueba | Qué comprueba |
|---|---|
| `EndToEndRemoteControlHttpIntegrationTests` | Agente real contra backend real (HTTP y SSE en un puerto local): vinculación de dos laptops con credenciales distintas, operación masiva, aviso en tiempo real, descarga, aplicación y confirmación, equipo desconectado pendiente, «Todo aplicado» no mostrado con pendientes, revocación que cierra el canal y deja la credencial sin valor, auditoría sin secretos |
| `SecurityReviewIntegrationTests` | Barrido de aislamiento: otra familia intenta leer, modificar, desactivar o borrar hijos, códigos, dispositivos, reglas web, límites y operaciones masivas (19 ataques) y consultar listados con identificadores ajenos (9); todo responde 403/404 sin datos y los recursos quedan intactos. Rutas del padre sin sesión → 401; rutas del Agente sin credencial → 400/401. Acciones administrativas auditadas sin tokens ni contraseñas |

## Matriz de las 16 pruebas obligatorias (prompt maestro, sección 13)

| # | Requisito | Cobertura |
|---|---|---|
| 1 | Vinculación de una computadora | `DeviceLinkFlowApiIntegrationTests`, `AgentLinkApiIntegrationTests`, prueba de punta a punta |
| 2 | Varias computadoras | `BulkOperationsApiIntegrationTests` (10 equipos), punta a punta (2 laptops) |
| 3 | Familias distintas | `DeviceLinkFlowApiIntegrationTests`, `SecurityReviewIntegrationTests` |
| 4 | Códigos vencidos | `DeviceLinkFlowApiIntegrationTests` (`rechazaCodigoExpirado`) |
| 5 | Códigos reutilizados / anulados | `DeviceLinkFlowApiIntegrationTests` (`codigoAnuladoSeRechaza…`) |
| 6 | Restricción individual | `BulkOperationsApiIntegrationTests` (una computadora) |
| 7 | Varios equipos | `BulkOperationsApiIntegrationTests` (varias computadoras) |
| 8 | Todos los equipos de un hijo | `BulkOperationsApiIntegrationTests` |
| 9 | Toda la familia | `BulkOperationsApiIntegrationTests` |
| 10 | Equipo desconectado | `BulkOperationsApiIntegrationTests`, `AgentEventsApiIntegrationTests`, punta a punta |
| 11 | Recuperación de conexión | `AgentEventsApiIntegrationTests`, `AgentRealtimeSyncTests` |
| 12 | Descarga y confirmaciones | `WebFilterStateApiIntegrationTests`, `BulkOperationsApiIntegrationTests`, punta a punta |
| 13 | Prevención de duplicados | Reintentos idempotentes, operaciones simultáneas, avisos SSE repetidos |
| 14 | Fallos parciales | Aplicación no registrada/ambigua, errores por equipo, «Todo aplicado» solo si todo está aplicado |
| 15 | Aislamiento entre familias | `SecurityReviewIntegrationTests` (barrido completo) y pruebas de cada etapa |
| 16 | Regresión de problemas 1, 2 y 3 | `AgentRelinkApiIntegrationTests`, `AgentUsageSyncApiIntegrationTests`, `WebFilterStateApiIntegrationTests` |

## Revisión de seguridad (sección 8 del prompt maestro)

| Requisito | Estado | Evidencia |
|---|---|---|
| Identidad y credencial por equipo | Cumple | Token aleatorio por equipo, guardado como hash; prueba de punta a punta verifica credenciales distintas |
| Códigos que expiran y de un solo uso | Cumple | Etapa 2; límite de intentos por IP |
| Sin vinculación entre familias | Cumple | Códigos ligados a familia e hijo; revinculación exige la misma familia |
| Revocación y renovación segura | Cumple | Revocar invalida la credencial y cierra el canal SSE |
| Duplicados (problema 2) y bloqueo web (problema 3) | Cumple | Pruebas de regresión vigentes |
| DPAPI y HTTPS | Cumple | Credencial del Agente cifrada con DPAPI; Agente y Administrador exigen HTTPS salvo en localhost |
| Proteger todos los endpoints | Cumple | Todo autenticado salvo registro, inicio de sesión, `/actuator/health` y la API del Agente, que valida su credencial en cada ruta |
| Registrar acciones administrativas | **Corregido** | Faltaba auditar reglas web, revocación y operaciones masivas; ahora se registran |
| No filtrar contraseñas ni tokens en registros | Cumple | El backend solo registra identificadores de equipo; la auditoría se verificó sin secretos |
| Operaciones masivas limitadas a la familia | Cumple | Validación completa en el backend; barrido de aislamiento |
| Sin elevación silenciosa | Cumple | El permiso del archivo hosts se pide con UAC visible |

Otras comprobaciones: contraseñas con BCrypt (coste 12); sesiones de 8 h con token aleatorio guardado como hash;
bloqueo de cuenta tras 5 intentos durante 15 min; CSRF no aplica (API sin cookies); CORS sin orígenes
permitidos; sin manejador genérico que exponga trazas; Flyway con `clean` deshabilitado.

## Hallazgo corregido en esta etapa

**Acciones administrativas sin auditar.** Crear, modificar, activar, desactivar o retirar reglas web, revocar
dispositivos y registrar o cancelar operaciones masivas no dejaban registro. Se añadió
`AuditoriaAdministrativa`, que guarda el evento en la misma transacción que la acción y sin secretos. Sin
migraciones.

## Riesgos pendientes (no corregidos aquí; requieren decisión o corresponden a la Etapa 7)

1. **Credenciales de base de datos en el perfil remoto.** Las cuatro líneas protegidas usan `root` sin
   contraseña. Un despliegue público debe sobrescribirlas en `application-remote.properties` con variables
   de entorno y un usuario MySQL con permisos mínimos (Etapa 7), sin tocar las líneas protegidas.
2. **Cabeceras de proxy.** `server.forward-headers-strategy=framework` confía en `X-Forwarded-For`. Es correcto
   detrás de un proxy inverso; si el backend se expusiera directamente, un atacante podría falsear su IP y
   eludir el límite de intentos de vinculación por IP. Mitigación actual: el perfil remoto escucha solo en
   `127.0.0.1` por defecto.
3. **Bloqueo de cuenta por intentos.** Es por cuenta, no por IP: alguien que conozca el correo del padre podría
   bloquearle el acceso 15 minutos. Recomendación: límite adicional por IP en el inicio de sesión.
4. **Ejecutable elevado desde una carpeta del usuario.** La ayuda que modifica el archivo hosts se eleva desde
   la instalación por usuario; conviene instalación por equipo y firma Authenticode (Etapa 7).
5. **El hijo puede cerrar el Agente.** Pendiente la función de PIN del padre, ya diseñada y no implementada.
6. **Aplicaciones que cambian de carpeta al actualizarse** (p. ej., Roblox): el límite puede dejar de reconocer
   el programa hasta repetir la operación; requiere cambiar el Agente.
7. **SSE en producción.** El proxy inverso deberá admitir conexiones de al menos 10 minutos y no almacenar en
   búfer las respuestas (`X-Accel-Buffering: no`).

## Validación

`mvnw.cmd clean verify` en `D:\escritorio\familyguard`. Resultado esperado tras esta etapa: backend 129 pruebas
(125 + 4), Agente 86 y Administrador 26.
