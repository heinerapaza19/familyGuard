# Empaquetado Windows reproducible

## Herramientas verificadas

- Oracle JDK/jpackage 17.0.12.
- Maven Wrapper 3.10.0.
- WiX Toolset portable 3.14.1.8722 (`.tools/wix314`).
- Windows 10/11 x64.

Desde la raíz ejecute:

```powershell
.\scripts\installer\build-installers.ps1 -Version 1.0.0
```

Para regenerar únicamente el componente modificado puede usar `-Target Admin` o
`-Target Agent`; `-Target All` conserva el comportamiento predeterminado.

El script ejecuta `mvn clean verify`, prepara dependencias runtime, invoca `jpackage`, añade
runtime, icono, menú Inicio, escritorio, selector de directorio y desinstalador por usuario,
y copia el backend Spring Boot a `dist/backend`. Para una versión nueva cambie `-Version` y
publique los instaladores completos; no existe un actualizador automático ni se descargan
binarios sin verificación.

## Entrega 1.0.0

| Archivo | Bytes | SHA-256 |
|---|---:|---|
| `FamilyGuard-Administrador-1.0.0.exe` | 62,998,016 | `6E26BEB8A6BE7AF692EB3749E7EA964A78A5130582EF93F433ADAB9E9E409917` |
| `FamilyGuard-Agente-Windows-1.0.0.exe` | 66,131,968 | `B3820A7267781718C24C1E0537F6A8938F666AEF0C222F825F5DA1BB21E2FDF6` |

## Validación realizada

`mvn clean verify`: 108 pruebas, 0 fallos, 0 errores, 0 omitidas. Ambos instaladores
devolvieron código 0, abrieron sus ventanas usando el runtime incorporado, crearon los dos
accesos y se desinstalaron con código 0 retirando binarios y accesos. Administrador también
se abrió desde Inicio y escritorio. No hubo VM limpia, certificado de firma, backend HTTPS
público/código temporal ni autorización elevada; por ello vinculación remota, heartbeat real,
reinicio de Windows y restauración elevada de `hosts` quedan pendientes y no se simulan.

La reconstrucción del 18/11/2026 afectó solo al Administrador: su instalación silenciosa,
arranque instalado (`FamilyGuard · Administración familiar`), accesos y desinstalación se
verificaron otra vez con código 0. El binario del Agente conservó fecha y hash porque su código
y dependencias compartidas no cambiaron.

La revisión posterior de supervisión remota reconstruyó ambos paquetes. Administrador y
Agente se instalaron silenciosamente con código 0, abrieron respectivamente las ventanas
`FamilyGuard · Administración familiar` y `FamilyGuard Agente · Supervisión visible`, y se
desinstalaron con código 0. El Agente se abrió con `%LOCALAPPDATA%` temporal para demostrar
el arranque sin tocar la identidad DPAPI ni las colas reales del equipo de desarrollo.

## Preparación de la versión 1.1.0 (Etapa 7, sin generar)

`build-installers.ps1` admite parámetros opcionales; sin ellos el comportamiento es el de 1.0.0:

- `-ServerUrl https://<dominio>`: incluye la dirección oficial HTTPS en Administrador y Agente
  (`-Dfamilyguard.api.url`). Se rechazan direcciones sin HTTPS, con credenciales, parámetros o fragmentos.
- `-PerMachineAgent`: instala el Agente para todo el equipo en lugar de por usuario.
- `-SignThumbprint <huella>`: firma Authenticode con `signtool.exe` y sello de tiempo, y verifica la firma.

Además, el script muestra el SHA-256 de cada instalador. Procedimiento completo, migración desde 1.0.0 y
verificación pendiente en `DESPLIEGUE_HTTPS.md`. Los instaladores 1.1.0 no se han generado.

