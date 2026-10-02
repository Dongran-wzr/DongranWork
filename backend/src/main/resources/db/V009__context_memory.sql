CREATE TABLE memories (id TEXT PRIMARY KEY, project_id TEXT, title TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'active', pinned INTEGER NOT NULL DEFAULT 0, source_task TEXT, source_message INTEGER, source_quote TEXT, origin TEXT NOT NULL DEFAULT 'manual', version INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE INDEX idx_memories_scope ON memories(project_id,status);
CREATE TABLE memory_versions (id INTEGER PRIMARY KEY AUTOINCREMENT, memory_id TEXT NOT NULL, document TEXT NOT NULL, created_at TEXT NOT NULL);
INSERT OR IGNORE INTO memories(id,project_id,title,content,created_at,updated_at)
 SELECT json_extract(j.value,'$.id'),json_extract(j.value,'$.projectId'),json_extract(j.value,'$.title'),json_extract(j.value,'$.content'),json_extract(j.value,'$.createdAt'),json_extract(j.value,'$.updatedAt') FROM preferences p,json_each(p.value) j WHERE p.key='memoryEntries';
DELETE FROM preferences WHERE key='memoryEntries';
CREATE TABLE context_states (task_id TEXT PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE, document TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE TABLE context_evidence (id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE, role TEXT NOT NULL, kind TEXT NOT NULL, document TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE INDEX idx_context_evidence_task ON context_evidence(task_id);
CREATE TABLE context_snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE, role TEXT NOT NULL, document TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE tool_executions (id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE, role TEXT NOT NULL, call_id TEXT NOT NULL, name TEXT NOT NULL, arguments TEXT NOT NULL, status TEXT NOT NULL, result TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(task_id,role,call_id));
CREATE TABLE context_drafts (id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE, path TEXT NOT NULL, expected_hash TEXT NOT NULL, content TEXT NOT NULL DEFAULT '', next_chunk INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'open', updated_at TEXT NOT NULL);
