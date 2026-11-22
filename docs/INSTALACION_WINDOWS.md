# Instalación de FamilyGuard en Windows

## Requisitos

Windows 10/11 de 64 bits y acceso HTTPS al backend remoto, o backend local en
`http://localhost:8080`. Java, Maven y MySQL no son necesarios en los equipos del
Administrador o Agente: cada instalador incluye un runtime Java 17. MySQL pertenece
exclusivamente al servidor.

## Administrador

Ejecute `FamilyGuard-Administrador-1.0.0.exe`. La instalación por usuario crea accesos en
Inicio y escritorio y aparece en Aplicaciones instaladas. En la primera apertura seleccione
modo local o remoto, indique la API y pruebe la conexión. HTTP externo se rechaza; para uso
remoto se exige HTTPS con certificado válido. La dirección puede cambiarse en Configuración.

## Agente Windows

Ejecute `FamilyGuard-Agente-Windows-1.0.0.exe`, abra el acceso visible, indique la API y el
código temporal generado por un administrador. En esa vinculación se muestra y autoriza el
inicio automático. El token queda cifrado con DPAPI y los datos modificables se guardan en
`%LOCALAPPDATA%\FamilyGuard\Agent`. La supervisión comienza al vincular y se reanuda al iniciar
sesión mediante `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`; no se ejecuta oculto ni
se eleva todo el agente.

## Actualización y desinstalación

Instale una versión posterior con su nuevo instalador. AppData no forma parte del directorio
del programa, por lo que conserva preferencias, vinculación DPAPI y colas offline. Desinstale
desde Configuración > Aplicaciones: se eliminan binarios y accesos. Antes de desinstalar el
agente, desactive el inicio automático desde su ventana. La restauración elevada del bloque propio de `hosts`
debe hacerse con la función de desbloqueo del agente; nunca se deben borrar líneas ajenas.

Los instaladores 1.0.0 no están firmados digitalmente. Windows puede mostrar SmartScreen.
La firma Authenticode queda preparada como paso de publicación cuando exista certificado.

## Backend separado

El directorio `dist/backend` contiene el JAR ejecutable, ejemplo de variables, arranque y
comprobación de salud. Defina `FAMILYGUARD_DB_URL`, `FAMILYGUARD_DB_USERNAME` y
`FAMILYGUARD_DB_PASSWORD` en el entorno seguro del servicio. No publique el puerto MySQL.
Para producción use el perfil `remote`, proxy HTTPS (IIS, Caddy o nginx), cuenta de servicio
con permisos mínimos, reinicio ante fallo y rotación del log de
`%LOCALAPPDATA%\FamilyGuard\Backend\logs`.
