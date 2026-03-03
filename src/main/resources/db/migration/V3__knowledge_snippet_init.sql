CREATE TABLE IF NOT EXISTS knowledge_snippet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    tags VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_knowledge_snippet_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspace (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_snippet_workspace_id ON knowledge_snippet (workspace_id);
CREATE INDEX idx_knowledge_snippet_workspace_updated_at ON knowledge_snippet (workspace_id, updated_at);
CREATE INDEX idx_knowledge_snippet_workspace_created_by ON knowledge_snippet (workspace_id, created_by);
