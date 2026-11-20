#!/bin/sh
# Backup do Postgres do Portal Tech V&D via "docker exec pg_dump" — não depende de ter o
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
#   BACKEND_CONTAINER nome do container do backend, de onde sai o volume de anexos (default: agile-space-backend)
#   BACKUP_UPLOADS   1 inclui os anexos dos cards da Review (/data/uploads) no backup (default: 1)

set -eu

DB_CONTAINER="${DB_CONTAINER:-agile-space-db}"
DB_NAME="${DB_NAME:-espacoagil}"
DB_USER="${DB_USER:-postgres}"
BACKUP_DIR="${BACKUP_DIR:-$(dirname "$0")/../backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
BACKEND_CONTAINER="${BACKEND_CONTAINER:-agile-space-backend}"
BACKUP_UPLOADS="${BACKUP_UPLOADS:-1}"

mkdir -p "$BACKUP_DIR"

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
OUT_FILE="$BACKUP_DIR/${DB_NAME}_${TIMESTAMP}.sql.gz"

echo "[backup-db] Fazendo dump de '$DB_NAME' (container $DB_CONTAINER) -> $OUT_FILE"
# "pg_dump | gzip" esconderia a falha do pg_dump (o status do pipe é o do gzip, que sempre dá certo) e deixaria um
# arquivo vazio com cara de backup. Por isso: grava o SQL num temporário, só comprime se o pg_dump terminou bem e só
# renomeia depois de conferir que o dump chegou ao fim (o pg_dump fecha o arquivo com "dump complete").
SQL_PART="$OUT_FILE.sql.part"
if ! docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" "$DB_NAME" > "$SQL_PART"; then
  rm -f "$SQL_PART"
  echo "[backup-db] ERRO: pg_dump falhou em $DB_CONTAINER; nenhum backup foi gravado" >&2
  exit 1
fi
if ! tail -n 5 "$SQL_PART" | grep -q "PostgreSQL database dump complete"; then
  rm -f "$SQL_PART"
  echo "[backup-db] ERRO: o dump de '$DB_NAME' veio incompleto; nenhum backup foi gravado" >&2
  exit 1
fi
if ! gzip -c "$SQL_PART" > "$OUT_FILE.part"; then
  rm -f "$SQL_PART" "$OUT_FILE.part"
  echo "[backup-db] ERRO: não foi possível comprimir o dump (disco cheio?)" >&2
  exit 1
fi
rm -f "$SQL_PART"
mv "$OUT_FILE.part" "$OUT_FILE"

SIZE="$(du -h "$OUT_FILE" | cut -f1)"
echo "[backup-db] OK: $OUT_FILE ($SIZE)"

# Anexos dos cards da Review (PNG/JPEG/PDF): ficam no volume agile-space-uploads, fora do banco.
# Backup do banco sem eles deixaria linhas apontando para arquivos que não existem mais.
if [ "$BACKUP_UPLOADS" = "1" ]; then
  UPLOADS_FILE="$BACKUP_DIR/uploads_${TIMESTAMP}.tar.gz"
  # Grava num temporário e só renomeia no sucesso: falha no meio não pode deixar um .tar.gz
  # vazio com cara de backup válido.
  if ! docker exec "$BACKEND_CONTAINER" tar -C /data -czf - uploads > "$UPLOADS_FILE.part"; then
    rm -f "$UPLOADS_FILE.part"
    echo "[backup-db] ERRO: não foi possível empacotar os anexos de $BACKEND_CONTAINER:/data/uploads" >&2
    exit 1
  fi
  mv "$UPLOADS_FILE.part" "$UPLOADS_FILE"
  echo "[backup-db] OK anexos: $UPLOADS_FILE ($(du -h "$UPLOADS_FILE" | cut -f1))"
fi

echo "[backup-db] Removendo backups com mais de $RETENTION_DAYS dia(s) em $BACKUP_DIR"
find "$BACKUP_DIR" \( -name "${DB_NAME}_*.sql.gz" -o -name "uploads_*.tar.gz" \) -mtime "+$RETENTION_DAYS" -print -delete
