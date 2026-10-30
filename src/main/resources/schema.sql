-- pomelo-ai 建表脚本（MyBatis 版，替代 JPA ddl-auto）
-- 使用 IF NOT EXISTS 确保可重复执行

CREATE TABLE IF NOT EXISTS knowledge_base (
    id                  VARCHAR(36) PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    collection_name     VARCHAR(128),
    top_k               INT DEFAULT 8,
    rerank_top_k        INT DEFAULT 4,
    similarity_threshold DOUBLE DEFAULT 0.3,
    chunk_size          INT DEFAULT 500,
    chunk_overlap       INT DEFAULT 50,
    auto_vectorize      TINYINT(1) DEFAULT 0,
    chunk_strategy      VARCHAR(20) DEFAULT 'general',
    doc_count           INT DEFAULT 0,
    chunk_count         INT DEFAULT 0,
    created_at          DATETIME,
    updated_at          DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS doc (
    id                  VARCHAR(36) PRIMARY KEY,
    kb_id               VARCHAR(36) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    file_path           VARCHAR(500),
    file_size           BIGINT DEFAULT 0,
    mime_type           VARCHAR(100),
    sha256              VARCHAR(64),
    parsed_content      MEDIUMTEXT,
    status              VARCHAR(32) DEFAULT 'UPLOADED',
    chunk_count         INT DEFAULT 0,
    vectorized_chunks   INT DEFAULT 0,
    error_msg           VARCHAR(1000),
    created_at          DATETIME,
    updated_at          DATETIME,
    parsed_at           DATETIME,
    chunked_at          DATETIME,
    vectorized_at       DATETIME,
    folder_id           VARCHAR(36),
    enabled             TINYINT(1) DEFAULT 1,
    INDEX idx_doc_kb (kb_id),
    INDEX idx_doc_sha (kb_id, sha256),
    INDEX idx_doc_status (kb_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS doc_chunk (
    id                  VARCHAR(36) PRIMARY KEY,
    doc_id              VARCHAR(36) NOT NULL,
    kb_id               VARCHAR(36) NOT NULL,
    chunk_index         INT NOT NULL,
    content             MEDIUMTEXT NOT NULL,
    page                INT DEFAULT 0,
    char_count          INT DEFAULT 0,
    content_hash        VARCHAR(64),
    status              VARCHAR(32) DEFAULT 'PENDING',
    created_at          DATETIME,
    updated_at          DATETIME,
    INDEX idx_chunk_doc (doc_id),
    INDEX idx_chunk_kb (kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS meta_field_config (
    id                  VARCHAR(36) PRIMARY KEY,
    kb_id               VARCHAR(36) NOT NULL,
    field_key           VARCHAR(50) NOT NULL,
    field_name          VARCHAR(100) NOT NULL,
    field_type          VARCHAR(20) DEFAULT 'string',
    extract_type        VARCHAR(20) NOT NULL,
    extract_rule        TEXT,
    required            TINYINT(1) DEFAULT 0,
    searchable          TINYINT(1) DEFAULT 0,
    vectorized          TINYINT(1) DEFAULT 0,
    display_in_list     TINYINT(1) DEFAULT 0,
    sort_order          INT DEFAULT 0,
    enabled             TINYINT(1) DEFAULT 1,
    created_at          DATETIME,
    INDEX idx_meta_kb (kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS doc_metadata (
    id                  VARCHAR(36) PRIMARY KEY,
    doc_id              VARCHAR(36) NOT NULL,
    kb_id               VARCHAR(36) NOT NULL,
    field_key           VARCHAR(50) NOT NULL,
    field_name          VARCHAR(100),
    field_value         MEDIUMTEXT,
    created_at          DATETIME,
    updated_at          DATETIME,
    INDEX idx_meta_doc (doc_id),
    INDEX idx_meta_kb_field (kb_id, field_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS doc_folder (
    id                  VARCHAR(36) PRIMARY KEY,
    kb_id               VARCHAR(36) NOT NULL,
    parent_id           VARCHAR(36),
    name                VARCHAR(200) NOT NULL,
    sort_order          INT DEFAULT 0,
    created_at          DATETIME,
    INDEX idx_folder_kb (kb_id),
    INDEX idx_folder_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS doc_tag (
    id                  VARCHAR(36) PRIMARY KEY,
    kb_id               VARCHAR(36) NOT NULL,
    doc_id              VARCHAR(36) NOT NULL,
    tag                 VARCHAR(100) NOT NULL,
    created_at          DATETIME,
    INDEX idx_tag_kb (kb_id),
    INDEX idx_tag_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_user (
    id                  VARCHAR(36) PRIMARY KEY,
    username            VARCHAR(50) NOT NULL UNIQUE,
    password            VARCHAR(100) NOT NULL,
    nickname            VARCHAR(50),
    role                VARCHAR(20) DEFAULT 'user',
    enabled             TINYINT(1) DEFAULT 1,
    created_at          DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_session (
    id                  VARCHAR(36) PRIMARY KEY,
    kb_id               VARCHAR(36),
    title               VARCHAR(100),
    created_at          DATETIME,
    updated_at          DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message (
    id                  VARCHAR(36) PRIMARY KEY,
    session_id          VARCHAR(36) NOT NULL,
    role                VARCHAR(16) NOT NULL,
    content             TEXT NOT NULL,
    citations           TEXT,
    created_at          DATETIME,
    INDEX idx_msg_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
