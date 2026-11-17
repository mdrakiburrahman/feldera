#!/usr/bin/env bash
set -euo pipefail

export PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -v '^/mnt/' | paste -sd ':')"

sudo apt-get update -q
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl gnupg jq wslu
sudo install -m 0755 -d /etc/apt/keyrings

if ! command -v az >/dev/null 2>&1; then
  curl -fsSL https://aka.ms/InstallAzureCLIDeb | sudo bash
fi

if ! command -v gh >/dev/null 2>&1; then
  curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg |
    sudo tee /etc/apt/keyrings/github-cli.gpg >/dev/null
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/github-cli.gpg] https://cli.github.com/packages stable main" |
    sudo tee /etc/apt/sources.list.d/github-cli.list >/dev/null
  sudo apt-get update -q
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y gh
fi

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg |
    sudo tee /etc/apt/keyrings/docker.asc >/dev/null
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $VERSION_CODENAME stable" |
    sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update -q
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
# The current WSL shell and the non-root workspace user need the mounted socket.
sudo chmod 666 /var/run/docker.sock

if ! command -v npm >/dev/null 2>&1; then
  curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash -
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs
fi
sudo npm install -g @devcontainers/cli

docker --version
devcontainer --version
