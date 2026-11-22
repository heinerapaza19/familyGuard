# Instaladores para Windows

`build-installers.ps1` compila y prueba los tres módulos (`mvnw clean verify`, sin omitir pruebas) y
empaqueta con `jpackage` de **JDK 17** y WiX 3.14.1 portable (`.tools\wix314`). Cada instalador
incluye su propio Java 17 y JavaFX 21: el usuario final no necesita Java, Maven ni MySQL.

Forma más simple: doble clic en `scripts\installer\generar-instaladores.cmd` (no cerrar la ventana hasta
que diga «Listo» o muestre un error). Desde PowerShell, en cualquier carpeta:

```powershell
powershell -ExecutionPolicy Bypass -File D:\escritorio\familyguard\scripts\installer\build-installers.ps1
```

El resultado (pruebas por módulo, verificación del contenido, rutas y tamaños, o el error) queda en
`build\windows-package\resultado.txt`.

Resultado:

| Archivo | Programa | Instalación |
|---|---|---|
| `dist\FG-Admin.exe` | FamilyGuard Administrador | Por usuario, `%LOCALAPPDATA%\FamilyGuard Administrador` |
| `dist\FG-Agente.exe` | FamilyGuard Agente Windows | Por usuario, `%LOCALAPPDATA%\FamilyGuard Agente Windows` |
| `dist\SHA256SUMS.txt` | Huellas SHA-256 de ambos | — |

El script se detiene sin generar nada si una prueba falla, si el JDK no es 17 o si el contenido del
paquete no es correcto (lanzador, runtime Java 17 propio, JavaFX para Windows, clase principal, sin
secretos, sin configuración del backend).

## Qué hace cada instalador

- Crea accesos en el menú Inicio (grupo «FamilyGuard») y en el escritorio, permite elegir carpeta y se
  desinstala desde *Configuración → Aplicaciones* como cualquier programa.
- **Actualizar:** generar con una versión mayor (`-Version 1.3.0`, etc.) e instalar encima. El nombre
  interno y el fabricante no cambian, así que Windows reemplaza la versión anterior en la misma carpeta.
- **Vinculación del Agente:** se guarda en `%LOCALAPPDATA%\FamilyGuard\Agent` (protegida con DPAPI),
  fuera de la carpeta del programa. Una actualización o reinstalación no la toca. Desinstalar tampoco
  la borra.
- **Inicio automático del Agente:** sigue siendo opcional y visible (Ajustes del Agente). Se registra en
  `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` con la ruta del programa instalado, que no
  cambia al actualizar.

## Dirección del backend (sin reconstruir)

Mientras no exista el servidor HTTPS público, `server.properties` va vacío y las aplicaciones usan
`http://localhost:8080`. Cuando exista, cualquiera de estas opciones basta:

1. **Administrador:** escribir la dirección HTTPS en la primera configuración (o al cambiar de servidor);
   queda guardada para ese usuario de Windows.
2. **Agente:** «Opciones avanzadas» al vincular el equipo.
3. **Ambos, en un equipo:** variable de entorno de usuario `FAMILYGUARD_API_URL=https://<dominio>`.
4. **Para repartir instaladores con la dirección incluida:** volver a ejecutar el script con
   `-ServerUrl https://<dominio>` (solo se regeneran los instaladores; el código no cambia).

Otras opciones: `-Target Admin|Agent`, `-PerMachineAgent` (instalar el Agente para todo el equipo; no
mezclar con una instalación por usuario existente), `-IncludeBackend` (copiar el JAR del backend a
`dist\backend`) y `-SignThumbprint <huella>` (firma Authenticode).

No contiene contraseñas, tokens ni rutas absolutas del entorno de desarrollo.
