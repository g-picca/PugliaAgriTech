#!/usr/bin/env bash
# Bootstrap "Vault as code" per l'ambiente AgriTech (Fase 2 dell'automazione
# README, vedi sezione 11 del riepilogo di tesi).
#
# Cosa fa, in ordine, ed e' pensato per essere rieseguito senza danni
# (idempotente) sia su un ambiente completamente nuovo sia su uno gia' in uso:
#   1. Attende che il container Vault risponda.
#   2. Se non e' mai stato inizializzato, lo inizializza (Shamir, 3 share /
#      soglia 2) e salva chiavi di unseal + root token in vault-init.json
#      (file locale, MAI versionato: senza di esso i dati restano illeggibili
#      per sempre, e' l'unica copia delle chiavi).
#   3. Se e' sigillato, lo sblocca con le chiavi salvate.
#   4. Abilita il secrets engine KV v2 su 'secret/' e il metodo di
#      autenticazione AppRole, se non gia' presenti.
#   5. Scrive le policy Vault (una per microservizio, privilegio minimo: ogni
#      servizio legge solo i segreti che gli servono davvero) e i ruoli
#      AppRole corrispondenti.
#   6. Scrive i segreti applicativi. La chiave HMAC condivisa viene riusata da
#      sensors/.env se gia' esiste (ambiente di sviluppo gia' avviato),
#      altrimenti generata da zero. I client secret OAuth2 non vengono
#      duplicati come valori a se stanti nello script: sono letti dal realm
#      Keycloak esportato come codice (infrastructure/config/IAM,
#      Fase 1) per evitare che le due fonti si disallineino nel tempo.
#   7. Genera un file .env per ciascun microservizio Java (consumer-service,
#      report-service) con le credenziali AppRole (role_id/secret_id) che gli
#      servono per autenticarsi a Vault.
#
# Prerequisiti: Docker (il container 'agritech-vault' gia' avviato via
# docker compose), bash, openssl. Nessun'altra dipendenza esterna: il
# parsing JSON e' fatto con grep/awk per non aggiungere strumenti che
# potrebbero mancare sull'ambiente della commissione d'esame.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="$(cd "$INFRA_DIR/.." && pwd)"

VAULT_CONTAINER="agritech-vault"
INIT_FILE="$INFRA_DIR/vault-init.json"
REALM_FILE="$INFRA_DIR/config/IAM/agritech-realm.json"
SENSORS_ENV="$PROJECT_DIR/sensors/.env"

# --- utility di parsing (solo grep/awk, nessuna dipendenza esterna) --------

