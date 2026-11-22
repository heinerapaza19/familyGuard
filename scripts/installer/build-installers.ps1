[CmdletBinding()]
param(
    # Debe ser mayor que la versión instalada para que Windows actualice en lugar de duplicar (1.0.0 y 1.1.0 ya existieron).
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version = "1.2.0",
    [ValidateSet("All", "Admin", "Agent")]
    [string]$Target = "All",
    # Dirección oficial HTTPS del backend. Vacía (por defecto): las aplicaciones usan su configuración local y la
    # dirección se puede fijar después sin reconstruir (ver README.md).
    [string]$ServerUrl = "",
    # Instala el Agente para todo el equipo (Archivos de programa). No combinar con una instalación por usuario ya
    # existente: Windows las trata como productos distintos.
    [switch]$PerMachineAgent,
    # Copia además el JAR del backend a dist\backend (no lo modifica). Desactivado por defecto.
    [switch]$IncludeBackend,
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

# ---------------------------------------------------------------- JDK 17 (Maven y jpackage usan el mismo)
function Find-JPackage17 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\jpackage.exe") }
    $onPath = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    $candidates += Get-ChildItem -Path "C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft" `
        -Filter "jpackage.exe" -Recurse -Depth 2 -ErrorAction SilentlyContinue | ForEach-Object FullName
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        # Avisos de la JVM en stderr no deben detener el script: solo interesa la primera línea de stdout.
        $ErrorActionPreference = "Continue"
        $reported = "$(& $candidate --version 2>$null | Select-Object -First 1)".Trim()
        $ErrorActionPreference = "Stop"
        if ($reported -like "17*") { return [pscustomobject]@{ Path = $candidate; Version = $reported } }
    }
    throw "No se encontró jpackage de JDK 17. Instala un JDK 17 o define JAVA_HOME con su carpeta."
}
$jpackage = Find-JPackage17
$env:JAVA_HOME = Split-Path (Split-Path $jpackage.Path -Parent) -Parent
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
New-Item -ItemType Directory -Force -Path $dist, $build | Out-Null
# Registro legible del resultado (sin secretos): build\windows-package\resultado.txt
$statusFile = Join-Path $build "resultado.txt"
function Log([string]$Text) { Write-Host $Text; Add-Content -LiteralPath $statusFile -Value $Text -Encoding UTF8 }
Set-Content -LiteralPath $statusFile -Value "FamilyGuard instaladores $Version - inicio $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -Encoding UTF8
trap {
    if ($statusFile) { Add-Content -LiteralPath $statusFile -Value "ERROR $(Get-Date -Format 'HH:mm:ss'): $($_.Exception.Message)" -Encoding UTF8 }
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    break
}
Log "JDK: $env:JAVA_HOME (jpackage $($jpackage.Version))"

# ---------------------------------------------------------------- Compilación y pruebas (sin omitir ninguna)
& (Join-Path $root "mvnw.cmd") -B clean verify
if ($LASTEXITCODE -ne 0) { throw "mvn clean verify falló: corrige el error y vuelve a ejecutar. No se generaron instaladores." }

function Show-TestSummary {
    $all = 0
    foreach ($module in "familyguard-backend", "familyguard-admin", "familyguard-agent") {
        $reports = Join-Path $root "$module\target\surefire-reports"
        $tests = 0; $failures = 0; $errors = 0; $skipped = 0
        if (Test-Path $reports) {
            foreach ($file in Get-ChildItem -LiteralPath $reports -Filter "TEST-*.xml") {
                [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
                $suite = $xml.testsuite
                $tests += [int]$suite.tests; $failures += [int]$suite.failures
                $errors += [int]$suite.errors; $skipped += [int]$suite.skipped
            }
        }
        Log ("  {0,-20} pruebas={1,4}  fallos={2}  errores={3}  omitidas={4}" -f $module, $tests, $failures, $errors, $skipped)
        if ($tests -eq 0) { throw "No se ejecutaron pruebas en $module" }
        if ($failures -gt 0 -or $errors -gt 0) { throw "Hay pruebas fallidas en $module" }
        $all += $tests
    }
    Log "  Total: $all pruebas correctas"
}
Log "Pruebas ($(Get-Date -Format 'HH:mm:ss')):"
Show-TestSummary

# ---------------------------------------------------------------- Empaquetado
function New-PackageInput([string]$Module) {
    $inputDir = Join-Path $build "$Module-input"
    if (Test-Path $inputDir) { Remove-Item -LiteralPath $inputDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
    & (Join-Path $root "mvnw.cmd") -B -q -pl $Module dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$inputDir"
    if ($LASTEXITCODE -ne 0) { throw "No se copiaron dependencias de $Module" }
    Copy-Item -LiteralPath (Join-Path $root "$Module\target\$Module-0.0.1-SNAPSHOT.jar") -Destination $inputDir
    return $inputDir
}

# Revisa la aplicación que va dentro del instalador: Java 17 propio, JavaFX para Windows, clase principal,
# dirección del servidor esperada y ningún secreto ni configuración del backend.
function Test-PackagedApp([string]$Temp, [string]$Name, [string]$Module, [string]$MainClass) {
    $image = Join-Path $Temp "images\win-msi.image\$Name"
    $problems = @()
    if (-not (Test-Path -LiteralPath (Join-Path $image "$Name.exe"))) { $problems += "falta el lanzador $Name.exe" }
    $release = Join-Path $image "runtime\release"
    if (-not (Test-Path -LiteralPath $release) -or ((Get-Content -LiteralPath $release -Raw) -notmatch 'JAVA_VERSION="17')) {
        $problems += "el runtime incluido no es Java 17"
    }
    $app = Join-Path $image "app"
    $cfgPath = Join-Path $app "$Name.cfg"
    $cfg = if (Test-Path -LiteralPath $cfgPath) { Get-Content -LiteralPath $cfgPath -Raw } else { "" }
    if ($cfg -notmatch [regex]::Escape("app.mainclass=$MainClass")) { $problems += "la clase principal no es $MainClass" }
    if ($cfg -match '(?i)(password|passwd|secret|token|api[-_.]?key)\s*[=:]') { $problems += "la configuración del lanzador contiene un posible secreto" }
    if ($ServerUrl) {
        if ($cfg -notmatch [regex]::Escape("-Dfamilyguard.api.url=$ServerUrl")) { $problems += "falta la dirección oficial $ServerUrl" }
    } elseif ($cfg -match 'familyguard\.api\.url') { $problems += "incluye una dirección de servidor no solicitada" }
    foreach ($fx in "javafx-base", "javafx-graphics", "javafx-controls") {
        if (-not (Get-ChildItem -LiteralPath $app -Filter "$fx-*-win.jar")) { $problems += "falta $fx para Windows" }
    }
    if (Get-ChildItem -LiteralPath $app -Filter "familyguard-backend*") { $problems += "incluye el backend" }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $app "$Module-0.0.1-SNAPSHOT.jar"))
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -match '(^|/)(application[^/]*\.(properties|ya?ml)|\.env)$') { $problems += "incluye $($entry.FullName)" }
            if ($entry.FullName -like "*/server.properties") {
                $reader = New-Object IO.StreamReader($entry.Open(), [Text.Encoding]::UTF8)
                $text = $reader.ReadToEnd(); $reader.Dispose()
                $line = ($text -split "`r?`n" | Where-Object { $_ -match '^\s*familyguard\.server\.url\s*=' } | Select-Object -First 1)
                $value = if ($line) { ($line -split '=', 2)[1].Trim() } else { "" }
                if ($value -and $value -notmatch '^https://') { $problems += "server.properties contiene una dirección no HTTPS" }
                Log "  server.properties: familyguard.server.url=$(if ($value) { $value } else { '(vacía)' })"
            }
        }
    } finally { $zip.Dispose() }
    if ($problems.Count -gt 0) { throw "Paquete $Name incompleto o inseguro: $($problems -join '; ')" }
    Log "  Contenido verificado: lanzador, Java 17 propio, JavaFX Windows, sin secretos ni backend"
}

function New-WindowsInstaller([string]$Module, [string]$Name, [string]$MainClass, [string]$OutputName, [bool]$PerMachine) {
    Log "== $OutputName ($Name $Version)"
    $inputDir = New-PackageInput $Module
    $temp = Join-Path $build "$Module-jpackage"
    if (Test-Path $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    # --name y --vendor NO se cambian: de ellos salen el código de actualización del instalador y la carpeta de
    # instalación. Así la versión nueva reemplaza a la anterior, conserva los accesos y el inicio automático
    # del Agente (HKCU\...\Run apunta a la misma ruta) y no toca los datos de %LOCALAPPDATA%\FamilyGuard.
    $options = @("--type", "exe", "--dest", $dist, "--temp", $temp, "--input", $inputDir, "--name", $Name,
        "--app-version", $Version, "--vendor", "FamilyGuard", "--description", "Control parental FamilyGuard para Windows",
        "--copyright", "FamilyGuard",
        "--main-jar", "$Module-0.0.1-SNAPSHOT.jar", "--main-class", $MainClass, "--icon", $icon,
        "--win-menu", "--win-menu-group", "FamilyGuard", "--win-shortcut", "--win-dir-chooser",
        "--java-options", "-Dfile.encoding=UTF-8")
    if (-not $PerMachine) { $options += "--win-per-user-install" }
    if ($ServerUrl) { $options += @("--java-options", "-Dfamilyguard.api.url=$ServerUrl") }
    & $jpackage.Path @options
    if ($LASTEXITCODE -ne 0) { throw "jpackage falló para $Module" }
    Test-PackagedApp $temp $Name $Module $MainClass
    $created = Join-Path $dist "$Name-$Version.exe"
    if (-not (Test-Path -LiteralPath $created)) { throw "jpackage no produjo $created" }
    $target = Join-Path $dist $OutputName
    if (Test-Path $target) { Remove-Item -LiteralPath $target -Force }
    Move-Item -LiteralPath $created -Destination $target
    if ((Get-Item -LiteralPath $target).Length -lt 20MB) { throw "$OutputName es demasiado pequeño: no incluye el runtime" }
    if ($SignThumbprint) {
        & signtool.exe sign /sha1 $SignThumbprint /fd SHA256 /tr $TimestampUrl /td SHA256 $target
        if ($LASTEXITCODE -ne 0) { throw "No se pudo firmar $OutputName" }
        & signtool.exe verify /pa $target
        if ($LASTEXITCODE -ne 0) { throw "La firma de $OutputName no es válida" }
    }
}

$built = @()
if ($Target -in @("All", "Admin")) {
    New-WindowsInstaller "familyguard-admin" "FamilyGuard Administrador" "com.familyguard.admin.AdminLauncher" "FG-Admin.exe" $false
    $built += "FG-Admin.exe"
}
if ($Target -in @("All", "Agent")) {
    New-WindowsInstaller "familyguard-agent" "FamilyGuard Agente Windows" "com.familyguard.agent.AgentLauncher" "FG-Agente.exe" $PerMachineAgent.IsPresent
    $built += "FG-Agente.exe"
}

if ($IncludeBackend) {
    $backend = Join-Path $dist "backend"
    New-Item -ItemType Directory -Force -Path $backend | Out-Null
    Copy-Item -LiteralPath (Join-Path $root "familyguard-backend\target\familyguard-backend-0.0.1-SNAPSHOT.jar") `
        -Destination (Join-Path $backend "familyguard-backend-$Version.jar") -Force
    foreach ($file in "start-backend.ps1", "check-health.ps1", ".env.example") {
        Copy-Item -LiteralPath (Join-Path $root "scripts\backend\$file") -Destination $backend -Force
    }
}

# Huellas SHA-256 para comprobar que el instalador que se reparte es el generado aquí.
$sums = foreach ($name in $built) {
    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $dist $name)
    "$($hash.Hash.ToLowerInvariant())  $name"
}
$sums | Set-Content -LiteralPath (Join-Path $dist "SHA256SUMS.txt") -Encoding ASCII
Log "Instaladores generados ($(Get-Date -Format 'HH:mm:ss')):"
foreach ($name in $built) {
    $file = Get-Item -LiteralPath (Join-Path $dist $name)
    Log ("  {0}  ({1:N1} MB)" -f $file.FullName, ($file.Length / 1MB))
}
Log "OK"
