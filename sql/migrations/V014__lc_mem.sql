-- SPEC-MEM (docs/spec/wayfarers-memory.md §2): observations with provenance, projects, notes, shared pages and their
-- frozen facts, milestones, session summaries and the per-character settings.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it. Each trigger is one line.

CREATE TABLE lc_mem_profile (
	char_id INT NOT NULL,
	enabled BOOLEAN NOT NULL DEFAULT TRUE,
	history_public BOOLEAN NOT NULL DEFAULT FALSE,
	collaborate_with_signal BOOLEAN NOT NULL DEFAULT FALSE,
	raw_retention_days SMALLINT NOT NULL DEFAULT 30,
	PRIMARY KEY (char_id),
	CONSTRAINT chk_mem_retention CHECK (raw_retention_days BETWEEN 1 AND 365)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- One row per (character, relation, subject, object, region, provenance): repeats coalesce into times and last_at.
CREATE TABLE lc_mem_observation (
	obs_id BIGINT NOT NULL AUTO_INCREMENT,
	char_id INT NOT NULL,
	relation ENUM('SOLD_BY', 'BOUGHT_FROM', 'BUYS', 'CRAFTS', 'PRICED_AT_NPC', 'OBTAINED_FROM', 'HUNTED', 'TALKED_TO', 'QUEST_FROM', 'QUEST_DONE', 'LEARNED_RECIPE', 'SAW_BOSS', 'HUNTED_WITH', 'TRADED_WITH', 'MET', 'VISITED', 'TOLD', 'RUMOR', 'LEAD') NOT NULL,
	subject_kind ENUM('ITEM', 'NPC', 'MONSTER', 'PLAYER', 'REGION', 'RECIPE', 'QUEST', 'BOSS', 'CONTRACT') NOT NULL,
	subject_ref BIGINT NOT NULL,
	object_kind ENUM('ITEM', 'NPC', 'MONSTER', 'PLAYER', 'REGION', 'RECIPE', 'QUEST', 'BOSS', 'CONTRACT') NULL,
	object_ref BIGINT NULL,
	region_id SMALLINT NULL,
	value JSON NOT NULL,
	provenance ENUM('KNOWN', 'TOLD', 'RUMORED', 'INFERRED', 'HISTORICAL') NOT NULL,
	state ENUM('CURRENT', 'STALE', 'CONTRADICTED') NOT NULL DEFAULT 'CURRENT',
	source_char INT NULL,
	source_page BIGINT NULL,
	intel_id BIGINT NULL,
	first_at TIMESTAMP(3) NOT NULL,
	last_at TIMESTAMP(3) NOT NULL,
	times INT NOT NULL DEFAULT 1,
	tier ENUM('RAW', 'SUMMARY', 'DURABLE') NOT NULL DEFAULT 'RAW',
	expires_at TIMESTAMP(3) NULL,
	coalesce_key VARCHAR(160) AS (CONCAT_WS('/', char_id, relation, subject_kind, subject_ref, IFNULL(object_kind, '-'), IFNULL(object_ref, '-'), IFNULL(region_id, '-'), provenance, tier)) PERSISTENT,
	PRIMARY KEY (obs_id),
	UNIQUE KEY uk_mem_coalesce (coalesce_key),
	KEY idx_mem_char (char_id, relation, last_at),
	KEY idx_mem_expiry (expires_at),
	CONSTRAINT chk_mem_value CHECK (JSON_VALID(value)),
	CONSTRAINT chk_mem_times CHECK (times >= 1 AND last_at >= first_at),
	CONSTRAINT chk_mem_told CHECK ((provenance = 'TOLD') = (source_char IS NOT NULL AND source_page IS NOT NULL)),
	CONSTRAINT chk_mem_lead CHECK ((intel_id IS NULL OR (relation = 'LEAD' AND provenance <> 'HISTORICAL')) AND (relation <> 'LEAD' OR intel_id IS NOT NULL OR provenance = 'HISTORICAL') AND (relation <> 'LEAD' OR intel_id IS NOT NULL OR JSON_LENGTH(value) <= 1)),
	CONSTRAINT chk_mem_tier CHECK ((tier = 'DURABLE') = (expires_at IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_mem_project (
	project_id BIGINT NOT NULL AUTO_INCREMENT,
	char_id INT NOT NULL,
	target_item_id INT NOT NULL,
	state ENUM('OPEN', 'DONE', 'ABANDONED') NOT NULL DEFAULT 'OPEN',
	created_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (project_id),
	KEY idx_mem_project_char (char_id, state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_mem_project_req (
	project_id BIGINT NOT NULL,
	item_id INT NOT NULL,
	qty_needed BIGINT NOT NULL,
	via_recipe INT NULL,
	PRIMARY KEY (project_id, item_id),
	CONSTRAINT fk_mem_req_project FOREIGN KEY (project_id) REFERENCES lc_mem_project (project_id) ON DELETE CASCADE,
	CONSTRAINT chk_mem_req_qty CHECK (qty_needed > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_mem_project_contract (
	project_id BIGINT NOT NULL,
	contract_id BIGINT NOT NULL,
	PRIMARY KEY (project_id, contract_id),
	CONSTRAINT fk_mem_contract_project FOREIGN KEY (project_id) REFERENCES lc_mem_project (project_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_mem_note (
	note_id BIGINT NOT NULL AUTO_INCREMENT,
	char_id INT NOT NULL,
	text VARCHAR(400) NOT NULL,
	pinned BOOLEAN NOT NULL DEFAULT FALSE,
	remind_at TIMESTAMP(3) NULL,
	created_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (note_id),
	KEY idx_mem_note_remind (remind_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A shared page holds frozen copies of its author's observations; never intel (spec §4.5).
CREATE TABLE lc_mem_shared_page (
	page_id BIGINT NOT NULL AUTO_INCREMENT,
	author_char INT NOT NULL,
	title VARCHAR(64) NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	observations_from TIMESTAMP(3) NOT NULL,
	observations_to TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (page_id),
	CONSTRAINT chk_mem_page_span CHECK (observations_to >= observations_from)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_mem_shared_fact (
	page_id BIGINT NOT NULL,
	seq INT NOT NULL,
	relation ENUM('SOLD_BY', 'BOUGHT_FROM', 'BUYS', 'CRAFTS', 'PRICED_AT_NPC', 'OBTAINED_FROM', 'HUNTED', 'TALKED_TO', 'QUEST_FROM', 'QUEST_DONE', 'LEARNED_RECIPE', 'SAW_BOSS', 'HUNTED_WITH', 'TRADED_WITH', 'MET', 'VISITED', 'TOLD', 'RUMOR') NOT NULL,
	subject_kind ENUM('ITEM', 'NPC', 'MONSTER', 'PLAYER', 'REGION', 'RECIPE', 'QUEST', 'BOSS', 'CONTRACT') NOT NULL,
	subject_ref BIGINT NOT NULL,
	object_kind ENUM('ITEM', 'NPC', 'MONSTER', 'PLAYER', 'REGION', 'RECIPE', 'QUEST', 'BOSS', 'CONTRACT') NULL,
	object_ref BIGINT NULL,
	region_id SMALLINT NULL,
	value JSON NOT NULL,
	observed_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (page_id, seq),
	CONSTRAINT fk_mem_fact_page FOREIGN KEY (page_id) REFERENCES lc_mem_shared_page (page_id) ON DELETE CASCADE,
	CONSTRAINT chk_mem_fact_value CHECK (JSON_VALID(value))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_mem_shared_fact_frozen BEFORE UPDATE ON lc_mem_shared_fact FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_mem_shared_fact: a shared page is frozen';

CREATE TABLE lc_mem_milestone (
	milestone_id BIGINT NOT NULL AUTO_INCREMENT,
	char_id INT NOT NULL,
	kind ENUM('FIRST_VISIT', 'BOSS_WITNESSED', 'CLAN_JOINED', 'CLAN_LEFT', 'CONTRACT_COMPLETED', 'SIGNIFICANT_CRAFT', 'MEMORABLE_PVP', 'RECURRING_COMPANION', 'SIEGE_ATTENDED', 'ECONOMIC') NOT NULL,
	ref BIGINT NOT NULL,
	region_id SMALLINT NULL,
	at TIMESTAMP(3) NOT NULL,
	public BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (milestone_id),
	UNIQUE KEY uk_mem_milestone (char_id, kind, ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_mem_session (
	char_id INT NOT NULL,
	session_end TIMESTAMP(3) NOT NULL,
	summary JSON NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id, session_end),
	KEY idx_mem_session_expiry (expires_at),
	CONSTRAINT chk_mem_session_summary CHECK (JSON_VALID(summary))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO lc_expiring_table (table_name, expires_column, owner_module, max_life_hours) VALUES ('lc_mem_observation', 'expires_at', 'WAYFARER', 87600), ('lc_mem_session', 'expires_at', 'WAYFARER', 2160);
