CREATE TABLE skill_versions (id TEXT PRIMARY KEY, skill_id TEXT NOT NULL, version TEXT NOT NULL, content TEXT NOT NULL, content_hash TEXT NOT NULL, source_task_id TEXT, created_at TEXT NOT NULL);
CREATE INDEX idx_skill_versions_skill ON skill_versions(skill_id,created_at);
CREATE TABLE skill_evolution_runs (id TEXT PRIMARY KEY, task_id TEXT NOT NULL, trigger TEXT NOT NULL, status TEXT NOT NULL, diagnosis TEXT, model_id TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE INDEX idx_skill_evolution_runs_task ON skill_evolution_runs(task_id,created_at);
CREATE TABLE skill_evolution_candidates (id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES skill_evolution_runs(id), skill_id TEXT, project_id TEXT, name TEXT NOT NULL, description TEXT NOT NULL, content TEXT NOT NULL, base_content TEXT NOT NULL, base_hash TEXT, status TEXT NOT NULL, created_at TEXT NOT NULL, reviewed_at TEXT);
CREATE INDEX idx_skill_evolution_candidates_status ON skill_evolution_candidates(status,created_at);
CREATE TABLE skill_eval_cases (id TEXT PRIMARY KEY, candidate_id TEXT NOT NULL REFERENCES skill_evolution_candidates(id), prompt TEXT NOT NULL, expected_behavior TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE skill_eval_results (id TEXT PRIMARY KEY, candidate_id TEXT NOT NULL REFERENCES skill_evolution_candidates(id), status TEXT NOT NULL, report TEXT NOT NULL, created_at TEXT NOT NULL);
