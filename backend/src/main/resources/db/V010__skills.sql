CREATE TABLE skills (id TEXT PRIMARY KEY, project_id TEXT, name TEXT NOT NULL, description TEXT NOT NULL, version TEXT NOT NULL, root_path TEXT NOT NULL, content_hash TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'manual', enabled INTEGER NOT NULL DEFAULT 1, auto_match INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(project_id,name));
CREATE INDEX idx_skills_scope ON skills(project_id,enabled);

CREATE UNIQUE INDEX idx_skills_unique_scope ON skills(coalesce(project_id,''),name);
CREATE TABLE skill_selections(task_id TEXT PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE, ids TEXT NOT NULL, auto_match INTEGER NOT NULL DEFAULT 1);
CREATE TABLE skill_loads(id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE, role TEXT NOT NULL, skill_id TEXT NOT NULL, name TEXT NOT NULL, version TEXT NOT NULL, content_hash TEXT NOT NULL, reason TEXT NOT NULL, estimated_tokens INTEGER NOT NULL, created_at TEXT NOT NULL);
CREATE INDEX idx_skill_loads_task_role ON skill_loads(task_id,role,created_at);
