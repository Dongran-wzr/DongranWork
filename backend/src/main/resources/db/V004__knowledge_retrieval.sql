CREATE TABLE knowledge_retrieval_settings (id INTEGER PRIMARY KEY CHECK(id=1), document TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 1);
CREATE TABLE knowledge_chunks (
  id TEXT PRIMARY KEY,
  document_id TEXT NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  content TEXT NOT NULL,
  vector TEXT,
  vector_signature TEXT,
  UNIQUE(document_id,ordinal)
);
CREATE INDEX knowledge_chunks_document ON knowledge_chunks(document_id);
CREATE VIRTUAL TABLE knowledge_chunks_fts USING fts5(chunk_id UNINDEXED,name,content, tokenize='unicode61');
