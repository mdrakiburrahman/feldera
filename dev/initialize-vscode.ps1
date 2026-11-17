$ErrorActionPreference = 'Stop'

function Invoke-Code {
    param([string[]]$Arguments)
    code @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "VS Code command 'code $($Arguments -join ' ')' failed with exit code $LASTEXITCODE."
    }
}

Invoke-Code -Arguments @('--version')
$installedExtensions = @(Invoke-Code -Arguments @('--list-extensions'))
if ('Microsoft.wavework' -in $installedExtensions) {
    Invoke-Code -Arguments @('--uninstall-extension', 'Microsoft.wavework')
}
Invoke-Code -Arguments @('--install-extension', 'ms-vscode-remote.remote-wsl')
Invoke-Code -Arguments @('--install-extension', 'ms-vscode-remote.remote-containers')
