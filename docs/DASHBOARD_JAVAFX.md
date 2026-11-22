# Dashboard JavaFX de FamilyGuard

Revision: 16 de noviembre de 2026 (America/Lima)

## Navegacion y pantallas

El dashboard utiliza un shell nativo con barra superior, menu lateral fijo y contenido
adaptable. El tema azul petroleo esta centralizado en `familyguard.css`.

- **Inicio:** seis tarjetas consultadas desde la API y estado de la plataforma.
- **Mis hijos:** listado, alta, edicion, desactivacion, seleccion y dispositivos asociados.
- **Dispositivos:** tabla real filtrable por hijo, codigo temporal de vinculacion, estado de
  conexion, ultima comunicacion y revocacion.
- **Aplicaciones:** filtros por hijo, dispositivo, nombre y estado; muestra version, editor,
  tipo de instalacion, PID, ejecucion, ventana activa y ultima deteccion.
- **Reportes:** filtros de fechas/hijo/dispositivo; graficos diario, semanal, mensual y ranking.
- **Reportes Etapa 6:** agrega filtro por aplicacion y utiliza exclusivamente intervalos
  sincronizados por agentes autorizados.
- **Alertas:** hasta 100 eventos de auditoria reales ordenados por fecha.
- **Mi perfil:** nombre, correo, familia, rol y estado, todos de solo lectura.
- **Configuracion:** tema actual, densidad funcional, URL efectiva de API y version.
- **Cerrar sesion:** revoca el token y vuelve al login.

Cuando no existen registros, las vistas presentan ceros o un estado vacio. El inventario
procede del agente Windows autorizado; no se simula monitoreo.

## Endpoints agregados

| Metodo | Ruta | Filtros | Contenido |
|---|---|---|---|
| GET | `/api/dashboard` | — | Totales y tiempo de uso del dia |
| GET | `/api/devices` | `menorId` | Dispositivos activos de la familia |
| GET | `/api/devices/panel` | `menorId` | Panel «Mis dispositivos»: equipo + estado de restricciones (Etapa 3) |
| GET | `/api/devices/{id}/detail` | — | Detalle de un equipo de la familia (404 si es de otra familia) |
| GET | `/api/applications` | `menorId`, `dispositivoId`, `nombre` | Aplicaciones y uso acumulado |
| GET | `/api/reports/usage` | `desde`, `hasta`, `menorId`, `dispositivoId` | Series y agregados reales |
| GET | `/api/alerts` | — | 100 eventos recientes como maximo |

Todos requieren Bearer y rol `PADRE` o `TUTOR`. El backend ignora cualquier intento de usar
los filtros como autorizacion y siempre parte de la familia del token. Un rango de reporte
invalido o superior a 366 dias devuelve 400.

## Componentes y asincronia

`DashboardWorkspace` compone `BorderPane`, `FlowPane`, `TableView`, `ComboBox`, `DatePicker`,
`TabPane`, `LineChart` y `BarChart`. `DashboardSection` define las opciones y
`NavigationModel` mantiene el estado navegable comprobable.

`AdminApiClient` conserva el token solo en memoria, usa `HttpClient.sendAsync`, timeout de
conexion de 5 segundos y 12 segundos por solicitud. Un HTTP 401 elimina el token; la interfaz
informa la expiracion y regresa al login. Las actualizaciones visuales se realizan con
`Platform.runLater`.

## Estadisticas

- uso diario: serie devuelta por el backend;
- semanal y mensual: agrupaciones locales de la misma serie diaria;
- ranking: diez aplicaciones con mayor duracion en el periodo;
- resumen: tiempo por combinacion de hijo y dispositivo;
- tiempo del dia: suma de sesiones en la zona horaria de la familia.

La Etapa 5 agrego V3 para vinculacion, procesos y estado de deteccion. Hibernate permanece
en `validate`, MySQL sigue siendo la base normal y H2 solo participa en pruebas.

## Pruebas y limites

