# Etapa 7 — Plan de despliegue HTTPS e instaladores (19/11/2026)

Objetivo final: Heiner, en Cusco, abre el Administrador y controla la laptop de Luz en Juliaca sin estar en la
misma red, con un backend central que funciona aunque la computadora del padre esté apagada.

**Estado real:** no hay ningún servidor público desplegado ni contratado, no se generaron instaladores nuevos y
no se tocó `familyguard_db`. Este documento y las plantillas de `scripts/deploy/` son la preparación. Ejecutarlos
requiere tu autorización y decisiones que solo tú puedes tomar (proveedor, dominio, certificado de firma).

## 1. Arquitectura de producción

```
Administrador (Cusco) ──HTTPS──┐
                               ├──► Nginx :443 (TLS Let's Encrypt, límites por IP)
Agente (Juliaca) ──HTTPS + SSE─┘          │ solo 127.0.0.1
                                          ▼
                              Backend Spring Boot :8080 (perfil "remote")
                                          │ solo 127.0.0.1
                                          ▼
                              MySQL 8 (usuarios sin privilegios globales)
```

- Solo el puerto 443 (y 80, para redirigir y renovar el certificado) queda abierto a Internet.
- Backend y MySQL escuchan únicamente en `127.0.0.1`.
- Las aplicaciones ya exigen HTTPS para direcciones remotas; HTTP solo se admite en `localhost`.

## 2. Opciones de alojamiento (no se contrató nada)

| Opción | Ventajas | Inconvenientes |
|---|---|---|
| **A. Servidor virtual Linux pequeño (recomendada)** | Siempre encendido; control total; las plantillas de este repositorio están hechas para él | Costo mensual; hay que mantener actualizaciones del sistema |
| B. PC de la casa + túnel (p. ej., Cloudflare Tunnel) | Sin costo de servidor ni puertos abiertos | La PC debe estar siempre encendida, cosa que el prompt maestro pide evitar; depende de la energía e Internet de la casa |
| C. Plataforma administrada (PaaS + MySQL administrado) | Menos mantenimiento | Mayor costo; SSE necesita conexiones largas (verificar límites del proveedor) |

Requisitos mínimos para la opción A: 1 vCPU, 1–2 GB de RAM, 20 GB de disco, Ubuntu LTS, Java 17, MySQL 8,
Nginx y certbot. Necesitas además un **dominio** (p. ej., `familyguard.tudominio.com`) que apunte al servidor.

## 3. Cambios ya preparados en el código (Etapa 7)

| Cambio | Motivo |
|---|---|
| `application-remote.properties` toma `spring.datasource.*` de `FAMILYGUARD_DB_URL`, `FAMILYGUARD_DB_USERNAME` y `FAMILYGUARD_DB_PASSWORD`, sin valores por defecto | Antes el perfil remoto heredaba `root` sin contraseña. Si falta una variable, el backend no arranca. Las cuatro líneas protegidas de `application.properties` no cambian |
| Flyway puede usar un usuario aparte (`FAMILYGUARD_FLYWAY_USERNAME`/`PASSWORD`) | La aplicación funciona solo con permisos de datos; los cambios de estructura, con otro usuario |
| `scripts/backend/start-backend.ps1` arranca con el perfil `remote` | Usaba el perfil `mysql`, que no existe: el backend ignoraba las variables seguras |
| Administrador: dirección oficial empaquetada (`server.properties`) y `-Dfamilyguard.api.url` | El padre no tiene que escribir una URL técnica cuando hay servidor oficial (el Agente ya lo tenía) |
| `build-installers.ps1`: `-ServerUrl`, `-PerMachineAgent`, `-SignThumbprint` | Incluir la dirección oficial, instalar el Agente para todo el equipo y firmar los instaladores |
| Plantillas en `scripts/deploy/` | Nginx (TLS, SSE, límites por IP, `X-Forwarded-For` no falsificable), servicio systemd endurecido, variables y usuarios MySQL |

Pruebas añadidas: `RemoteProfileConfigurationTests` (backend, 3) y `AdminServerConfigurationTests` (Administrador, 3).

## 4. Procedimiento de despliegue (cuando lo autorices)

1. **Respaldo local.** `mysqldump -u root --single-transaction --routines familyguard_db > respaldo_antes_servidor.sql`
   en tu PC. Guárdalo fuera del proyecto.
