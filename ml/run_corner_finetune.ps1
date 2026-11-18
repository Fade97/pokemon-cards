# SPDX-License-Identifier: GPL-3.0-or-later
# Corner detector fine-tune on real photos (issue #59), end to end on Fabian-PC (RTX 3080, Windows + WSL export).
#
#   cd D:\AI\pokemon-cards\ml
#   powershell -ExecutionPolicy Bypass -File run_corner_finetune.ps1
#
# Steps (each one is skipped when its output already exists, so a rerun continues where it stopped):
#   1. corner_analysis.py forb/tcgar with the shipped detector: per-photo rows = baseline + FORB pseudo-labels
#      (~25 min on 8 threads; skipped when corner_rows_<embedder>.json was copied over from Vulcan)
#   2. corner_realset.py build: TCG-AR train cards (annotated) + FORB train cards (confident pseudo-labels),
#      TCG-AR val cards for selection, no test-split card (~3 min, ~1.2 GB in <data>\corner-train)
#   3. train_corners.py --real from corners_mnv3 (~15 min for 10 epochs)
#   4. export_corners.py in WSL -> <data>\export\<Run>_{f32,w8}.tflite (~2 min)
#   5. corner_analysis.py on the test split with the new detector + compare (~6 min)
# Expected total: ~50 min, ~25 min when the step-1 rows come from Vulcan.
# Embedder: the EN gallery in <data>\packs-work\en must be embedded with it (eval_forb.gallery checks this).
param(
    [string]$Embedder = "mnv3_256_real",
    [string]$Run = "corners_mnv3_real",
    [int]$Epochs = 10,
    [double]$Lr = 2e-4,
    [int]$Threads = 8,
    [string]$Distro = "Ubuntu"
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$Data = if ($env:CARDINDEX_DATA) { $env:CARDINDEX_DATA } else { "D:\AI\pokemon-cards-data" }
$env:CARDINDEX_DATA = $Data

function Step($name, [scriptblock]$cmd) {
    Write-Host "`n=== $name ===" -ForegroundColor Cyan
    $t = Get-Date
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "$name failed (exit $LASTEXITCODE)" }
    Write-Host ("{0} took {1:n1} min" -f $name, ((Get-Date) - $t).TotalMinutes)
}

if (-not (Test-Path "$Data\external\forb\corner_rows_$Embedder.json")) {
    Step "1a FORB rows (shipped detector)" { uv run --extra litert python corner_analysis.py forb --embedder $Embedder --threads $Threads }
}
if (-not (Test-Path "$Data\external\tcg-ar\corner_rows_$Embedder.json")) {
    Step "1b TCG-AR rows (shipped detector)" { uv run --extra litert python corner_analysis.py tcgar --embedder $Embedder --threads $Threads }
}
Step "1c baseline summary" { uv run python corner_analysis.py summary --embedder $Embedder }
if (-not (Test-Path "$Data\corner-train\real_corners.npz")) {
    Step "2 real corner set" { uv run python corner_realset.py build --rows $Embedder }
}
if (-not (Test-Path "$Data\checkpoints\$Run.pt")) {
    Step "3 fine-tune" { uv run python train_corners.py --run $Run --init corners_mnv3 --real --epochs $Epochs --lr $Lr --eval-every 1 }
}
if (-not (Test-Path "$Data\export\${Run}_w8.tflite")) {
    $ml = (wsl -d $Distro -- wslpath -a ($PSScriptRoot -replace '\\', '/')).Trim()
    $dataPosix = (wsl -d $Distro -- wslpath -a ($Data -replace '\\', '/')).Trim()
    Step "4 export (WSL)" { wsl -d $Distro -- env CARDINDEX_DATA_POSIX=$dataPosix ~/cardindex-export/.venv/bin/python "$ml/export_corners.py" --run $Run }
}
Step "5a TCG-AR with $Run" { uv run --extra litert python corner_analysis.py tcgar --embedder $Embedder --corners $Run --threads $Threads }
Step "5b FORB test split with $Run" { uv run --extra litert python corner_analysis.py forb --embedder $Embedder --corners $Run --test-only --threads $Threads }
Step "5c summary" { uv run python corner_analysis.py summary --embedder $Embedder --corners $Run }
Step "5d compare" { uv run python corner_analysis.py compare --embedder $Embedder --corners $Run }
Write-Host "`nResults: results\train_$Run.json, results\export_$Run.json, results\corner_analysis_${Embedder}_$Run.json"
