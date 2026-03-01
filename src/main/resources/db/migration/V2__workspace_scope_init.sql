CREATE TABLE IF NOT EXISTS workspace (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    is_personal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_workspace_owner_user_id ON workspace (owner_user_id);
CREATE INDEX idx_workspace_is_personal ON workspace (is_personal);

CREATE TABLE IF NOT EXISTS workspace_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workspace_member_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspace (id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_workspace_member_workspace_user ON workspace_member (workspace_id, user_id);
CREATE INDEX idx_workspace_member_user_id ON workspace_member (user_id);

ALTER TABLE conversation ADD COLUMN workspace_id BIGINT;

INSERT INTO workspace (name, owner_user_id, is_personal)
SELECT CONCAT('个人空间-', c.user_id), c.user_id, TRUE
FROM (SELECT DISTINCT user_id FROM conversation) c
LEFT JOIN workspace w ON w.owner_user_id = c.user_id AND w.is_personal = TRUE
WHERE w.id IS NULL;

INSERT INTO workspace_member (workspace_id, user_id, role)
SELECT w.id, w.owner_user_id, 'OWNER'
FROM workspace w
LEFT JOIN workspace_member wm ON wm.workspace_id = w.id AND wm.user_id = w.owner_user_id
WHERE w.is_personal = TRUE AND wm.id IS NULL;

UPDATE conversation c
SET workspace_id = (
    SELECT w.id
    FROM workspace w
    WHERE w.owner_user_id = c.user_id
      AND w.is_personal = TRUE
)
WHERE c.workspace_id IS NULL;

ALTER TABLE conversation MODIFY COLUMN workspace_id BIGINT NOT NULL;

ALTER TABLE conversation
    ADD CONSTRAINT fk_conversation_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspace (id);

CREATE INDEX idx_conversation_workspace_id ON conversation (workspace_id);
