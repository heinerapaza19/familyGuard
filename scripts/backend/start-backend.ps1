[CmdletBinding()]
param([string]$Jar = (Join-Path $PSScriptRoot "familyguard-backend-1.1.0.jar"))
$ErrorActionPreference = "Stop"
foreach ($name in "FAMILYGUARD_DB_URL","FAMILYGUARD_DB_USERNAME","FAMILYGUARD_DB_PASSWORD","FAMILYGUARD_PIN_KEY") {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "Falta la variable segura $name" }
}
$logs = Join-Path $env:LOCALAPPDATA "FamilyGuard\Backend\logs"
New-Item -ItemType Directory -Force -Path $logs | Out-Null
# Perfil "remote": credenciales de MySQL desde las variables seguras anteriores (el perfil "mysql" no existía
# y el backend habría usado la configuración local con root). Escucha en 127.0.0.1 detrás del proxy HTTPS.
& java -jar $Jar "--spring.profiles.active=remote" 2>&1 | Tee-Object -FilePath (Join-Path $logs "familyguard.log") -Append
