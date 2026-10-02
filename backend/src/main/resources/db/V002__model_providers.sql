CREATE TABLE model_providers (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  kind TEXT NOT NULL,
  base_url TEXT NOT NULL,
  model_name TEXT NOT NULL,
  notes TEXT NOT NULL DEFAULT '',
  temperature REAL NOT NULL DEFAULT 0.7,
  context_window INTEGER NOT NULL DEFAULT 32768,
  credential_id TEXT NOT NULL,
  active INTEGER NOT NULL DEFAULT 0,
  revision INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  tested_at TEXT,
  latency_ms INTEGER,
  test_error TEXT
);
CREATE UNIQUE INDEX one_active_model_provider ON model_providers(active) WHERE active=1;
