# Wrapper Windows per scripts/start.sh: l'implementazione vera (avvio
# container, attesa readiness, bootstrap Vault completo - init/unseal/
# policy/segreti, non solo unseal) vive in un unico script bash per evitare
# di mantenere due logiche duplicate che rischiano di disallinearsi (vedi
# sezione 11 del riepilogo di tesi). Richiede Git Bash (incluso in Git for
# Windows, gia' un prerequisito per clonare il repository).

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$bash = Get-Command bash.exe -ErrorAction SilentlyContinue
if (-not $bash) {
    $gitBash = "C:\Program Files\Git\bin\bash.exe"
    if (Test-Path $gitBash) {
        $bash = $gitBash
    } else {
        Write-Error "bash.exe non trovato. Installare Git for Windows (include Git Bash) e riprovare: https://git-scm.com/download/win"
        exit 1
    }
} else {
    $bash = $bash.Source
}

& $bash "$scriptDir/start.sh"
exit $LASTEXITCODE