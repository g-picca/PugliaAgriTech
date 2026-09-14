#!/usr/bin/env bash
# Avvio completo dell'ambiente AgriTech (Fase 3 dell'automazione README,
# sezione 11 del riepilogo di tesi): un solo comando per portare su tutto
# il sistema pronto all'uso, dall'infrastruttura (Postgres, Vault, Keycloak,
# RabbitMQ) fino ai due microservizi Java, containerizzati - incluso il
# bootstrap di Vault (init/unseal/policy/segreti, sezione 11.6-11.10) e
# l'import del realm Keycloak (sezione 11.1-11.5). Pensato per essere
# idempotente: puo' essere rilanciato su un ambiente gia' avviato senza
# effetti indesiderati.
#
# Uso:
#   ./scripts/start.sh              avvia tutto, inclusi i microservizi Java
#   ./scripts/start.sh --infra-only si ferma dopo l'infrastruttura: pensato per
#                                    lo sviluppo locale di consumer-service/
#                                    report-service da IDE o mvnw (non
#                                    documentato nel README, rivolto alla
#                                    commissione: solo la modalita' container)

set -euo pipefail

INFRA_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --infra-only) INFRA_ONLY=true ;;
    *) echo "Argomento non riconosciuto: $arg (uso: start.sh [--infra-only])" >&2; exit 1 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$INFRA_DIR"

fail() { echo "ERRORE: $1" >&2; exit 1; }

echo "==> Verifico i prerequisiti..."
command -v docker >/dev/null 2>&1 || fail "docker non trovato nel PATH. Installare Docker Desktop (vedi README, sezione Prerequisiti)."
command -v curl >/dev/null 2>&1 || fail "curl non trovato nel PATH."
docker info >/dev/null 2>&1 || fail "Docker non risulta in esecuzione. Avviare Docker Desktop e rilanciare questo script."

if [ ! -f .env ]; then
  [ -f .env.example ] || fail "infrastructure/.env.example non trovato: impossibile creare .env."
  cp .env.example .env
  echo "    Creato infrastructure/.env da .env.example (credenziali di sviluppo/demo, vedi sezione 11.5 del riepilogo tesi)."
else
  echo "    infrastructure/.env gia' presente."
fi

echo ""
echo "==> [1/6] Avvio i container di infrastruttura (postgres, vault, keycloak, rabbitmq)..."
docker compose up -d

echo ""
echo "==> [2/6] Attendo che Postgres sia pronto..."
POSTGRES_OK=false
for _ in $(seq 1 30); do
  status=$(docker inspect -f '{{.State.Health.Status}}' agritech-postgres 2>/dev/null || echo "starting")
  if [ "$status" = "healthy" ]; then POSTGRES_OK=true; break; fi
  sleep 2
done
[ "$POSTGRES_OK" = true ] || fail "Postgres non risulta 'healthy' dopo 60s. Controllare: docker logs agritech-postgres"

echo ""
echo "==> [3/6] Attendo che Keycloak importi/carichi il realm 'agritech'..."
KEYCLOAK_OK=false
for _ in $(seq 1 45); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/realms/agritech/protocol/openid-connect/certs 2>/dev/null || echo 000)
  if [ "$code" = "200" ]; then KEYCLOAK_OK=true; break; fi
  sleep 2
done
[ "$KEYCLOAK_OK" = true ] || fail "Keycloak/realm 'agritech' non raggiungibile dopo 90s. Controllare: docker logs agritech-keycloak"

echo ""
echo "==> [4/6] Configuro Vault (init/unseal/policy/segreti, se necessario)..."
"$SCRIPT_DIR/vault-bootstrap.sh"

echo ""
echo "==> [5/6] Attendo che RabbitMQ sia pronto..."
RABBITMQ_OK=false
for _ in $(seq 1 30); do
  status=$(docker inspect -f '{{.State.Health.Status}}' agritech-rabbitmq 2>/dev/null || echo "starting")
  if [ "$status" = "healthy" ]; then RABBITMQ_OK=true; break; fi
  sleep 2
done
[ "$RABBITMQ_OK" = true ] || echo "    ATTENZIONE: RabbitMQ non risulta ancora 'healthy'. Controllare: docker logs agritech-rabbitmq"

if [ "$INFRA_ONLY" = true ]; then
  echo ""
  echo "==================================================================="
  echo " Infrastruttura pronta (--infra-only: microservizi Java non avviati)."
  echo "==================================================================="
  echo ""
  echo " Per avviarli da IDE o riga di comando (le credenziali Vault/AppRole"
  echo " sono gia' scritte in consumer-service/.env e report-service/.env):"
  echo "   cd consumer-service && set -a; source .env; set +a && ./mvnw spring-boot:run"
  echo "   cd report-service   && set -a; source .env; set +a && ./mvnw spring-boot:run"
  exit 0
fi

echo ""
echo "==> [6/6] Compilo e avvio consumer-service e report-service (container)..."
# Nome di progetto Compose esplicito e diverso da quello dell'infrastruttura
# (altrimenti Docker Compose segnala postgres/vault/keycloak/rabbitmq come
# "orphan containers", solo perche' non compaiono in questo file - stesso
# progetto per directory, file diversi).
docker compose -p agritech-apps -f docker-compose.apps.yml up -d --build

CONSUMER_OK=false
REPORT_OK=false
for _ in $(seq 1 40); do
  c=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8090/actuator/health 2>/dev/null || echo 000)
  r=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8091/actuator/health 2>/dev/null || echo 000)
  [ "$c" = "200" ] && CONSUMER_OK=true
  [ "$r" = "200" ] && REPORT_OK=true
  [ "$CONSUMER_OK" = true ] && [ "$REPORT_OK" = true ] && break
  sleep 3
done
[ "$CONSUMER_OK" = true ] || echo "    ATTENZIONE: consumer-service non risulta 'UP'. Controllare: docker logs agritech-consumer-service"
[ "$REPORT_OK" = true ] || echo "    ATTENZIONE: report-service non risulta 'UP'. Controllare: docker logs agritech-report-service"

echo ""
echo "==================================================================="
echo " Sistema pronto."
echo "==================================================================="
echo ""
echo " Servizi disponibili:"
echo "   consumer-service        : http://localhost:8090/actuator/health"
echo "   report-service          : http://localhost:8091/actuator/health"
echo "   Keycloak Admin Console  : http://localhost:8080  (admin/admin)"
echo "   RabbitMQ Management UI  : http://localhost:15672 (admin/admin)"
echo "   Vault UI                : http://localhost:8200/ui"
echo ""
echo " Prossimo passo: avviare i sensori simulati, vedi README sezione 5.3"
echo " (cd sensors && docker compose up -d --build)."
