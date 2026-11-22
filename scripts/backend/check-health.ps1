param([string]$ApiUrl = "http://localhost:8080")
$uri = ([uri]$ApiUrl).AbsoluteUri.TrimEnd('/') + "/actuator/health"
Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 10