El resultado historico de la Etapa 4 fue de 32 pruebas exitosas (29 backend y 3 JavaFX), sin fallos, errores ni
omisiones. Las pruebas de Etapa 4 cubren dashboard vacio/con hijos, consultas, filtros, reportes reales
y vacios, aislamiento, anonimato, expiracion, validacion, navegacion y comunicacion fallida.
Login y dashboard fueron renderizados e inspeccionados realmente en Windows. Para la captura
del dashboard se utilizo una cuenta efimera en el backend H2 del perfil `test`; las seis
estadisticas muestran los ceros reales devueltos por ese backend vacio. No se insertaron
estadisticas ni se modifico MySQL. Las evidencias reproducibles estan en
`docs/captures/login.png` y `docs/captures/dashboard.png`.

`mvn clean verify` termino con `BUILD SUCCESS` en los tres modulos. El backend tambien fue
iniciado contra MySQL 8.4.3 real: Flyway confirmo esquema v2, Hibernate valido las entidades
y Tomcat arranco. La comprobacion uso temporalmente la administracion local de Laragon sin
guardar credenciales; `familyguard_app` continua pendiente.

Tras la Etapa 5, `mvn clean verify` ejecuta 48 pruebas: 38 backend, 3 administrador y 7
agente Windows. La vinculacion y deteccion ya estan integradas. Pendientes para etapas
posteriores: calculo de tiempo, categorias, bloqueo real, reglas automaticas, edicion de
perfil y persistencia ampliada de preferencias visuales.

## Controles de la Etapa 7

La vista Aplicaciones conserva filtros de hijo, dispositivo, estado y nombre. Sobre una
aplicacion real seleccionada permite configurar presets de 30 minutos, 1, 2 o 3 horas,
tiempo personalizado, bloqueo total, advertencia y horario `BLOQUEAR` o `PERMITIR`, además
de modificar o quitar la regla. El backend devuelve consumido, restante y estado real.

Se pueden marcar varios días simultáneamente. Una tabla secundaria muestra por regla la
aplicación, hijo, límite, consumo, tiempo restante y estado (`Activo`/`Agotado`).

## Control de sitios web

La sección permite buscar, crear, editar, activar/desactivar y eliminar reglas por dominio,
hijo y dispositivo; configurar vigencia y varios días/horarios; consultar sincronización y
eventos del filtro; y borrar esos metadatos. La interfaz advierte que no bloquea rutas HTTPS
y que las categorías sin fuente verificable no están disponibles.

## Dispositivos conectados (Etapa 9)

La sección muestra nombre, sistema y versión, usuario local, perfil/familia, vinculación,
último heartbeat, versión del agente, presencia y estado de restricciones. Incluye búsqueda,
filtro de conexión, selector de perfil, actualización, código temporal y desvinculación con
confirmación.

`PENDIENTE`, `APLICADO` y `FALLIDO` proceden de las revisiones deseada/confirmada y del error
comunicado; guardar una regla no se presenta como aplicación exitosa. La presencia depende
de un heartbeat reciente y no de la mera existencia del registro.

## Panel «Mis dispositivos» (Etapa 3 de conexión remota, 19/11/2026)

La sección Dispositivos pasa a llamarse **Mis dispositivos** y reúne todos los equipos de la familia
(p. ej., la laptop de Luz en Cusco, su PC en Juliaca y las PC de la misma casa, o un grupo de 10+ laptops).

**Columnas:** casilla de selección, dispositivo, hijo, sistema, versión del agente, conexión, última
conexión (heartbeat), última sincronización, políticas, restricciones de aplicaciones, bloqueo web y
botón «Ver» (detalle).

**Tres estados que no se mezclan:**
- *Conexión*: «Conectado» solo con heartbeat reciente (máx(45 s, 3 × intervalo)); un registro sin señal
  se muestra «Sin conexión».
- *Políticas / restricciones de apps*: revisión aplicada frente a deseada. Descargar las reglas no las
  marca como aplicadas; solo una confirmación `APLICADA` del agente. Una confirmación `FALLIDA` de la
  revisión vigente se muestra como «Error».
- *Bloqueo web*: estado confirmado por el agente en `sitios_dispositivos` (Aplicado/Pendiente/Error),
  por equipo: la confirmación de un equipo no se extiende a otro.

