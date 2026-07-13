# start.ps1 — One-command project launcher for Distributed Skyline Processing
# Usage: .\start.ps1
# Optional: .\start.ps1 -Fresh    (tears down existing Docker first)

param(
    [switch]$Fresh   # Pass -Fresh to do docker-compose down before starting
)

$Root = $PSScriptRoot

Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "  Distributed Skyline Processing" -ForegroundColor Cyan
Write-Host "  Auto Launcher" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""

# ── Step 1: Docker ────────────────────────────────────────────────────────────
if ($Fresh) {
    Write-Host "[1/5] Tearing down existing Docker environment..." -ForegroundColor Yellow
    docker-compose -f "$Root\deploy\docker-compose.yml" down 2>$null
    Write-Host "      Done." -ForegroundColor Green
}

Write-Host "[1/5] Starting Docker services..." -ForegroundColor Cyan
docker-compose -f "$Root\deploy\docker-compose.yml" up -d

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: docker-compose failed. Is Docker Desktop running?" -ForegroundColor Red
    exit 1
}

# ── Step 2: Wait for Kafka topics ─────────────────────────────────────────────
Write-Host "[2/5] Waiting for Kafka topics to be created..." -ForegroundColor Cyan
$timeout = 90
$elapsed = 0
$ready = $false

while ($elapsed -lt $timeout) {
    $logs = docker logs init-kafka 2>&1
    if ($logs -match "Topics created") {
        $ready = $true
        break
    }
    Start-Sleep -Seconds 3
    $elapsed += 3
    Write-Host "      Waiting... ($elapsed / $timeout s)"
}

if (-not $ready) {
    Write-Host "ERROR: Kafka topics were not created within $timeout seconds." -ForegroundColor Red
    Write-Host "       Check: docker logs init-kafka" -ForegroundColor Yellow
    exit 1
}

Write-Host "      Kafka topics ready!" -ForegroundColor Green

# ── Step 3: Metrics Collector ─────────────────────────────────────────────────
Write-Host "[3/5] Starting Metrics Collector..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList @(
    "-NoExit",
    "-Command",
    "Set-Location '$Root'; `$host.UI.RawUI.WindowTitle = 'Metrics Collector'; python python/src/metrics_collector.py results.csv"
)

Start-Sleep -Seconds 1

# ── Step 4: WebSocket Bridge ──────────────────────────────────────────────────
Write-Host "[4/5] Starting WebSocket Bridge..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList @(
    "-NoExit",
    "-Command",
    "Set-Location '$Root'; `$host.UI.RawUI.WindowTitle = 'WebSocket Bridge'; python python/src/websocket_bridge.py"
)

Start-Sleep -Seconds 2

# ── Step 5: Dashboard ─────────────────────────────────────────────────────────
Write-Host "[5/5] Starting Dashboard..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList @(
    "-NoExit",
    "-Command",
    "Set-Location '$Root\dashboard'; `$host.UI.RawUI.WindowTitle = 'Dashboard'; npm run dev"
)

# ── Done ──────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "======================================" -ForegroundColor Green
Write-Host "  All services started!" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Flink UI  ->  http://localhost:8081" -ForegroundColor White
Write-Host "  Dashboard ->  http://localhost:5173" -ForegroundColor White
Write-Host ""
Write-Host "Next:" -ForegroundColor Yellow
Write-Host "  1. Submit the JAR at http://localhost:8081"
Write-Host "     Program Arguments (Example):" -ForegroundColor Gray
Write-Host "       --config /opt/flink/usrlib/config.properties --algo mr-angle --parallelism 4 --dims 3" -ForegroundColor Cyan
Write-Host "  2. Run the producer (quick test - query every 10k records):"
Write-Host ""
Write-Host "     python python/src/unified_producer.py input-tuples anti_correlated 3 0 10000 queries 10000" -ForegroundColor Cyan
Write-Host ""
