# Wrapper Windows per scripts/start.sh: l'implementazione vera (avvio
# container, attesa readiness, bootstrap Vault completo - init/unseal/
# policy/segreti, non solo unseal) vive in un unico script bash per evitare
# di mantenere due logiche duplicate che rischiano di disallinearsi (vedi
# sezione 11 del riepilogo di tesi). Richiede Git Bash (incluso in Git for
# Windows, gia' un prerequisito per clonare il repository).

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Va individuato esplicitamente Git Bash, non un generico "bash.exe" trovato
# sul PATH: Windows 10/11 include quasi sempre anche C:\Windows\System32\bash.exe,
# il lanciatore di WSL, spesso precedente a Git Bash nel PATH anche quando WSL
# non ha una distribuzione funzionante (o ne ha una, ma con un filesystem/rete
# Docker incompatibili con questo script, pensato per l'ambiente MSYS di Git
# Bash). Si preferisce quindi sempre un percorso esplicito noto a Git Bash,
# usando la ricerca sul PATH solo come ultima risorsa.
$gitBashCandidates = @(
    "$env:ProgramFiles\Git\bin\bash.exe",
    "${env:ProgramFiles(x86)}\Git\bin\bash.exe"
)
$git = Get-Command git.exe -ErrorAction SilentlyContinue
if ($git) {
    # git.exe e' gia' un prerequisito del progetto: da "...\Git\cmd\git.exe"
    # si risale alla radice dell'installazione e si deriva "...\Git\bin\bash.exe",
    # affidabile indipendentemente da dove Git sia stato installato.
    $gitRoot = Split-Path -Parent (Split-Path -Parent $git.Source)
    $gitBashCandidates = @("$gitRoot\bin\bash.exe") + $gitBashCandidates
}

$bash = $gitBashCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $bash) {
    Write-Error "Git Bash (bash.exe) non trovato. Installare Git for Windows (include Git Bash) e riprovare: https://git-scm.com/download/win"
    exit 1
}

# Non passare a bash.exe un percorso Windows assoluto con backslash
# ("C:\Users\...\scripts"): per bash il backslash e' il carattere di
# escape, quindi ogni sequenza "\<lettera>" non riconosciuta viene rimossa
# in fase di parsing degli argomenti, corrompendo il percorso (es.
# "C:\Users\nome\..." diventa "C:Usersnome..." -> "No such file or
# directory"). Ci si sposta invece nella cartella dello script e lo si
# invoca con il solo nome del file, senza backslash coinvolti.
Push-Location $scriptDir
& $bash "start.sh"
$exitCode = $LASTEXITCODE
Pop-Location
exit $exitCode