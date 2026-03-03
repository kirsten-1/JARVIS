CREATE TABLE IF NOT EXISTS knowledge_retrieval_policy (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    mode VARCHAR(32) NOT NULL DEFAULT 'RECOMMEND',
    keyword_weight DOUBLE NULL,
    vector_weight DOUBLE NULL,
    hybrid_min_score DOUBLE NULL,
    max_candidates INT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_retrieval_policy_workspace UNIQUE (workspace_id),
    CONSTRAINT fk_knowledge_retrieval_policy_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
