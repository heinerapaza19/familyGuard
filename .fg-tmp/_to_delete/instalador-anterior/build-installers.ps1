[CmdletBinding()]
param(
    [string]$Version = "1.1.0",
    [ValidateSet("All", "Admin", "Agent")]
    [string]$Target = "All",
    # Dirección oficial HTTPS del backend (Etapa 7). Vacía: las aplicaciones usan su configuración local.
    [string]$ServerUrl = "",
    # Instala el Agente para todo el equipo (Archivos de programa): el ayudante elevado del filtro web deja de
    # ejecutarse desde una carpeta que el usuario estándar puede modificar.
    [switch]$PerMachineAgent,
    # Huella SHA-1 de un certificado de firma de código instalado en el equipo. Vacía: no se firma.
    [string]$SignThumbprint = "",
    [string]$TimestampUrl = "http://timestamp.digicert.com"
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$dist = Join-Path $root "dist"
$build = Join-Path $root "build\windows-package"
$icon = Join-Path $PSScriptRoot "resources\familyguard.ico"
$wix = Join-Path $root ".tools\wix314"
if (-not (Test-Path $icon)) { throw "Falta el icono: $icon" }
if (-not (Test-Path (Join-Path $wix "candle.exe"))) { throw "Falta WiX 3.14.1 portable en $wix" }
$env:Path = "$wix;$env:Path"
if ($ServerUrl) {
    $uri = $null
    if (-not [Uri]::TryCreate($ServerUrl, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne "https" -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        throw "ServerUrl debe ser una dirección HTTPS sin credenciales, parámetros ni fragmentos"
    }
    $ServerUrl = $uri.AbsoluteUri.TrimEnd("/")
}
if ($SignThumbprint -and -not (Get-Command signtool.exe -ErrorAction SilentlyContinue)) {
    throw "Para firmar se necesita signtool.exe (Windows SDK) en el PATH"
}
New-Item -ItemType Directory -Force -Path $dist, $build | Out-Null

& (Join-Path $root "mvnw.cmd") -q clean verify
if ($LASTEXITCODE -ne 0) { throw "mvn clean verify falló" }

function New-PackageInput([string]$Module) {
    $input = Join-Path $build "$Module-input"
    if (Test-Path $input) { Remove-Item -LiteralPath $input -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $input | Out-Null
    & (Join-Path $root "mvnw.cmd") -q -pl $Module dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$input"
    if ($LASTEXITCODE -ne 0) { throw "No se copiaron dependencias de $Module" }
    Copy-Item -LiteralPath (Join-Path $root "$Module\target\$Module-0.0.1-SNAPSHOT.jar") -Destination $input
    return $input
}

function New-WindowsInstaller([string]$Module, [string]$Name, [string]$MainClass, [string]$OutputName, [bool]$PerMachine) {
    $input = New-PackageInput $Module
    $temp = Join-Path $build "$Module-jpackage"
    if (Test-Path $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    $options = @("--type", "exe", "--dest", $dist, "--temp", $temp, "--input", $input, "--name", $Name,
        "--app-version", $Version, "--vendor", "FamilyGuard", "--description", "Control parental FamilyGuard para Windows",
        "--main-jar", "$Module-0.0.1-SNAPSHOT.jar", "--main-class", $MainClass, "--icon", $icon,
        "--win-menu", "--win-menu-group", "FamilyGuard", "--win-shortcut", "--win-dir-chooser",
        "--java-options", "-Dfile.encoding=UTF-8")
    if (-not $PerMachine) { $options += "--win-per-user-install" }
    if ($ServerUrl) { $options += @("--java-options", "-Dfamilyguard.api.url=$ServerUrl") }
    & jpackage @options
    if ($LASTEXITCODE -ne 0) { throw "jpackage falló para $Module" }
    $created = Get-ChildItem -LiteralPath $dist -Filter "$Name-*.exe" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $created) { throw "jpackage no produjo el instalador de $Module" }
    $target = Join-Path $dist $OutputName
    if (Test-Path $target) { Remove-Item -LiteralPath $target -Force }
    Move-Item -LiteralPath $created.FullName -Destination $target
    if ($SignThumbprint) {
        & signtool.exe sign /sha1 $SignThumbprint /fd SHA256 /tr $TimestampUrl /td SHA256 $target
        if ($LASTEXITCODE -ne 0) { throw "No se pudo firmar $OutputName" }
        & signtool.exe verify /pa $target
        if ($LASTEXITCODE -ne 0) { throw "La firma de $OutputName no es válida" }
    }
    Get-FileHash -Algorithm SHA256 -LiteralPath $target | Select-Object Hash, Path
}

if ($Target -in @("All", "Admin")) {
    New-WindowsInstaller "familyguard-admin" "FamilyGuard Administrador" "com.familyguard.admin.AdminLauncher" "FamilyGuard-Administrador-$Version.exe" $false
}
if ($Target -in @("All", "Agent")) {
    New-WindowsInstaller "familyguard-agent" "FamilyGuard Agente Windows" "com.familyguard.agent.AgentLauncher" "FamilyGuard-Agente-Windows-$Version.exe" $PerMachineAgent.IsPresent
}

$backend = Join-Path $dist "backend"
New-Item -ItemType Directory -Force -Path $backend | Out-Null
Copy-Item -LiteralPath (Join-Path $root "familyguard-backend\target\familyguard-backend-0.0.1-SNAPSHOT.jar") `
    -Destination (Join-Path $backend "familyguard-backend-$Version.jar") -Force
Copy-Item -LiteralPath (Join-Path $root "scripts\backend\start-backend.ps1") -Destination $backend -Force
Copy-Item -LiteralPath (Join-Path $root "scripts\backend\check-health.ps1") -Destination $backend -Force
Copy-Item -LiteralPath (Join-Path $root "scripts\backend\.env.example") -Destination $backend -Force
Get-ChildItem -LiteralPath $dist -File | Select-Object Name,Length,LastWriteTime