**Selección múltiple** (`DeviceSelection`, independiente de la página y de los filtros):
- casilla por equipo y contador «N dispositivos seleccionados (M fuera de los filtros actuales)»;
- «Seleccionar todos»: todos los que cumplen los filtros, en todas las páginas;
- «Deseleccionar todos»;
- «Seleccionar equipos de un hijo»: usa el hijo del filtro o pregunta cuál; solo equipos autorizados;
- «Seleccionar toda la familia»: pide confirmación indicando cuántos equipos se seleccionarán; nunca
  incluye equipos de otras familias (el backend solo devuelve los de la sesión y cada refresco descarta
  identificadores que ya no pertenecen al listado).

**Búsqueda y filtros** (`DeviceFilter`): texto por nombre del equipo, del hijo o usuario de Windows; hijo;
conexión (Conectados/Desconectados); políticas (Aplicada/Pendiente); restricciones (Con pendientes/Con
errores/Sin restricciones). Se aplican antes de paginar.

**Actualización automática:** cada 30 s, asíncrona (no bloquea JavaFX), sin solapar peticiones y
conservando la página actual. Se detiene al cambiar de sección o si la vista deja de mostrarse.

«Revocar dispositivo» actúa sobre la fila resaltada, no sobre la selección múltiple.

**Acciones masivas (Etapa 4):** la barra «Con la selección:» ofrece «Aplicar restricción» (Bloquear
sitio web, Limitar aplicación), «Retirar restricción» (sitio web o límite de aplicación) y
«Consultar resultados». Cada acción muestra vista previa y confirmación con equipos conectados y
desconectados. Detalle en `RESTRICCIONES_MASIVAS.md`.

## Renovación visual previa al ejecutable

El módulo administrador utiliza un sistema visual único en `familyguard.css`: fondo azul
noche, superficies azul petróleo, acento menta/cian, bordes redondeados, sombras discretas y
espaciado consistente. El login y registro tienen composición dividida, branding, jerarquía
tipográfica, foco visible, ayuda contextual y transiciones suaves.

El dashboard agrupa la navegación por contexto, muestra familia activa y conexión de API,
añade tarjetas de resumen y un bloque de estado de la plataforma. Tablas, filtros, gráficos,
formularios y diálogos comparten controles estilizados. Los estados se representan con chips
semánticos y las listas vacías muestran orientación útil sin introducir datos ficticios.

### Reconstruccion vectorial basada en las referencias

Las dos referencias de `img` se usaron exclusivamente para estudiar composicion, paleta,
jerarquia y ritmo visual. Ninguna se copia a recursos ni se carga durante la ejecucion.

- `FamilyIllustration` dibuja en `Canvas` una escena original con cuatro integrantes,
  portatil, escudo luminoso y elementos domesticos mediante degradados, curvas y sombras.
- `VectorIcon` centraliza los glifos reutilizables como `SVGPath`; sidebar, campos,
  prestaciones y tarjetas estadisticas ya no dependen de caracteres Unicode.
- El login conserva campos, visibilidad de contraseña y botones JavaFX reales.
- Inicio conserva los valores asincronos del backend en una cuadricula exacta de tres por dos,
  junto con familia activa, avatar, conexion y estado de plataforma.
- El tema comun tambien cubre registro, tablas, filtros, formularios, dialogos, graficos,
  estados vacios y el resto de secciones de las etapas 1 a 9.

La ilustracion es una reinterpretacion vectorial original, no una copia pixel a pixel de la
referencia. Prioriza legibilidad, rendimiento y ausencia total de recursos bitmap externos.
La utilidad `VisualCaptureLauncher`, ubicada solo en fuentes de prueba, permite regenerar las
capturas contra un backend aislado sin formar parte del artefacto de produccion.

La verificacion final del rediseño ejecuto `mvn clean verify` sobre los tres modulos: 92
pruebas exitosas (53 backend, 36 agente y 3 administrador), sin fallos, errores u omisiones.

## Modo claro premium

`ThemeManager` centraliza los temas `Claro` y `Oscuro`, aplica las hojas a escenas y dialogos
registrados y conserva exclusivamente la preferencia visual mediante `java.util.prefs`. No
se guardan credenciales ni datos familiares. El modo claro es la opcion inicial para una
instalacion sin preferencia; una eleccion previa se recupera al volver a abrir la aplicacion.

