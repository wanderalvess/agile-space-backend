#!/usr/bin/env bash
# Prepara VM Ubuntu 24.04 (Oracle Cloud): swap, Docker, firewall 80/443, clone dos repos.
# Rodar como usuario ubuntu: bash setup-vm.sh
set -euo pipefail

# Swap 4G (build do Next/Maven pode estourar RAM)
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 4G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

# Docker (repo oficial)
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"
fi

# Imagem Ubuntu da Oracle vem com iptables REJECT antes das regras; inserir 80/443 antes dele.
REJECT_N=$(sudo iptables -L INPUT --line-numbers -n | awk '/REJECT/ {print $1; exit}')
REJECT_N=${REJECT_N:-5}
sudo iptables -I INPUT "$REJECT_N" -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT "$REJECT_N" -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent
sudo netfilter-persistent save

# Repos lado a lado (o compose usa ../agile-space-frontend)
cd "$HOME"
[ -d agile-space-backend ]  || git clone https://github.com/wanderalvess/agile-space-backend.git
[ -d agile-space-frontend ] || git clone https://github.com/wanderalvess/agile-space-frontend.git

echo "Pronto. Reconecte o SSH (grupo docker), depois crie ~/agile-space-backend/.env com:"
echo "  DOMAIN, ALLOWED_ORIGINS=https://<DOMAIN>, DB_PASSWORD, APP_ENCRYPTION_SECRET, APP_JWT_SECRET,"
echo "  ALLOWED_EMAIL_DOMAIN, SPRING_PROFILES_ACTIVE=prod, NEXT_PUBLIC_API_URL=https://<DOMAIN>/api,"
echo "  NEXT_PUBLIC_SPRING_API_URL=https://<DOMAIN>/api, NEXT_PUBLIC_GOOGLE_CLIENT_ID (ver DEPLOYMENT.md)"
echo "  cd ~/agile-space-backend && docker compose --profile proxy up -d --build"
