# AgriTech Middleware — Monitoraggio Uliveti Puglia (PW12)

Middleware per il monitoraggio agronomico di uliveti in Puglia, finalizzato alla rilevazione precoce della Xylella fastidiosa e di altre patologie degli alberi. Sensori IoT (TreeTalker e stazioni Agrometeo) inviano letture periodiche a un broker AMQP protetto con autenticazione OAuth2 e firma HMAC; un microservizio le valida e le persiste su PostgreSQL; un secondo microservizio genera, con cadenza giornaliera, un report PDF aggregato scaricabile via REST.

Questo documento è pensato per chi deve eseguire il progetto nel proprio ambiente locale e verificarne il funzionamento, senza dover compilare o modificare codice. Ogni comando è pensato per essere eseguito così com'è, senza conoscenza pregressa dell'infrastruttura.

## Indice

1. [Architettura](#1-architettura)
2. [Struttura del repository](#2-struttura)
3. [Prerequisiti](#3-prerequisiti)
4. [Avvio rapido](#4-avvio-rapido)
5. [Avvio guidato, passo per passo](#5-avvio-guidato)
6. [Come verificare che tutto funzioni](#6-verifica)
7. [Risoluzione dei problemi comuni](#7-troubleshooting)

---

## 1. Architettura {#1-architettura}

```
 sensori C++ (TreeTalker, Agrometeo)
        │  MQTT + firma HMAC-SHA256
        ▼
   RabbitMQ  ◄── OAuth2 (Keycloak) ──► consumer-service ──► PostgreSQL
        ▲                                    ▲  │
        │  richiesta aggregazione (RPC)      │  │ marca "processed"
        └──────────── report-service ────────┘  │
                          │                      │
                          ▼                      │
                    genera PDF, lo salva       (dati non ripetuti
                    su disco, lo serve via     in report successivi)
                    endpoint REST

   HashiCorp Vault: segreti applicativi (chiave HMAC, credenziali
   PostgreSQL, client secret OAuth2) per consumer-service e report-service.
```

Tutti i componenti girano come container Docker, `consumer-service` e `report-service` compresi: le loro immagini vengono costruite automaticamente dal codice sorgente al primo avvio, senza bisogno di installare Java o Maven sulla macchina (sezione 5.2).

## 2. Struttura del repository {#2-struttura}

```
PROJECT/
├── infrastructure/           Docker Compose: Postgres, Vault, Keycloak, RabbitMQ
│   ├── config/
│   │   ├── IAM/               realm Keycloak esportato (as code)
│   │   ├── database/          script di init Postgres (utenti, schemi)
│   │   ├── queue/              configurazione RabbitMQ (OAuth2, code, chiave pubblica)
│   │   └── vault/               configurazione del server Vault
│   ├── scripts/
│   │   ├── start.sh            avvio completo del sistema (Linux/Mac/Git Bash)
│   │   ├── start.ps1           wrapper Windows (richiama start.sh via Git Bash)
│   │   └── vault-bootstrap.sh  init/unseal/configurazione di Vault
│   ├── docker-compose.yml      infrastruttura: Postgres, Vault, Keycloak, RabbitMQ
│   ├── docker-compose.apps.yml consumer-service e report-service containerizzati
│   └── .env.example            template delle variabili d'ambiente
├── consumer-service/          Spring Boot: ingestion sensori + query di aggregazione
│   └── Dockerfile              build multi-stage (JDK per compilare, JRE per eseguire)
├── report-service/            Spring Boot: generazione e download del report PDF
│   └── Dockerfile
└── sensors/                   Simulatori dei sensori IoT in C++ (Docker)
    └── .env.example
```

## 3. Prerequisiti {#3-prerequisiti}

Solo due strumenti, entrambi gratuiti e multipiattaforma, per eseguire e verificare l'intero sistema — sensori compresi — senza altro sulla macchina:

| Strumento | Perché serve | Link download |
|---|---|---|
| **Docker Desktop** | Esegue tutto: Postgres, Vault, Keycloak, RabbitMQ, i due microservizi Java e i sensori simulati | [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/) |
| **Git** | Clonare il repository. Su Windows include **Git Bash**, necessario per eseguire gli script di questo progetto | [git-scm.com/downloads](https://git-scm.com/downloads) |

Non serve installare Java, Maven, né il client CLI di Vault o Keycloak: la build dei due microservizi avviene interamente dentro i container Docker.

**Verifica dei prerequisiti** (da un terminale, Git Bash su Windows):

```bash
docker --version
git --version
```

**Windows**: Docker Desktop richiede il backend WSL2, che l'installer configura automaticamente alla prima esecuzione (potrebbe chiedere un riavvio).

## 4. Avvio rapido {#4-avvio-rapido}

Per chi vuole solo vedere il sistema funzionante, senza i dettagli di ogni passaggio — un solo comando avvia infrastruttura, segreti e i due microservizi (containerizzati, build inclusa):

```bash
git clone https://github.com/g-picca/PugliaAgriTech.git
cd PugliaAgriTech/PROJECT

./infrastructure/scripts/start.sh   # infrastruttura + Vault + consumer-service + report-service

cd sensors && docker compose up -d --build   # sensori simulati
```

Su Windows, `start.sh` si lancia anche con `infrastructure\scripts\start.ps1` da PowerShell (richiama automaticamente `start.sh` tramite Git Bash).

La prima esecuzione richiede qualche minuto in più (build delle immagini Docker dei due microservizi); le successive sono molto più rapide grazie alla cache di build. Dopo l'avvio, `http://localhost:8090/actuator/health` e `http://localhost:8091/actuator/health` devono rispondere `{"status":"UP"}`. Per generare subito un report senza aspettare lo scheduler notturno: `curl -X POST http://localhost:8091/api/reports/daily/generate`, poi scaricarlo da `curl -o report.pdf http://localhost:8091/api/reports/daily/latest`.

Se qualcosa non torna, la sezione 5 spiega ogni passaggio nel dettaglio e la sezione 7 elenca i problemi più comuni già incontrati durante lo sviluppo.

## 5. Avvio guidato, passo per passo {#5-avvio-guidato}

### 5.1 Infrastruttura

```bash
cd infrastructure
./scripts/start.sh
```

Lo script, in ordine:

1. Verifica che Docker sia installato e in esecuzione.
2. Crea `infrastructure/.env` da `.env.example` se non esiste già (credenziali di sviluppo fisse, non segreti reali — vedi documento di sintesi allegato alla tesi per il dettaglio di questa scelta).
3. Avvia i container di infrastruttura (`docker compose up -d`): PostgreSQL, Vault, Keycloak, RabbitMQ.
4. Attende che PostgreSQL sia pronto.
5. Attende che Keycloak abbia importato il realm `agritech` (client OAuth2, scope, chiave di firma — tutto già preconfigurato, incluso in `infrastructure/config/IAM/agritech-realm.json`).
6. Inizializza Vault (solo alla primissima esecuzione: genera le chiavi di unseal e il token root, salvati in `infrastructure/vault-init.json` — **file locale, non versionato**: se lo si perde e Vault ha già dei dati, quei dati diventano irrecuperabili) o lo sblocca (se già inizializzato in precedenza). Configura poi automaticamente i segreti applicativi e le credenziali (AppRole) che `consumer-service` e `report-service` useranno per leggerli.
7. Attende che RabbitMQ sia pronto.
8. Compila (con Docker, nessun Java richiesto sull'host) e avvia `consumer-service` e `report-service` come container (sezione 5.2).

Lo script è **idempotente**: si può rilanciare in qualunque momento (ad esempio dopo un riavvio del PC) senza effetti collaterali indesiderati — riconosce cosa è già configurato e salta i passaggi non necessari.

Al termine, sono raggiungibili:

- Keycloak Admin Console: <http://localhost:8080> (`admin` / `admin`)
- RabbitMQ Management UI: <http://localhost:15672> (`admin` / `admin`)
- Vault UI: <http://localhost:8200/ui> (token: quello in `infrastructure/vault-init.json`, campo `root_token`)

Le credenziali RabbitMQ/Keycloak sopra servono solo per accedere alle rispettive interfacce di amministrazione: i microservizi si autenticano con OAuth2 (Client Credentials), non con queste utenze.

### 5.2 consumer-service e report-service

Nessun comando aggiuntivo: `start.sh` (sezione 5.1) li compila e avvia da solo, come ultimo passo, costruendo un'immagine Docker per ciascuno (`consumer-service/Dockerfile`, `report-service/Dockerfile`). Restano in ascolto sulle porte `8090`/`8091`.

Log dei due servizi: `docker logs -f agritech-consumer-service` / `docker logs -f agritech-report-service`.

`report-service` è l'unico dei due con una vera API REST (`consumer-service` riceve tutto via RabbitMQ) ed espone la relativa documentazione OpenAPI/Swagger:

- Swagger UI (interattiva, per provare gli endpoint dal browser): <http://localhost:8091/swagger-ui.html>
- Specifica OpenAPI in JSON: <http://localhost:8091/v3/api-docs>

### 5.3 Sensori simulati

```bash
cd sensors
docker compose up -d --build
```

Richiede che l'infrastruttura (passo 5.1) sia già avviata: i sensori si collegano alla rete Docker creata da `infrastructure/docker-compose.yml` e leggono la stessa chiave HMAC scritta in `sensors/.env` dal bootstrap di Vault. Pubblicano una lettura ogni 60 secondi ciascuno (due sensori: `OLIVO_SEC_001` di tipo TreeTalker, `AGRO_STATION_SUD` di tipo Agrometeo) — un intervallo comunque compresso rispetto ai 10-30 minuti tipici di sensori reali di questo tipo, per restare osservabile durante una demo.

## 6. Come verificare che tutto funzioni {#6-verifica}

### 6.1 Health check

```bash
curl http://localhost:8090/actuator/health   # consumer-service
curl http://localhost:8091/actuator/health   # report-service
```

Entrambi devono rispondere `{"status":"UP", ...}`.

### 6.2 Verifica end-to-end

Con infrastruttura, sensori e i due microservizi tutti avviati (sezione 5):

1. **Osservare l'ingestion**: dopo una trentina di secondi, controllare i log di `consumer-service` (`docker logs -f agritech-consumer-service`) — deve comparire una riga per ogni lettura accettata (`ACCEPTED`). In alternativa, ispezionare la coda `sensor.data` dalla RabbitMQ Management UI (`Queues` → `sensor.data` → `Get messages`, oppure la scheda `Overview` per vedere il tasso di messaggi in ingresso).
2. **Generare un report senza aspettare la mezzanotte**:
   ```bash
   curl -X POST http://localhost:8091/api/reports/daily/generate
   ```
   Risposta attesa: conferma di generazione avvenuta con successo.
3. **Scaricare il PDF**:
   ```bash
   curl -o report.pdf http://localhost:8091/api/reports/daily/latest
   ```
   Aprendo `report.pdf`: intestazione con data/ora di generazione, una sezione per tipo di sensore (TREE_TALKER, AGROMETEO) con media/minimo/massimo/numero di campioni per ciascun campo, in italiano (es. "Temperatura Aria(°C)").
4. **Verificare il comportamento incrementale**: rigenerando il report subito dopo (`POST .../generate` di nuovo), il nuovo PDF deve riportare solo le letture arrivate nel frattempo, non quelle già incluse nel report precedente (il campo `processed` evita il doppio conteggio — vedi documento di sintesi allegato alla tesi per il dettaglio).

## 7. Risoluzione dei problemi comuni {#7-troubleshooting}

**"Docker non risulta in esecuzione"** — Avviare Docker Desktop e attendere che l'icona nella system tray indichi che è pronto, poi rilanciare `start.sh`.

**Una porta è già occupata (`bind: address already in use`)** — Questo progetto usa le porte 5433 (Postgres), 8080 (Keycloak), 8090/8091 (i due microservizi), 8200 (Vault), 5671/5672/1883/15672 (RabbitMQ). Verificare cosa occupa la porta (`netstat -ano | grep <porta>` su Windows/Git Bash, `lsof -i :<porta>` su Mac/Linux) e liberarla, oppure chiudere l'applicazione in conflitto (un'istanza Postgres locale già installata è la causa più comune per la 5433/5432).

**`consumer-service`/`report-service` non si aggiornano dopo una modifica al repository** — `docker compose up -d` da solo non ricostruisce l'immagine: serve esplicitamente `--build`:
```bash
cd infrastructure
docker compose -p agritech-apps -f docker-compose.apps.yml up -d --build
```

**Vault risulta "sealed" dopo un riavvio del PC** — Normale: Vault si sigilla automaticamente ogni volta che il processo riparte, per design di sicurezza. Rilanciare `./infrastructure/scripts/start.sh`: rileva lo stato sigillato e lo sblocca da solo usando le chiavi salvate in `vault-init.json`.

**"Vault è già inizializzato ma vault-init.json non esiste"** — Significa che il volume/i dati di Vault sono sopravvissuti da un ambiente precedente ma il file con le chiavi di unseal è andato perso: senza quelle chiavi i dati in Vault sono irrecuperabili by design. Se non servono dati già presenti in Vault, ripartire da zero: `docker compose down -v` dentro `infrastructure/` (rimuove anche il volume dati di Postgres, quindi anche i dati dei sensori raccolti finora), poi rilanciare `start.sh`.

**I sensori smettono di inviare dati dopo un riavvio di RabbitMQ** — Limite noto: il client MQTT dei sensori non si riconnette automaticamente. Riavviare i loro container: `cd sensors && docker compose restart`.

**Un file di configurazione modificato con un editor Windows (Notepad, o salvato da PowerShell) smette di funzionare inspiegabilmente** — Controllare l'encoding: PowerShell (`Out-File`, `Set-Content` senza `-Encoding`) e alcuni editor scrivono file di testo in UTF-16 invece di UTF-8. Gli script di questo repository lo gestiscono automaticamente dove serve (`vault-bootstrap.sh`), ma un file di configurazione applicativo (`.yaml`, `.sql`, `.conf`) salvato così può risultare illeggibile alle librerie che lo interpretano come UTF-8. Riconvertire con `iconv -f UTF-16LE -t UTF-8 file.txt > file_utf8.txt` (disponibile in Git Bash) o risalvare con un editor che permetta di scegliere esplicitamente l'encoding UTF-8 senza BOM.
