ALTER TABLE command_runs ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'host-legacy';
ALTER TABLE command_runs ADD COLUMN sandbox_backend TEXT;
ALTER TABLE command_runs ADD COLUMN workspace_path TEXT;
ALTER TABLE command_runs ADD COLUMN sync_status TEXT;
