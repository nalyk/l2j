-- SPEC-SIG (docs/spec/aden-signal.md §2): profiles, schedules, Signal blocks, sessions and their members, daily demand
-- cells and audit samples. Intents, introductions, latent groups and the interest ledger are memory-first.
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
	synthetic_changed_at TIMESTAMP(3) NULL,
	standing_objectives SET('XP', 'SPOIL', 'QUEST', 'RAID', 'PVP', 'MANOR', 'CRAFTING', 'MATERIALS', 'SUPPORT_FOR_HIRE', 'EXPLORATION', 'ITEM', 'CARAVAN_ESCORT', 'TW_MERCENARY') NOT NULL DEFAULT '',
	updated_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id),
	CONSTRAINT chk_signal_latent CHECK (NOT latent OR opted_in)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_signal_schedule (
	schedule_id BIGINT NOT NULL AUTO_INCREMENT,
	char_id INT NOT NULL,
	objective ENUM('XP', 'SPOIL', 'QUEST', 'RAID', 'PVP', 'MANOR', 'CRAFTING', 'MATERIALS', 'SUPPORT_FOR_HIRE', 'EXPLORATION', 'ITEM', 'CARAVAN_ESCORT', 'TW_MERCENARY') NOT NULL,
	role_sought SET('TANK', 'HEALER', 'BUFFER', 'NUKER', 'ARCHER', 'DAGGER', 'SUMMONER', 'SPOILER', 'CRAFTER', 'SUPPORT', 'DAMAGE') NOT NULL DEFAULT '',
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
	CONSTRAINT chk_signal_schedule_region CHECK (JSON_VALID(region_pref) AND JSON_TYPE(region_pref) = 'ARRAY')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A block, possibly of an anonymous interest sender (the blocker never sees who it was).
CREATE TABLE lc_signal_block (
	char_id INT NOT NULL,
	blocked_char INT NOT NULL,
	anonymous BOOLEAN NOT NULL DEFAULT FALSE,
	at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id, blocked_char),
	CONSTRAINT chk_signal_block_self CHECK (char_id <> blocked_char)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- One session per introduction; members are rows; who played with whom expires (spec §2.1).
CREATE TABLE lc_signal_session (
	session_id BIGINT NOT NULL AUTO_INCREMENT,
	introduction_id BIGINT NOT NULL,
	formed_at TIMESTAMP(3) NOT NULL,
	cohort ENUM('HUMAN', 'SYNTHETIC', 'MIXED') NOT NULL,
	objective ENUM('XP', 'SPOIL', 'QUEST', 'RAID', 'PVP', 'MANOR', 'CRAFTING', 'MATERIALS', 'SUPPORT_FOR_HIRE', 'EXPLORATION', 'ITEM', 'CARAVAN_ESCORT', 'TW_MERCENARY') NOT NULL,
	introduced_by ENUM('INTENT', 'LATENT', 'SCHEDULE') NOT NULL,
	continued_after_first BOOLEAN NULL,
	ended_at TIMESTAMP(3) NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (session_id),
	UNIQUE KEY uk_signal_session_introduction (introduction_id),
	KEY idx_signal_session_expiry (expires_at),
	CONSTRAINT chk_signal_session_life CHECK (expires_at > formed_at AND (ended_at IS NULL OR ended_at >= formed_at))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_signal_session_member (
	session_id BIGINT NOT NULL,
	char_id INT NOT NULL,
	origin ENUM('HUMAN', 'SYNTHETIC_CITIZEN') NOT NULL,
	PRIMARY KEY (session_id, char_id),
	CONSTRAINT fk_signal_member_session FOREIGN KEY (session_id) REFERENCES lc_signal_session (session_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Daily demand: kept only at five distinct principals or more, banded, by territory and objective only (spec §4.8).
CREATE TABLE lc_signal_demand (
	day DATE NOT NULL,
	territory_id SMALLINT NOT NULL,
	objective ENUM('XP', 'SPOIL', 'QUEST', 'RAID', 'PVP', 'MANOR', 'CRAFTING', 'MATERIALS', 'SUPPORT_FOR_HIRE', 'EXPLORATION', 'ITEM', 'CARAVAN_ESCORT', 'TW_MERCENARY') NOT NULL,
	intents_band ENUM('5-9', '10-19', '20+') NOT NULL,
	PRIMARY KEY (day, territory_id, objective)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_signal_audit_sample (
	sample_id BIGINT NOT NULL AUTO_INCREMENT,
	at TIMESTAMP(3) NOT NULL,
	class_a SMALLINT NOT NULL,
	class_b SMALLINT NOT NULL,
	score DOUBLE NOT NULL,
	components JSON NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (sample_id),
	KEY idx_signal_audit_expiry (expires_at),
	CONSTRAINT chk_signal_audit_components CHECK (JSON_VALID(components))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO lc_expiring_table (table_name, expires_column, owner_module, max_life_hours) VALUES ('lc_signal_schedule', 'expires_at', 'SIGNAL', 720), ('lc_signal_session', 'expires_at', 'SIGNAL', 2160), ('lc_signal_audit_sample', 'expires_at', 'SIGNAL', 168);

-- Signal's publication type (spec §4.8; intel-service.md §4.7).
INSERT INTO lc_pub_type (pub_type, min_delay_minutes, location_related) VALUES ('signal_demand', 1440, TRUE);

INSERT INTO lc_pub_field_rule (pub_type, field, rule) VALUES ('signal_demand', 'territory', 'AREA_TERRITORY'), ('signal_demand', 'objective', 'TEXT_FROM_FACT'), ('signal_demand', 'band', 'TEXT_FROM_FACT'), ('signal_demand', 'day', 'DAY');
