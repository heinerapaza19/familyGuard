# Inicia el backend con la base de datos de DESARROLLO AISLADA (familyguard_dev) para pruebas manuales.
# No toca familyguard_db. La base se crea vacía la primera vez y recibe V1-V12.
# Uso (desde la carpeta del proyecto):  .\scripts\backend\start-backend-dev.ps1
# Opcional: $env:FAMILYGUARD_DEV_DB_USERNAME / $env:FAMILYGUARD_DEV_DB_PASSWORD si su MySQL local las requiere.
[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$devJdbcUrl = "jdbc:mysql://127.0.0.1:3306/familyguard_dev?createDatabaseIfNotExist=true"
$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($listener) {
    $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    $description = if ($owner) { "$($owner.ProcessName) (PID $($owner.Id))" } else { "PID $($listener.OwningProcess)" }
    throw "El puerto 8080 ya está ocupado por $description. No se iniciará una segunda instancia."
}

$previousProfile = $env:SPRING_PROFILES_ACTIVE
$previousDevUrl = $env:FAMILYGUARD_DEV_DB_URL
try {
    # Alcance exclusivo de este proceso: no cambia el perfil global ni la configuración de producción.
    $env:SPRING_PROFILES_ACTIVE = "dev"
    $env:FAMILYGUARD_DEV_DB_URL = $devJdbcUrl
    Write-Host "FamilyGuard backend · perfil efectivo: dev"
    Write-Host "JDBC de desarrollo: $devJdbcUrl"
    Write-Host "familyguard_db no se modifica"
    # Las pruebas del backend usan el Agente como dependencia. Con -pl, Maven no lo compila: lo toma del
    # repositorio local (~/.m2). Se instala primero (con sus pruebas) para no compilar contra una versión antigua.
    Write-Host "Actualizando familyguard-agent en el repositorio local de Maven..."
    & (Join-Path $root "mvnw.cmd") -q -pl familyguard-agent install
    if ($LASTEXITCODE -ne 0) { throw "No se pudo compilar e instalar familyguard-agent (código $LASTEXITCODE)." }
    & (Join-Path $root "mvnw.cmd") -q -pl familyguard-backend spring-boot:run "-Dspring-boot.run.profiles=dev"
    if ($LASTEXITCODE -ne 0) { throw "El backend de desarrollo terminó con código $LASTEXITCODE." }
}
finally {
    if ($null -eq $previousProfile) { Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue }
    else { $env:SPRING_PROFILES_ACTIVE = $previousProfile }
    if ($null -eq $previousDevUrl) { Remove-Item Env:FAMILYGUARD_DEV_DB_URL -ErrorAction SilentlyContinue }
    else { $env:FAMILYGUARD_DEV_DB_URL = $previousDevUrl }
}
