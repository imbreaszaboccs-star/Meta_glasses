# Downloads the small Vosk models into the Android assets (gitignored):
#  - English (vosk-model-en): "hey lens" wake word + English lecture keywords
#  - Czech   (vosk-model-cs): Czech lecture keywords (only used when the hint language is cs)
# Run once from anywhere:  powershell -File focuslens\tool\get_wake_model.ps1
$ErrorActionPreference = 'Stop'
function Get-Model($name, $dir) {
    $dest = Join-Path $PSScriptRoot "..\android\app\src\main\assets\$dir"
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("vosk-" + [guid]::NewGuid())
    New-Item -ItemType Directory $tmp | Out-Null
    try {
        Invoke-WebRequest "https://alphacephei.com/vosk/models/$name.zip" -OutFile "$tmp\m.zip"
        Expand-Archive "$tmp\m.zip" "$tmp\x"
        if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
        New-Item -ItemType Directory (Split-Path $dest) -Force | Out-Null
        Move-Item "$tmp\x\$name" $dest
        # StorageService.sync() copies the assets only when this file differs from the unpacked copy.
        Set-Content -NoNewline -Path "$dest\uuid" -Value ([guid]::NewGuid().ToString())
        Write-Host "Model ready: $dest"
    } finally {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
Get-Model 'vosk-model-small-en-us-0.15' 'vosk-model-en'
Get-Model 'vosk-model-small-cs-0.4-rhasspy' 'vosk-model-cs'
