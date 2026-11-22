# Autenticacion y usuarios

Revision: 15 de noviembre de 2026 (America/Lima)

## Flujo de autenticacion

El cliente envia correo y contrasena solo al endpoint de login. Spring compara la clave con
BCrypt (coste 12) y genera un token opaco aleatorio de 256 bits. El token se devuelve una
sola vez y JavaFX lo conserva solo en memoria. MySQL almacena exclusivamente SHA-256 del
token, su usuario, familia, fecha de expiracion y posible revocacion.

Las sesiones duran 8 horas. `POST /api/auth/logout` las revoca inmediatamente. Cinco
intentos fallidos bloquean la cuenta durante 15 minutos. El mensaje de error es siempre
`Credenciales invalidas`, exista o no el correo, y se realiza una comparacion BCrypt
simulada para reducir diferencias temporales cuando el usuario no existe.

## Roles y aislamiento

- `PADRE`: creado por el registro publico; administra perfiles de su familia.
- `TUTOR`: puede administrar la familia a la que ya esta asociado.
- `ADMIN_SISTEMA`: reservado para operacion tecnica; no se acepta en el registro publico y
  no obtiene por defecto acceso a datos familiares.

Los valores historicos de V1 permanecen en el enum para compatibilidad de datos, pero no
autorizan la gestion de hijos. No existe endpoint para modificar roles. Todos los servicios
de hijos toman la familia de `UsuarioPrincipal`; un ID ajeno devuelve 404 sin revelar su
existencia. La base refuerza el limite mediante claves foraneas compuestas.

## Endpoints

| Metodo | Ruta | Autenticacion | Resultado |
|---|---|---|---|
| POST | `/api/auth/register` | Publico | 201, crea familia y padre |
| POST | `/api/auth/login` | Publico | 200, token y perfil |
| GET | `/api/auth/me` | Bearer | 200, perfil autenticado |
| POST | `/api/auth/logout` | Bearer | 204, sesion revocada |
| POST | `/api/children` | PADRE/TUTOR | 201, crea perfil |
| GET | `/api/children` | PADRE/TUTOR | 200, lista familiar activa |
| GET | `/api/children/{id}` | PADRE/TUTOR | 200 o 404 |
| PUT | `/api/children/{id}` | PADRE/TUTOR | 200, actualiza perfil |
| DELETE | `/api/children/{id}` | PADRE/TUTOR | 204, desactivacion logica |

Codigos habituales: 400 para validacion o propiedades desconocidas, 401 para autenticacion
ausente/invalida, 403 para rol insuficiente, 404 para recurso no visible y 409 para correo
duplicado. Las respuestas nunca contienen contrasenas, hashes ni entidades JPA.

## Registro y validacion

El registro exige nombres, correo valido y unico, familia, confirmacion coincidente y una
contrasena de 12 a 72 caracteres con mayuscula, minuscula, numero y simbolo. La familia y el
padre `PADRE` se crean en una sola transaccion. Propiedades inesperadas como `rol` provocan
400 y no se persiste ningun usuario.

## Cliente JavaFX

`familyguard-admin` ofrece login, mostrar/ocultar clave, registro, informacion de usuario y
familia, tabla de hijos, alta, edicion, desactivacion y logout. El estilo reutilizable usa
azul petroleo oscuro. `AdminApiClient` usa `java.net.http.HttpClient`, timeout de conexion de
5 segundos, timeout por solicitud de 12 segundos y `sendAsync`; toda actualizacion visual
regresa al hilo JavaFX con `Platform.runLater`.

La URL se configura antes de iniciar el cliente:

```powershell
$env:FAMILYGUARD_API_URL = 'http://localhost:8080'
./mvnw.cmd -pl familyguard-admin javafx:run
```

Para un servidor remoto debe usarse una URL HTTPS con certificado valido. El cliente no
persiste credenciales ni tokens, por lo que al reiniciarse solicita login nuevamente.

## Configuracion y pruebas

El backend normal requiere `FAMILYGUARD_DB_URL`, `FAMILYGUARD_DB_USERNAME` y
`FAMILYGUARD_DB_PASSWORD`. MySQL es la unica base de ejecucion. H2 tiene alcance Maven
`test`, aparece solo en `application-test.properties` y nunca actua como fallback.

Se ejecutan 19 pruebas: 13 de API/seguridad y 6 de persistencia/arranque. Cubren todos los
escenarios obligatorios, V2 en H2, Hibernate `validate` y restricciones entre familias. V2
tambien fue aplicada en MySQL 8.4.3 real y Spring Boot completo su arranque.

## Limitacion pendiente

La cuenta local `familyguard_app` no existia al verificar y no se creo porque su secreto debe
elegirlo el usuario. La operacion habitual con minimo privilegio queda pendiente hasta
completar el procedimiento seguro de `CONFIGURACION_MYSQL.md`. No hay recuperacion de
contrasena, invitaciones de tutores ni HTTPS local automatizado en esta etapa.