# Estrae il client secret di un client OAuth2 dal realm Keycloak esportato.
# Sfrutta l'ordine dei campi nell'export di kc.sh: "secret" compare sempre
# poco dopo "clientId" dello stesso client, prima del prossimo "clientId".
extract_client_secret() {
  local cid=$1 file=$2
  awk -v target="\"$cid\"" '
    /"clientId" :/ { match_found = ($0 ~ target) }
    match_found && /"secret" :/ {
      line=$0
      sub(/^[^"]*"secret" : "/, "", line)
      sub(/".*/, "", line)
      print line
      match_found = 0
      exit
    }
  ' "$file"
}

# Legge un campo stringa semplice "campo": "valore" da un JSON piatto
# (usato solo su vault-init.json, il cui formato e' generato da noi stessi
# tramite 'vault operator init -format=json').
json_get_field() {
  local field=$1 file=$2
  grep -o "\"$field\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$file" | head -1 | sed -E 's/.*"([^"]+)"$/\1/'
}

# Legge un array di stringhe "campo": [ "a", "b", "c" ], una per riga.
json_get_array() {
  local file=$1 field=$2
  awk -v field="\"$field\"" '
    $0 ~ field {inarr=1; next}
    inarr && /\]/ {inarr=0}
    inarr {
      line=$0
      gsub(/^[ \t]+|[ \t,]+$/, "", line)
      gsub(/^"|"$/, "", line)
      if (line != "") print line
    }
  ' "$file"
}

vault_cli() {
  docker exec -e VAULT_ADDR=http://127.0.0.1:8200 "$VAULT_CONTAINER" vault "$@"
}

vault_authed() {
  docker exec -i -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN="$ROOT_TOKEN" "$VAULT_CONTAINER" vault "$@"
}

# Alcuni strumenti Windows (es. 'Out-File' di PowerShell) scrivono file di
# testo in UTF-16 anziche' UTF-8: grep/awk li leggono come binari e falliscono
# silenziosamente. Se vault-init.json risulta in UTF-16 (rilevato dal BOM),
# lo converte in UTF-8 in place prima di leggerlo.
normalize_encoding() {
  local f=$1 bom
  [ -f "$f" ] || return 0
  bom=$(head -c 2 "$f" | od -An -tx1 | tr -d ' \n')
  if [ "$bom" = "fffe" ] || [ "$bom" = "feff" ]; then
    local tmp; tmp=$(mktemp)
    if [ "$bom" = "fffe" ]; then
      iconv -f UTF-16LE -t UTF-8 "$f" > "$tmp"
    else
      iconv -f UTF-16BE -t UTF-8 "$f" > "$tmp"
    fi
    mv "$tmp" "$f"
  fi
}

fail() { echo "ERRORE: $1" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "docker non trovato nel PATH."
command -v openssl >/dev/null 2>&1 || fail "openssl non trovato nel PATH."
docker inspect "$VAULT_CONTAINER" >/dev/null 2>&1 || fail "container '$VAULT_CONTAINER' non trovato. Avviare prima l'infrastruttura (docker compose up -d)."

# --- 1. attesa readiness ----------------------------------------------------

echo "==> [1/6] Attendo che Vault sia raggiungibile..."
READY=false
for _ in $(seq 1 30); do
  STATUS_TEXT=$(docker exec "$VAULT_CONTAINER" vault status 2>&1 || true)
  if echo "$STATUS_TEXT" | grep -q "Initialized"; then READY=true; break; fi
  sleep 2
done
[ "$READY" = true ] || fail "Vault non risponde dopo 60s. Controllare 'docker logs $VAULT_CONTAINER'."

# --- 2. init se necessario ---------------------------------------------------

echo "==> [2/6] Verifico stato di inizializzazione..."
STATUS_TEXT=$(docker exec "$VAULT_CONTAINER" vault status 2>&1 || true)

if echo "$STATUS_TEXT" | grep -qi "Initialized.*false"; then
  echo "    Vault non inizializzato: eseguo l'init (Shamir, 3 share / soglia 2)..."
  docker exec "$VAULT_CONTAINER" vault operator init -key-shares=3 -key-threshold=2 -format=json > "$INIT_FILE"
  chmod 600 "$INIT_FILE" 2>/dev/null || true
  echo "    Chiavi di unseal e root token salvati in: $INIT_FILE"
  echo "    ATTENZIONE: questo file non e' versionato (.gitignore). E' l'unica copia delle chiavi: senza di esso i segreti in Vault restano illeggibili per sempre. Conservarlo al sicuro."
else
  echo "    Vault gia' inizializzato in precedenza."
  [ -f "$INIT_FILE" ] || fail "Vault risulta gia' inizializzato ma $INIT_FILE non esiste in locale: senza le chiavi di unseal originali non e' possibile sbloccarlo. Se l'ambiente e' nuovo, rimuovere il volume Docker 'vault-data' e i dati di Vault nel database Postgres, poi rilanciare lo script."
fi

normalize_encoding "$INIT_FILE"
ROOT_TOKEN=$(json_get_field root_token "$INIT_FILE")
[ -n "$ROOT_TOKEN" ] || fail "impossibile leggere 'root_token' da $INIT_FILE."
# Soglia fissa, coerente con '-key-threshold=2' usato in fase di init poco
# sopra: valore numerico (non tra virgolette) nel JSON di Vault, per questo
# non e' letto con json_get_field (pensato solo per campi stringa).
THRESHOLD=2
UNSEAL_KEYS=()
while IFS= read -r key; do UNSEAL_KEYS+=("$key"); done < <(json_get_array "$INIT_FILE" unseal_keys_b64)
[ "${#UNSEAL_KEYS[@]}" -ge "$THRESHOLD" ] || fail "trovate solo ${#UNSEAL_KEYS[@]} chiavi di unseal in $INIT_FILE, ne servono almeno $THRESHOLD."

# --- 3. unseal se necessario -------------------------------------------------

echo "==> [3/6] Verifico lo stato di seal..."
STATUS_TEXT=$(docker exec "$VAULT_CONTAINER" vault status 2>&1 || true)
if echo "$STATUS_TEXT" | grep -qi "Sealed.*true"; then
  echo "    Vault sigillato: invio $THRESHOLD chiavi di unseal..."
  for ((i = 0; i < THRESHOLD; i++)); do
    docker exec "$VAULT_CONTAINER" vault operator unseal "${UNSEAL_KEYS[$i]}" >/dev/null
  done
  echo "    Vault sbloccato."
else
  echo "    Vault gia' sbloccato."
fi

# --- 4. secrets engine + auth method -----------------------------------------

echo "==> [4/6] Configuro secrets engine e metodo di autenticazione..."
if vault_authed secrets list 2>/dev/null | grep -q "^secret/"; then
  echo "    KV v2 su 'secret/' gia' presente."
else
  vault_authed secrets enable -path=secret -version=2 kv >/dev/null
  echo "    Abilitato KV v2 su 'secret/'."
fi

if vault_authed auth list 2>/dev/null | grep -q "^approle/"; then
  echo "    AppRole gia' abilitato."
else
  vault_authed auth enable approle >/dev/null
  echo "    Abilitato metodo di autenticazione AppRole."
fi

# --- 5. policy e ruoli AppRole (privilegio minimo) ---------------------------

echo "==> [5/6] Scrivo le policy e i ruoli AppRole..."

write_policy() {
  local name=$1 hcl=$2
  printf '%s' "$hcl" | vault_authed policy write "$name" - >/dev/null
}

# consumer-dati: verifica firma sensori (shared) + persistenza (database) +
# proprio client secret OAuth2.
write_policy consumer-dati-policy '
path "secret/data/agritech/consumer-dati" { capabilities = ["read"] }
path "secret/data/agritech/shared"        { capabilities = ["read"] }
path "secret/data/agritech/database"      { capabilities = ["read"] }
'

# consumer-report: nessun accesso al database ne' alla chiave HMAC (non gli
# servono, per design architetturale, sezione 9 del riepilogo) - solo il
# proprio client secret.
write_policy consumer-report-policy '
path "secret/data/agritech/consumer-report" { capabilities = ["read"] }
'

write_policy producer-rest-policy '
path "secret/data/agritech/producer-rest" { capabilities = ["read"] }
path "secret/data/agritech/shared"        { capabilities = ["read"] }
'

for role in consumer-dati consumer-report producer-rest; do
  vault_authed write "auth/approle/role/$role" \
    token_policies="$role-policy" \
    token_ttl=1h \
    token_max_ttl=4h \
    secret_id_ttl=0 \
    secret_id_num_uses=0 >/dev/null
done
echo "    Policy e ruoli AppRole scritti."

# --- 6. segreti applicativi ---------------------------------------------------

echo "==> [6/6] Scrivo i segreti applicativi..."

if [ -f "$SENSORS_ENV" ] && grep -q "^HMAC_KEY=" "$SENSORS_ENV"; then
  HMAC_KEY=$(grep "^HMAC_KEY=" "$SENSORS_ENV" | head -1 | cut -d= -f2-)
  echo "    Chiave HMAC riutilizzata da sensors/.env (ambiente gia' esistente)."
else
  HMAC_KEY=$(openssl rand -base64 32)
  mkdir -p "$(dirname "$SENSORS_ENV")"
  printf 'HMAC_KEY=%s\n' "$HMAC_KEY" >> "$SENSORS_ENV"
  echo "    Chiave HMAC generata e scritta in sensors/.env (nuovo ambiente)."
fi
vault_authed kv put secret/agritech/shared hmac-secret-key="$HMAC_KEY" >/dev/null

vault_authed kv put secret/agritech/database \
  postgres-url="jdbc:postgresql://localhost:5433/agritech_db" \
  postgres-username="agritech_admin" \
  postgres-password="agritech_admin" >/dev/null

[ -f "$REALM_FILE" ] || fail "realm Keycloak non trovato: $REALM_FILE (eseguire prima il bootstrap Keycloak, Fase 1)."
for client in consumer-dati consumer-report producer-rest; do
  SECRET=$(extract_client_secret "$client" "$REALM_FILE")
  [ -n "$SECRET" ] || fail "client '$client' non trovato (o privo di secret) in $REALM_FILE."
  vault_authed kv put "secret/agritech/$client" keycloak-client-secret="$SECRET" >/dev/null
done
echo "    Segreti applicativi scritti (chiave HMAC, credenziali database, client secret OAuth2)."

echo ""
echo "==> Genero le credenziali AppRole per i microservizi Java..."

write_service_env() {
  local role=$1 target=$2
  local role_id secret_id
  role_id=$(vault_authed read -field=role_id "auth/approle/role/$role/role-id")
  secret_id=$(vault_authed write -f -field=secret_id "auth/approle/role/$role/secret-id")
  {
    echo "VAULT_ROLE_ID=$role_id"
    echo "VAULT_SECRET_ID=$secret_id"
  } > "$target"
  echo "    $target scritto (ruolo AppRole: $role)."
}

write_service_env consumer-dati "$PROJECT_DIR/consumer-service/.env"
write_service_env consumer-report "$PROJECT_DIR/report-service/.env"

echo ""
echo "==> Bootstrap Vault completato."
echo "    Vault UI: http://localhost:8200/ui  (token: vedi $INIT_FILE)"
echo "    Ricordare di esportare le variabili di consumer-service/.env e"
echo "    report-service/.env nell'ambiente prima di avviare i due servizi"
echo "    Spring Boot (vedi README, sezione avvio manuale)."
