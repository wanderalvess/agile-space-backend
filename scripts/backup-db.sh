#!/bin/sh
# Backup do Postgres do Agile Space via "docker exec pg_dump" — não depende de ter o
# cliente do Postgres instalado no host, só do container "db" (docker-compose.yml) estar
# de pé. Pensado pra rodar via cron/systemd timer no servidor de produção, sem argumentos.
#
# Uso:
#   ./scripts/backup-db.sh
#
# Variáveis de ambiente (todas opcionais, com default batendo com docker-compose.yml):
#   DB_CONTAINER   nome do container do Postgres (default: agile-space-db)
#   DB_NAME        nome do banco (default: espacoagil)
#   DB_USER        usuário do Postgres (default: postgres)
#   BACKUP_DIR     pasta onde salvar os .sql.gz (default: ./backups)
#   RETENTION_DAYS backups mais velhos que isso são apagados (default: 14)

set -eu

DB_CONTAINER="${DB_CONTAINER:-agile-space-db}"
DB_NAME="${DB_NAME:-espacoagil}"
DB_USER="${DB_USER:-postgres}"
BACKUP_DIR="${BACKUP_DIR:-$(dirname "$0")/../backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

mkdir -p "$BACKUP_DIR"

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
OUT_FILE="$BACKUP_DIR/${DB_NAME}_${TIMESTAMP}.sql.gz"

echo "[backup-db] Fazendo dump de '$DB_NAME' (container $DB_CONTAINER) -> $OUT_FILE"
docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$OUT_FILE"

SIZE="$(du -h "$OUT_FILE" | cut -f1)"
echo "[backup-db] OK: $OUT_FILE ($SIZE)"

echo "[backup-db] Removendo backups com mais de $RETENTION_DAYS dia(s) em $BACKUP_DIR"
find "$BACKUP_DIR" -name "${DB_NAME}_*.sql.gz" -mtime "+$RETENTION_DAYS" -print -delete
