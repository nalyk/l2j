-- SPEC-SIG (docs/spec/aden-signal.md §2): profiles, schedules, Signal blocks, sessions, demand aggregates and audit
-- samples. Intents, candidates and interest are memory-first and have no table.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

CREATE TABLE lc_signal_profile (
	char_id INT NOT NULL,
	opted_in BOOLEAN NOT NULL DEFAULT FALSE,
	visibility ENUM('EVERYONE', 'ALLIANCE', 'CLAN', 'NOBODY') NOT NULL DEFAULT 'EVERYONE',
	latent BOOLEAN NOT NULL DEFAULT FALSE,
	languages SET('EN', 'RO', 'RU') NOT NULL DEFAULT 'EN',
	pace ENUM('RELAXED', 'STEADY', 'FAST') NOT NULL DEFAULT 'STEADY',
	allow_synthetic BOOLEAN NOT NULL DEFAULT TRUE,
	standing_objectives SET('XP', 'SPOIL', 'QUEST', 'RAID', 'PVP', 'MANOR', 'CRAFTING', 'MATERIALS', 'SUPPORT_FOR_HIRE', 'EXPLORATION', 'ITEM', 'CARAVAN_ESCORT', 'TW_MERCENARY') NOT NULL DEFAULT '',
	updated_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id),
	CONSTRAINT chk_signal_latent CHECK (NOT latent OR opted_in)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_signal_schedule (
	schedule_id BIGINT NOT NULL AUTO_INCREMENT,
	char_id INT NOT NULL,
	objective ENUM('XP', 'SPOIL', 'QUEST', 'RAID', 'PVP', 'MANOR', 'CRAFTING', 'MATERIALS', 'SUPPORT_FOR_HIRE', 'EXPLORATION', 'ITEM', 'CARAVAN_ESCORT', 'TW_MERCENARY') NOT NULL,
	role_sought SET('TANK', 'HEALER', 'BUFFER', 'NUKER', 'ARCHER', 'DAGGER', 'SUMMONER', 'SPOILER', 'CRAFTER', 'SUPPORT') NOT NULL DEFAULT '',
	region_pref JSON NOT NULL,
	languages SET('EN', 'RO', 'RU') NOT NULL DEFAULT 'EN',
	start_at TIMESTAMP(3) NOT NULL,
	end_at TIMESTAMP(3) NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (schedule_id),
	KEY idx_signal_schedule_start (start_at),
	KEY idx_signal_schedule_expiry (expires_at),
	CONSTRAINT fk_signal_schedule_profile FOREIGN KEY (char_id) REFERENCES lc_signal_profile (char_id),
	CONSTRAINT chk_signal_schedule_time CHECK (end_at > start_at AND expires_at = end_at),
	CONSTRAINT chk_signal_schedule_region CHECK (JSON_VALID(region_pref))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_signal_block (
	char_id INT NOT NULL,
	blocked_char INT NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id, blocked_char),
	CONSTRAINT chk_signal_block_self CHECK (char_id <> blocked_char)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Who played with whom after an introduction: expires (spec §2.1).
CREATE TABLE lc_signal_session (
	session_id BIGINT NOT NULL AUTO_INCREMENT,
	formed_at TIMESTAMP(3) NOT NULL,
	members JSON NOT NULL,
	cohort ENUM('HUMAN', 'SYNTHETIC', 'MIXED') NOT NULL,
	objective ENUM('XP', 'SPOIL', 'QUEST', 'RAID', 'PVP', 'MANOR', 'CRAFTING', 'MATERIALS', 'SUPPORT_FOR_HIRE', 'EXPLORATION', 'ITEM', 'CARAVAN_ESCORT', 'TW_MERCENARY') NOT NULL,
	introduced_by ENUM('INTENT', 'LATENT', 'SCHEDULE') NOT NULL,
	continued_after_first BOOLEAN NULL,
	ended_at TIMESTAMP(3) NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (session_id),
	KEY idx_signal_session_expiry (expires_at),
	CONSTRAINT chk_signal_session_members CHECK (JSON_VALID(members) AND JSON_LENGTH(members) BETWEEN 2 AND 9),
	CONSTRAINT chk_signal_session_life CHECK (expires_at > formed_at AND (ended_at IS NULL OR ended_at >= formed_at))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Published demand: counts only, by territory; a cell is stored only when it reaches the publication threshold
-- (`DemandMinIntents`, at least 3 by its range, the floor the schema keeps; the writer applies the configured value).
CREATE TABLE lc_signal_demand (
	window_start TIMESTAMP NOT NULL,
	territory_id SMALLINT NOT NULL,
	objective ENUM('XP', 'SPOIL', 'QUEST', 'RAID', 'PVP', 'MANOR', 'CRAFTING', 'MATERIALS', 'SUPPORT_FOR_HIRE', 'EXPLORATION', 'ITEM', 'CARAVAN_ESCORT', 'TW_MERCENARY') NOT NULL,
	role_sought VARCHAR(16) NOT NULL,
	cohort ENUM('HUMAN', 'SYNTHETIC', 'MIXED') NOT NULL,
	intents INT NOT NULL,
	PRIMARY KEY (window_start, territory_id, objective, role_sought, cohort),
	CONSTRAINT chk_signal_demand_floor CHECK (intents >= 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_signal_audit_sample (
	sample_id BIGINT NOT NULL AUTO_INCREMENT,
	at TIMESTAMP(3) NOT NULL,
	intent_a_class SMALLINT NOT NULL,
	intent_b_class SMALLINT NOT NULL,
	score DOUBLE NOT NULL,
	components JSON NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (sample_id),
	KEY idx_signal_audit_expiry (expires_at),
	CONSTRAINT chk_signal_audit_components CHECK (JSON_VALID(components))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO lc_expiring_table (table_name, expires_column, owner_module, max_life_hours) VALUES ('lc_signal_schedule', 'expires_at', 'SIGNAL', 720), ('lc_signal_session', 'expires_at', 'SIGNAL', 2160), ('lc_signal_audit_sample', 'expires_at', 'SIGNAL', 168);
