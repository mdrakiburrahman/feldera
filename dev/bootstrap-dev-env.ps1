#Requires -RunAsAdministrator
#Requires -Version 7.0

param(
    [string]$Branch = (git -C $PSScriptRoot rev-parse --abbrev-ref HEAD),
    [string]$LinuxUser = ($env:USERNAME.ToLower() -replace '[^a-z0-9_-]', '')
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'initialize-vscode.ps1')

if (-not (git credential-manager --version 2>$null)) {
    winget install -e --id Git.GCM --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
    if ($LASTEXITCODE) { throw 'Could not install Git Credential Manager.' }
    $env:PATH = [Environment]::GetEnvironmentVariable('PATH', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('PATH', 'User')
}
$gcm = @((Get-Command git-credential-manager -ErrorAction SilentlyContinue).Source, "$(Split-Path (Split-Path (git --exec-path)))\bin\git-credential-manager.exe") |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $gcm) { throw 'Git Credential Manager executable was not found.' }

$origin = git -C $PSScriptRoot remote get-url origin
$env:GCM_INTERACTIVE = 'never'
$env:GIT_TERMINAL_PROMPT = '0'
git ls-remote --exit-code --heads $origin $Branch | Out-Null
if ($LASTEXITCODE) { throw "Cannot read $Branch from $origin without a prompt. Push the branch and sign in before retrying." }

$sshDir = Join-Path $env:USERPROFILE '.ssh'
New-Item -ItemType Directory -Path $sshDir -Force | Out-Null
$knownHosts = Join-Path $sshDir 'known_hosts'
if (-not (Test-Path $knownHosts)) {
    New-Item -ItemType File -Path $knownHosts | Out-Null
}
if ((Get-Item $knownHosts).Length -eq 0) {
    Set-Content -Path $knownHosts -Value '# Avoid vscode-remote-release/issues/11480 with an empty known_hosts.'
}
icacls $knownHosts /inheritance:r /grant:r "$($env:USERNAME):F" | Out-Null
if ($LASTEXITCODE) { throw 'Could not set known_hosts permissions.' }

$dockerDesktop = Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue
foreach ($process in $dockerDesktop) {
    Stop-Process -Id $process.Id -Force
}
if (Test-Path "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe") {
    winget uninstall --id Docker.DockerDesktop --silent --force --accept-source-agreements --disable-interactivity
    if ($LASTEXITCODE) { throw 'Could not uninstall Docker Desktop.' }
}

Add-Type -AssemblyName UIAutomationClient
[Windows.Automation.AutomationElement]::RootElement.FindAll('Children', [Windows.Automation.Condition]::TrueCondition) |
    Where-Object { $_.Current.Name -match '\[(Dev Container|WSL)\b.*Visual Studio Code' } |
    ForEach-Object { $_.GetCurrentPattern([Windows.Automation.WindowPattern]::Pattern).Close() }

$distros = (wsl -l -q) | ForEach-Object { $_.Replace("`0", '').Trim() } | Where-Object { $_ }
foreach ($distro in $distros) {
    wsl --unregister $distro
    if ($LASTEXITCODE) { throw "Could not unregister $distro." }
}

$memGB = [math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
$cpu = [Environment]::ProcessorCount
$swapGB = [math]::Floor($memGB / 4)
$drive = Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' |
    Where-Object { $_.VolumeName -notmatch 'Temporary' } |
    Sort-Object FreeSpace -Descending | Select-Object -First 1
if (-not $drive) { throw 'No fixed drive is available for the WSL VHD.' }
$wslLocation = "$($drive.DeviceID)\VHD"
New-Item -ItemType Directory -Path $wslLocation -Force | Out-Null
if ($drive.FreeSpace -lt 100GB) {
    Write-Warning 'Feldera release and image builds may need more than 100 GB of free disk space.'
}
if ($wslLocation -notin @((Get-MpPreference).ExclusionPath)) {
    Add-MpPreference -ExclusionPath $wslLocation
}

@"
[wsl2]
memory=${memGB}GB
processors=$cpu
swap=${swapGB}GB
networkingMode=NAT

[general]
instanceIdleTimeout=-1
"@ | Set-Content -Path "$env:USERPROFILE\.wslconfig"

wsl --shutdown
if ($LASTEXITCODE) { throw 'Could not shut down WSL.' }
wsl --update
if ($LASTEXITCODE) { throw 'Could not update WSL.' }

$previousEncoding = [Console]::OutputEncoding
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::Unicode
    $versionOutput = wsl --version
} finally {
    [Console]::OutputEncoding = $previousEncoding
}
$versionMatch = [regex]::Match(($versionOutput -join "`n"), '\d+\.\d+\.\d+')
if (-not $versionMatch.Success -or [version]$versionMatch.Value -lt [version]'2.4.4') {
    throw 'WSL 2.4.4 or newer is required for --location.'
}

wsl --install -d Ubuntu-24.04 --location $wslLocation --no-launch --web-download
if ($LASTEXITCODE) { throw 'Could not install Ubuntu-24.04.' }
wsl -d Ubuntu-24.04 -u root --exec bash -c 'useradd -m -s /bin/bash -G sudo "$1" && echo "$1 ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/$1" && chmod 0440 "/etc/sudoers.d/$1" && install -d -o "$1" /workspaces' bash $LinuxUser
if ($LASTEXITCODE) { throw "Could not create Linux user $LinuxUser." }
wsl --manage Ubuntu-24.04 --set-default-user $LinuxUser
if ($LASTEXITCODE) { throw "Could not make $LinuxUser the default WSL user." }

wsl -d Ubuntu-24.04 --cd ~ --exec bash -c 'git config --global user.name "$1" && git config --global user.email "$2" && git config --global credential.helper "$(printf %q "$(wslpath -u "$5")")" && GIT_TERMINAL_PROMPT=0 git clone --recurse-submodules --branch "$3" "$4" /workspaces/feldera' bash (git -C $PSScriptRoot config user.name) (git -C $PSScriptRoot config user.email) $Branch $origin $gcm
if ($LASTEXITCODE) { throw "Could not clone $Branch into /workspaces/feldera." }
