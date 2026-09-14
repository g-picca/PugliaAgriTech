# Le secret saranno salvate cifrate nel db
storage "postgresql"{
  connection_url = "postgres://vault_admin:vault_admin@postgres:5432/agritech_db?sslmode=disable"
  table = "vault_kv_store"
}

# Listener HTTP (HTTPS per produzione)
listener "tcp" {
  address = "0.0.0.0:8200"
  tls_disable = 1 # TLS disabilitato solo per sviluppo
}

ui = true
log_level = "info"