# Instaladores para Windows

`build-installers.ps1` verifica los tres módulos, copia dependencias de ejecución y usa
`jpackage` con WiX 3.14.1 portable. Cada instalador incorpora su propio runtime Java,
crea accesos de Inicio/escritorio y registra el desinstalador por usuario.

Ejecutar desde la raíz:

```powershell
.\scripts\installer\build-installers.ps1
```

No contiene contraseñas, tokens ni rutas absolutas del entorno de desarrollo.

Opciones de la Etapa 7 (ver `docs/DESPLIEGUE_HTTPS.md`):

```powershell
.\scripts\installer\build-installers.ps1 -Version 1.1.0 -ServerUrl https://<dominio> -PerMachineAgent -SignThumbprint <huella>
```

