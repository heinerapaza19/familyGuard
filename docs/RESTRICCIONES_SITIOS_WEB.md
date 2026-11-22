# Restricciones de sitios web — Etapa 8

## Alcance

FamilyGuard permite a un padre o tutor crear reglas por dominio, hijo y dispositivo, activarlas, editarlas o desactivarlas, definir vigencia temporal/permanente y varios horarios. Se normalizan esquema, mayúsculas, ruta y punto final mediante IDN/ASCII. No se aceptan direcciones locales ni dominios esenciales protegidos.

El filtrado DNS no puede distinguir `/ruta` dentro de una conexión HTTPS. Por ello, aunque el usuario escriba una URL, FamilyGuard muestra y aplica solamente el dominio. No se inspeccionan páginas, consultas, contenidos, títulos ni historial de navegación.

## Método real en Windows

El agente administra exclusivamente el bloque delimitado `# BEGIN FAMILYGUARD MANAGED` / `# END FAMILYGUARD MANAGED` del archivo `%WINDIR%\System32\drivers\etc\hosts`, agrega resoluciones IPv4 e IPv6 locales y ejecuta `ipconfig /flushdns`. Funciona para Chrome, Edge, Firefox y otras aplicaciones cuando usan la resolución del sistema.

Modificar `hosts` requiere permisos de administrador y el agente se ejecuta sin elevar. Si Windows rechaza la escritura, el archivo no se altera y el agente muestra «Bloqueo web pendiente: requiere permisos de administrador». Al detener el control se intenta retirar solo el bloque administrado; las demás entradas se preservan byte a byte.

### Permisos, reintentos y estados (problema N.º 3, 18/11/2026)

- **Reintento controlado:** el agente evalúa las reglas cada 2 s, pero solo intenta escribir `hosts` cuando cambia el conjunto de dominios o vence un reintento de 30 s, 1, 2, 4, 8 y como máximo 15 minutos. Si el archivo ya contiene el bloque correcto, no escribe nada.
- **Eventos sin duplicados:** se registra un evento por regla solo cuando cambia su estado (`FILTRO_APLICADO`, `FILTRO_PENDIENTE_PERMISOS`, `FILTRO_ERROR`, `FILTRO_RETIRADO`) o la revisión de la regla. La caché cifrada de reglas no se reescribe si las reglas no cambiaron.
- **Estados independientes:** la confirmación de revisión de políticas informa que las restricciones de aplicaciones se aplicaron; el bloqueo web se informa por regla y dispositivo. El Administrador muestra «Aplicado», «Pendiente» o «Error» en la columna «Bloqueo en equipo», con su detalle. Descargar una regla ya no equivale a aplicarla.
- **Sin falsas confirmaciones:** «Aplicado» solo se informa cuando el contenido real de `hosts` coincide con los dominios deseados. Al modificar una regla vuelve a «Pendiente» y se ignoran eventos de revisiones anteriores.
- **Autorización explícita:** cuando falta el permiso, la ventana del agente muestra «Autorizar bloqueo web (administrador)». Solo al pulsarlo, Windows muestra UAC y ejecuta una instancia auxiliar del propio agente (`--familyguard-web-filter`) que valida los dominios, actualiza únicamente el bloque de FamilyGuard y termina. El agente no se eleva, no se instala ningún servicio ni tarea y no hay persistencia elevada. Después el agente verifica `hosts` por lectura antes de informar «Aplicado».
- **Retirada:** al quitar o desactivar reglas se elimina su entrada del bloque y se informa `FILTRO_RETIRADO`; si faltan permisos queda «Pendiente» hasta autorizar.
- **Migración aditiva V8:** agrega a `sitios_dispositivos` el estado, detalle, fecha y revisión de aplicación. Las asignaciones existentes comienzan en «Pendiente».
- **Limitación:** con instalación por usuario, el ejecutable que se eleva reside en una carpeta del usuario. Conviene autorizar solo cuando la solicitud la inicia el propio padre o tutor y, en adelante, firmar el instalador (Authenticode) o instalarlo para todos los usuarios.

No se instalan certificados raíz, proxy HTTPS, extensión de navegador, controlador de red ni servicio oculto. DNS-over-HTTPS, resolutores integrados, VPN, cachés de proceso y cambios manuales con privilegios administrativos pueden evitar o retrasar el bloqueo. No se afirma que sea imposible de evadir. `incluirSubdominios` añade el dominio raíz y `www`; el archivo hosts no admite comodines, por lo que otros subdominios requieren reglas explícitas.

## Prioridad

1. horario `BLOQUEAR` coincidente;
2. fuera de cualquier horario `PERMITIR`;
3. regla permanente `BLOQUEAR`;
4. regla temporal solo dentro de su vigencia;
5. permitido.

La zona horaria procede de la familia/dispositivo. Las reglas descargadas se cifran con DPAPI en `web-rules.bin` y siguen activas sin conexión. Los eventos de aplicación o error llevan UUID y se sincronizan de forma idempotente. Con este mecanismo no es técnicamente posible conocer cada intento de navegación; el dashboard muestra eventos del filtro, no un historial privado.

## Categorías

V6 registra Adultos, Apuestas, Redes sociales, Videojuegos, Entretenimiento y Personalizado. Solo Personalizado está disponible. Las demás se muestran como no disponibles y el backend rechaza su activación hasta incorporar una fuente real, versionada, verificable y actualizable de clasificación de dominios.

## V6 y API

V6 amplía `sitios_bloqueados` y crea `sitios_dispositivos`, `horarios_sitios`, `categorias_sitios`, `dominios_categoria` y `eventos_sitios`.

- Padre/tutor: `/api/web-restrictions`, `/api/web-categories`, `/api/web-events`.
- Agente: `/api/agent/web-rules`, `/api/agent/web-events` con `X-Agent-Token`.

Toda consulta deriva la familia del principal o token. El historial mínimo puede eliminarse por familia.

## Ejecución y prueba manual

Inicie backend y administrador normalmente. Para aplicar el filtro, abra PowerShell como administrador y ejecute:

```powershell
cd D:\escritorio\familyguard
.\mvnw.cmd -pl familyguard-agent javafx:run
```

Prueba reversible dirigida:

```powershell
.\mvnw.cmd -pl familyguard-agent "-Dtest=WindowsManualWebFilterIT" test
```

La prueba usa `example.com`, comprueba que `example.org` continúa resolviendo y restaura los bytes originales de `hosts` en `finally`. El 16/11/2026 la ejecución sin elevar fue rechazada por Windows antes de modificar el archivo; no se declaró el bloqueo manual como exitoso.