La hoja `familyguard-light.css` se carga sobre el sistema oscuro existente, que permanece
sin cambios visuales. El modo claro fue evolucionado a una composicion multicolor: fondo
azul luminoso, superficies celestes, turquesa, morado, menta, ambar y coral, texto azul
profundo y estados accesibles para:

- login, registro, dashboard y todas las secciones de navegacion;
- tablas, seleccion, filtros, formularios, campos y estados vacios;
- ComboBox, DatePicker, CheckBox, pestañas, graficos, progreso y scroll;
- dialogos, botones principales/secundarios/de peligro, alertas y badges semanticos.

El selector esta disponible antes de autenticarse y en `Configuracion > Tema de la
aplicacion`. El cambio se realiza en vivo. `FamilyIllustration` escucha la misma propiedad y
redibuja fondo, escudo, iluminacion y portatil con la paleta activa; los iconos SVG heredan el
color CSS correspondiente.

Evidencia visual generada contra el backend H2 aislado del perfil de pruebas:

- `docs/captures/login-light.png` (1220 x 780);
- `docs/captures/dashboard-light.png` (ventana 1366 x 768);
- `docs/captures/dashboard-light-1920x1080.png` (ventana 1920 x 1080);
- `docs/captures/settings-light.png` y `docs/captures/table-light.png`;
- `docs/captures/theme-switch-dark.png`, tomada tras cambiar en vivo al tema oscuro.

La decoracion nativa de Windows reduce en 14 x 37 pixeles el area interna capturada en las
ventanas de 1366 x 768 y 1920 x 1080. La escena de login se comprobo ademas con area interna
exacta de 1220 x 780. No se observaron recortes, solapamientos ni fondos oscuros residuales.
Dos procesos JVM separados confirmaron `set DARK` y posterior `expect DARK`; despues de la
prueba se restauro `Claro` como preferencia local.

La verificacion final termino con `BUILD SUCCESS`: 94 pruebas exitosas (53 backend, 36
agente y 5 administrador), 0 fallos, 0 errores y 0 omitidas.

### Revision multicolor

La revision multicolor elimina las grandes superficies blancas. Login y registro combinan
un hero azul-celeste-turquesa, ilustracion Canvas con decoracion morada, verde y coral, cuatro
prestaciones con acentos independientes y formulario azul/lavanda. La accion principal usa
turquesa a azul.

Las metricas de Inicio tienen estilos semanticos propios agregados por `DashboardWorkspace`:
turquesa para hijos, azul cielo para dispositivos, morado para aplicaciones, ambar para uso,
verde para restricciones y coral para alertas. Todos conservan exactamente los valores del
backend. Sidebar, encabezado, familia, plataforma, filtros, paneles, tablas, pestañas, graficos
y dialogos usan superficies coloreadas de bajo contraste en vez de rectangulos blancos.

Las capturas de `docs/captures` fueron regeneradas con esta revision y se comprobo de nuevo
`theme-switch-dark.png`: al retirar la hoja clara, el diseño azul petroleo original reaparece
sin residuos del sistema multicolor.

### Refinamiento final de login y registro

El acceso multicolor se adapto a la composicion aprobada en dos bloques. El tamaño inicial
se calcula a partir del area visual de Windows, hasta 1500 x 880, respetando un minimo de
1020 x 680. El panel izquierdo conserva la familia Canvas de cuatro integrantes, escudo,
portatil, identidad, mensaje y cuatro prestaciones con gradientes independientes.

La tarjeta derecha incorpora ahora logo FG centrado, jerarquia centrada, campos iconizados,
un control de ojo real para mostrar u ocultar cada contraseña y acciones con iconos SVG. El
boton principal conserva su icono despues de un error de autenticacion. Dos halos vectoriales
suaves completan el fondo sin usar imagenes.

Registro utiliza la misma tarjeta y lenguaje visual, con densidad especifica para mostrar
todos sus campos, confirmacion, ayuda, accion y regreso al login sin recortes a la resolucion
probada. Las evidencias finales son `docs/captures/login-light.png` y
`docs/captures/registration-light.png`.

### Familia procedente del recurso local

