ALTER TABLE knowledge_chunks ADD COLUMN vector_blob BLOB;
ALTER TABLE knowledge_chunks ADD COLUMN vector_dimension INTEGER;
CREATE INDEX knowledge_vector_signature ON knowledge_chunks(vector_signature);