2. **Servidor.** Crear el usuario de sistema `familyguard`, instalar Java 17, MySQL 8, Nginx y certbot. Firewall:
   solo 22 (idealmente restringido a tu IP), 80 y 443.
3. **MySQL.** Ejecutar `scripts/deploy/mysql/usuarios-familyguard.sql` con contraseñas generadas en el servidor.
   `bind-address = 127.0.0.1`.
4. **Datos.** Importar el respaldo en `familyguard_db` del servidor. Al primer arranque, Flyway aplica las
   migraciones que falten (V8, V9 y V10 si tu base aún no las tiene). Revisar el registro antes de abrir el acceso.
5. **Backend.** Copiar el JAR a `/opt/familyguard/familyguard-backend.jar`, crear `/etc/familyguard/backend.env`
   (permisos 600) desde `scripts/deploy/linux/backend.env.example` e instalar
   `scripts/deploy/linux/familyguard-backend.service`.
6. **HTTPS.** Configurar el dominio, obtener el certificado con certbot y activar `scripts/deploy/nginx/familyguard.conf`.
7. **Verificación.** `https://<dominio>/actuator/health` responde `UP`; `/actuator/info` y `/actuator/env` no
   son accesibles; el Administrador inicia sesión; `scripts/backend/check-health.ps1 -ApiUrl https://<dominio>`.
8. **Copias de seguridad.** `mysqldump` diario con rotación (por ejemplo, 14 días) fuera del servidor, y prueba de
   restauración al menos una vez.

**Vuelta atrás:** detener el servicio, restaurar el respaldo y volver a apuntar las aplicaciones a la versión
anterior. Flyway no tiene `clean` habilitado y las migraciones V8–V10 son aditivas.

## 5. Actualización de instaladores a 1.1.0 (cuando lo autorices)

Los dos programas cambiaron desde 1.0.0 (Etapas 2–7), así que hay que regenerar ambos:

```powershell
.\scripts\installer\build-installers.ps1 -Version 1.1.0 -ServerUrl https://<dominio-autorizado> -PerMachineAgent `
    -SignThumbprint <huella-del-certificado>
```

- **`-ServerUrl`:** la dirección oficial queda incluida en ambos programas. Luz y Heiner solo escriben el código
  de vinculación y su correo y contraseña. La configuración avanzada sigue disponible para desarrollo.
- **`-PerMachineAgent`:** el Agente se instala en Archivos de programa (pide permiso de administrador una vez,
  con UAC visible). Así el ayudante elevado del filtro web no se ejecuta desde una carpeta que un usuario
  estándar pueda modificar (riesgo 4 de la Etapa 6).
- **`-SignThumbprint`:** firma Authenticode con sello de tiempo. Sin certificado, Windows SmartScreen mostrará una
  advertencia. Un certificado de firma de código tiene costo anual: decisión tuya.
- El script ejecuta `mvn clean verify` antes de empaquetar e imprime el SHA-256 de cada instalador para
  publicarlo junto a la descarga.

**Migración de equipos con 1.0.0:**
- Administrador: se instala encima (sigue siendo por usuario).
- Agente: 1.0.0 se instaló por usuario, así que conviene desinstalarlo antes de instalar 1.1.0 por equipo. Si
  después de reinstalar el Agente pide vincularse otra vez, se usa un código nuevo; la revinculación conserva el
  mismo dispositivo y sus reglas (problema N.º 1).
- Compatibilidad: un Agente 1.0.0 con el backend nuevo sigue funcionando por consulta periódica (no usa SSE); un
  Agente 1.1.0 con un backend sin SSE reintenta con espera creciente y también funciona por consulta.

**Verificación en una máquina virtual limpia (pendiente, no simulada):** instalar, vincular con un código desde
otra red, recibir un bloqueo en tiempo real, reiniciar Windows y comprobar el inicio automático, autorizar el
filtro web con UAC y desinstalar.

## 6. Pendientes que requieren tu decisión

1. Proveedor y plan del servidor (opción A recomendada) y nombre de dominio.
2. Certificado de firma de código (recomendado para distribuir el Agente).
3. Autorización para desplegar, migrar los datos y generar los instaladores 1.1.0.
4. Funciones aún no implementadas: PIN del padre para cerrar el Agente, problemas N.º 4 y N.º 5, e identificación
   de aplicaciones que cambian de carpeta al actualizarse.