Se inspecciono `img/ico.png` (PNG ARGB de 603 x 348) y se confirmo que contiene la familia
solicitada junto con elementos ambientales. El original no se modifico. A partir de el se
genero `familyguard-admin/src/main/resources/com/familyguard/admin/images/family-cutout.png`,
un recorte transparente que conserva padre, madre, hijo, hija con audifonos y laptop, y
elimina habitacion, planta, lampara, estanteria y escudo de fondo duplicado.

`FamilyIllustration` conserva su Canvas y continúa dibujando el fondo, la decoracion domestica
y el gran escudo adaptado a cada tema. Un `ImageView` carga el recorte exclusivamente desde
el classpath, con `preserveRatio` y suavizado, y calcula su tamaño desde el espacio disponible.
No se utiliza una captura del login. Las comprobaciones visuales claras y oscuras se guardan
en `docs/captures/login-light.png` y `docs/captures/login-dark.png`.

## Diseño compacto y paginación

`PaginatedTable` y `PaginationModel` forman el componente común de las tablas extensas. El
modelo conserva el filtro que entrega cada pantalla, ordena el conjunto completo según el
encabezado activo y después calcula la página. Cambiar filtros o el tamaño (10/25/50/100)
vuelve a la primera página. El pie informa el intervalo, el total, la página y desactiva los
cuatro controles de navegación cuando no corresponden.

Las pantallas se organizan así:

- Mis hijos: selector maestro compacto y detalle con pestañas Información, Dispositivos,
  Restricciones y Actividad.
- Aplicaciones: una sola tabla visible mediante las pestañas Aplicaciones detectadas y
  Restricciones configuradas; las políticas incluyen sincronización real.
- Sitios web: reglas y eventos se muestran en pestañas independientes.
- Reportes: filtros adaptables, tarjetas resumen, gráficos de altura limitada y detalle
  paginado separado.
- Mi perfil: información personal y seguridad en dos columnas, con sesión y familia debajo.
- Navegación: área desplazable para módulos y pie permanente con Cerrar sesión y Salir de
  FamilyGuard como acciones distintas.

`VisualCaptureLauncher` inspecciona estas vistas a 1366×768, 1600×900 y 1920×1080, y permite
repetirlas con escalas JavaFX forzadas de 125 % y 150 %. Las
evidencias están en `docs/captures` con nombres `children-*`, `devices-*`, `applications-*`,
`websites-*`, `reports-*`, `alerts-*` y `profile-*`. La comprobación utilizó exclusivamente
el backend H2 del perfil de pruebas; no tocó MySQL ni datos de producción.
# Distribución Windows (Etapa 10)

El dashboard y los temas claro multicolor/oscuro se incluyen sin cambios en
`FamilyGuard-Administrador-1.0.0.exe`. En primera ejecución se valida y guarda la API; desde
Configuración puede probarse y sustituirse sin credenciales MySQL.

## Gestión de cuenta y políticas

La pantalla Mi perfil dispone de edición reautenticada, cambio seguro de contraseña y cierre
de sesión real. Aplicaciones separa inventario y reglas mediante pestañas y muestra el
ejecutable cuando está disponible. Una política puede desactivarse temporalmente, reactivarse,
editarse o eliminarse; eliminarla retira horarios y regla, pero conserva eventos de auditoría.
Los perfiles de hijos se desactivan de forma reversible y nunca se eliminan en cascada.

## Cuenta, hijos y dispositivos como pasos independientes (19/11/2026)

- El registro solo pide los datos del padre o tutor y el nombre de la familia; nunca datos de hijos.
- Tras iniciar sesión se entra al panel principal aunque no haya hijos; no se abre ni se impone ningún
  formulario. Sin hijos, Inicio y «Mis hijos» muestran la sugerencia discreta «Todavía no has agregado hijos».
- «Mis hijos» tiene el botón «Agregar hijo»; el padre decide cuándo y cuántos registrar.
- El menú usa «Mis dispositivos». En «Agregar dispositivo» solo aparecen los hijos activos de la propia familia;
  si no hay ninguno, se indica que se agreguen en «Mis hijos», sin abrir formularios.
- No se crean usuarios ni hijos de ejemplo. Los textos están centralizados en `FamilyTexts`.

