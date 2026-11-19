-- SPEC-CHR (docs/spec/chronicle.md §2): the publication row store and its visible view, names, Chronicle's publication
-- types, issues, monuments, sagas, battles, letters, narrated prose and the criers' log.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

-- Three field rules for Chronicle's types (an amendment to intel-service.md §4.7, chronicle.md §2.2).
ALTER TABLE lc_pub_field_rule MODIFY rule ENUM('PUBLIC_ENTITY', 'AREA_TERRITORY', 'DAY', 'COUNT', 'TEXT_FROM_FACT', 'LEDGER_WRIT_MARK', 'LEDGER_LEGEND_MARK', 'LEDGER_ALIAS', 'CHARACTER_PUBLIC', 'SAGA_ITEM', 'MODERATED_TEXT') NOT NULL;

-- Every publication row of every registered type. A row is visible only from its publish time, which honours its type's
-- delay; the readers of the website and the narrator are granted the view, never this table (chronicle.md §2.2).
CREATE TABLE lc_pub_row (
	pub_row_id BIGINT NOT NULL AUTO_INCREMENT,
	pub_type VARCHAR(48) NOT NULL,
	written_at TIMESTAMP(3) NOT NULL,
	event_at TIMESTAMP(3) NOT NULL,
	publish_at TIMESTAMP(3) NOT NULL,
	fields JSON NOT NULL,
	source_fact_from BIGINT NOT NULL,
	source_fact_to BIGINT NOT NULL,
	superseded_by BIGINT NULL,
	PRIMARY KEY (pub_row_id),
	KEY idx_pub_row_type (pub_type, publish_at),
	CONSTRAINT chk_pub_row_fields CHECK (JSON_VALID(fields) AND JSON_TYPE(fields) = 'OBJECT'),
	CONSTRAINT chk_pub_row_facts CHECK (source_fact_to >= source_fact_from AND publish_at >= written_at),
	CONSTRAINT fk_pub_row_type FOREIGN KEY (pub_type) REFERENCES lc_pub_type (pub_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_pub_row_delay BEFORE INSERT ON lc_pub_row FOR EACH ROW IF NEW.publish_at < NEW.event_at + INTERVAL (SELECT min_delay_minutes FROM lc_pub_type WHERE pub_type = NEW.pub_type) MINUTE OR NEW.superseded_by IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pub_row: a row is published no sooner than its type allows, and starts current'; END IF;

CREATE TRIGGER lc_pub_row_frozen BEFORE UPDATE ON lc_pub_row FOR EACH ROW IF NOT (OLD.superseded_by IS NULL AND NEW.superseded_by IS NOT NULL AND NEW.superseded_by > OLD.pub_row_id) OR NEW.pub_type <> OLD.pub_type OR NEW.written_at <> OLD.written_at OR NEW.event_at <> OLD.event_at OR NEW.publish_at <> OLD.publish_at OR NOT (NEW.fields <=> OLD.fields) OR NEW.source_fact_from <> OLD.source_fact_from OR NEW.source_fact_to <> OLD.source_fact_to THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pub_row: a row never changes; it is only superseded once, by a later row'; END IF;

CREATE TRIGGER lc_pub_row_kept BEFORE DELETE ON lc_pub_row FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pub_row: rows are never deleted';

CREATE VIEW lc_pub_visible AS SELECT pub_row_id, pub_type, event_at, publish_at, fields FROM lc_pub_row WHERE publish_at <= NOW(3) AND superseded_by IS NULL;

-- Names resolve at render: a row holds a character id and its basis, never a name (chronicle.md §2.1).
CREATE TABLE lc_pub_name (
	char_id INT NOT NULL,
	name VARCHAR(35) NOT NULL,
	opted_in BOOLEAN NOT NULL DEFAULT FALSE,
	changed_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO lc_pub_type (pub_type, min_delay_minutes, location_related) VALUES ('chr_realm', 0, FALSE), ('chr_history', 1440, TRUE), ('chr_monument', 1440, TRUE), ('chr_saga_entry', 1440, TRUE), ('chr_obituary', 1440, TRUE), ('chr_replay', 1440, TRUE), ('chr_arena', 0, FALSE), ('chr_letter', 1440, TRUE), ('chr_gazette', 0, FALSE);

INSERT INTO lc_pub_field_rule (pub_type, field, rule) VALUES ('chr_realm', 'kind', 'TEXT_FROM_FACT'), ('chr_realm', 'castle', 'PUBLIC_ENTITY'), ('chr_realm', 'fortress', 'PUBLIC_ENTITY'), ('chr_realm', 'clan', 'PUBLIC_ENTITY'), ('chr_realm', 'character', 'CHARACTER_PUBLIC'), ('chr_realm', 'day', 'DAY'), ('chr_history', 'kind', 'TEXT_FROM_FACT'), ('chr_history', 'title', 'TEXT_FROM_FACT'), ('chr_history', 'period', 'DAY'), ('chr_history', 'territory', 'AREA_TERRITORY'), ('chr_history', 'clans', 'PUBLIC_ENTITY'), ('chr_history', 'characters', 'CHARACTER_PUBLIC'), ('chr_history', 'counts', 'COUNT'), ('chr_monument', 'history_row', 'COUNT'), ('chr_monument', 'town', 'PUBLIC_ENTITY'), ('chr_monument', 'slot', 'COUNT'), ('chr_saga_entry', 'item', 'SAGA_ITEM'), ('chr_saga_entry', 'entry_kind', 'TEXT_FROM_FACT'), ('chr_saga_entry', 'territory', 'AREA_TERRITORY'), ('chr_saga_entry', 'day', 'DAY'), ('chr_saga_entry', 'characters', 'CHARACTER_PUBLIC'), ('chr_saga_entry', 'counts', 'COUNT'), ('chr_obituary', 'item', 'SAGA_ITEM'), ('chr_obituary', 'cause', 'TEXT_FROM_FACT'), ('chr_obituary', 'day', 'DAY'), ('chr_replay', 'history_row', 'COUNT'), ('chr_replay', 'frames', 'COUNT'), ('chr_arena', 'heroes', 'CHARACTER_PUBLIC'), ('chr_arena', 'class', 'TEXT_FROM_FACT'), ('chr_arena', 'day', 'DAY'), ('chr_letter', 'author', 'CHARACTER_PUBLIC'), ('chr_letter', 'text', 'MODERATED_TEXT'), ('chr_letter', 'day', 'DAY'), ('chr_gazette', 'issue', 'COUNT'), ('chr_gazette', 'day', 'DAY'), ('chr_gazette', 'sections', 'COUNT');

-- The Gazette: one issue per day; a publication row enters at most one issue (chronicle.md §2.3).
CREATE TABLE lc_chr_issue (
	issue_no INT NOT NULL,
	day DATE NOT NULL,
	closed_fact_id BIGINT NOT NULL,
	composed_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (issue_no),
	UNIQUE KEY uk_chr_issue_day (day),
	UNIQUE KEY uk_chr_issue_fact (closed_fact_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_chr_issue_entry (
	issue_no INT NOT NULL,
	seq SMALLINT NOT NULL,
	pub_row_id BIGINT NOT NULL,
	section ENUM('REALM', 'MARKETS', 'ROADS', 'LAW', 'ARENA', 'OBITUARIES', 'LETTERS', 'HISTORY') NOT NULL,
	PRIMARY KEY (issue_no, seq),
	UNIQUE KEY uk_chr_issue_row (pub_row_id),
	CONSTRAINT fk_chr_issue_entry_issue FOREIGN KEY (issue_no) REFERENCES lc_chr_issue (issue_no),
	CONSTRAINT fk_chr_issue_entry_row FOREIGN KEY (pub_row_id) REFERENCES lc_pub_row (pub_row_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_chr_issue_entry_visible BEFORE INSERT ON lc_chr_issue_entry FOR EACH ROW IF (SELECT publish_at FROM lc_pub_row WHERE pub_row_id = NEW.pub_row_id) > (SELECT composed_at FROM lc_chr_issue WHERE issue_no = NEW.issue_no) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_chr_issue_entry: a row enters an issue only once it is published'; END IF;

-- Monuments: raised once per history row and per application id; the NPC is a projection of the row.
CREATE TABLE lc_chr_monument_slot (
	town_id INT NOT NULL,
	slot TINYINT NOT NULL,
	x INT NOT NULL,
	y INT NOT NULL,
	z INT NOT NULL,
	heading INT NOT NULL,
	PRIMARY KEY (town_id, slot)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_chr_monument (
	monument_id BIGINT NOT NULL AUTO_INCREMENT,
	town_id INT NOT NULL,
	slot TINYINT NOT NULL,
	history_row_id BIGINT NOT NULL,
	application_id BINARY(16) NOT NULL,
	state ENUM('STANDING', 'RETIRED') NOT NULL DEFAULT 'STANDING',
	raised_at TIMESTAMP(3) NOT NULL,
	retired_at TIMESTAMP(3) NULL,
	standing_key VARCHAR(24) AS (IF(state = 'STANDING', CONCAT(town_id, '/', slot), NULL)) PERSISTENT,
	PRIMARY KEY (monument_id),
	UNIQUE KEY uk_chr_monument_history (history_row_id),
	UNIQUE KEY uk_chr_monument_application (application_id),
	UNIQUE KEY uk_chr_monument_slot (standing_key),
	CONSTRAINT chk_chr_monument_retired CHECK ((state = 'RETIRED') = (retired_at IS NOT NULL)),
	CONSTRAINT fk_chr_monument_slot FOREIGN KEY (town_id, slot) REFERENCES lc_chr_monument_slot (town_id, slot)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_chr_monument_retire BEFORE UPDATE ON lc_chr_monument FOR EACH ROW IF OLD.state <> 'STANDING' OR NEW.town_id <> OLD.town_id OR NEW.slot <> OLD.slot OR NEW.history_row_id <> OLD.history_row_id OR NEW.application_id <> OLD.application_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_chr_monument: a monument only retires, once'; END IF;

-- Item sagas, keyed by Asset item lifecycles (chronicle.md §4.6).
CREATE TABLE lc_chr_saga (
	lifecycle_id BIGINT NOT NULL,
	item_id INT NOT NULL,
	began_at TIMESTAMP(3) NOT NULL,
	began_kind ENUM('CRAFTED', 'LOOTED', 'BOUGHT', 'REWARDED', 'EXCHANGED', 'OTHER') NOT NULL,
	ended_at TIMESTAMP(3) NULL,
	end_kind ENUM('DESTROYED', 'CRYSTALLIZED', 'MERGED', 'OTHER') NULL,
	pvp_kills INT NOT NULL DEFAULT 0,
	PRIMARY KEY (lifecycle_id),
	CONSTRAINT chk_chr_saga_end CHECK ((ended_at IS NULL) = (end_kind IS NULL) AND pvp_kills >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_chr_saga_event (
	lifecycle_id BIGINT NOT NULL,
	seq INT NOT NULL,
	kind ENUM('OWNER', 'LOST_ON_DEATH', 'PVP_KILL', 'BATTLE', 'ENDED') NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	territory_id SMALLINT NULL,
	char_id INT NULL,
	other_char_id INT NULL,
	ref BIGINT NULL,
	PRIMARY KEY (lifecycle_id, seq),
	CONSTRAINT fk_chr_saga_event FOREIGN KEY (lifecycle_id) REFERENCES lc_chr_saga (lifecycle_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_chr_saga_event_open BEFORE INSERT ON lc_chr_saga_event FOR EACH ROW IF (SELECT ended_at FROM lc_chr_saga WHERE lifecycle_id = NEW.lifecycle_id) IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_chr_saga_event: an ended saga takes no event'; END IF;

-- Battles: samples and marks are private until their replay (chronicle.md §4.7); a cell never shows fewer than two.
CREATE TABLE lc_chr_battle (
	battle_key VARCHAR(48) NOT NULL,
	kind ENUM('CASTLE_SIEGE', 'FORT_SIEGE', 'TERRITORY_WAR') NOT NULL,
	place_id INT NOT NULL,
	started_at TIMESTAMP(3) NOT NULL,
	ended_at TIMESTAMP(3) NULL,
	history_row_id BIGINT NULL,
	PRIMARY KEY (battle_key),
	CONSTRAINT chk_chr_battle_span CHECK (ended_at IS NULL OR ended_at >= started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_chr_battle_sample (
	battle_key VARCHAR(48) NOT NULL,
	minute SMALLINT NOT NULL,
	cell SMALLINT NOT NULL,
	side ENUM('ATTACKER', 'DEFENDER') NOT NULL,
	count SMALLINT NOT NULL,
	PRIMARY KEY (battle_key, minute, cell, side),
	CONSTRAINT chk_chr_battle_sample CHECK (count >= 2 AND minute >= 0),
	CONSTRAINT fk_chr_battle_sample FOREIGN KEY (battle_key) REFERENCES lc_chr_battle (battle_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_chr_battle_mark (
	battle_key VARCHAR(48) NOT NULL,
	minute SMALLINT NOT NULL,
	kind ENUM('KILLS', 'WARD', 'GATE', 'SEAL') NOT NULL,
	ref INT NOT NULL DEFAULT 0,
	count INT NOT NULL,
	PRIMARY KEY (battle_key, minute, kind, ref),
	CONSTRAINT chk_chr_battle_mark CHECK (count > 0),
	CONSTRAINT fk_chr_battle_mark FOREIGN KEY (battle_key) REFERENCES lc_chr_battle (battle_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Letters: queued, decided by a moderator, published once (chronicle.md §3.4).
CREATE TABLE lc_chr_letter (
	letter_id BIGINT NOT NULL AUTO_INCREMENT,
	author_char INT NOT NULL,
	text VARCHAR(600) NOT NULL,
	submitted_at TIMESTAMP(3) NOT NULL,
	state ENUM('QUEUED', 'APPROVED', 'REJECTED', 'PUBLISHED') NOT NULL DEFAULT 'QUEUED',
	moderator VARCHAR(64) NULL,
	decided_at TIMESTAMP(3) NULL,
	reason VARCHAR(128) NULL,
	PRIMARY KEY (letter_id),
	CONSTRAINT chk_chr_letter_decided CHECK ((state = 'QUEUED') = (moderator IS NULL AND decided_at IS NULL) AND (state <> 'REJECTED' OR reason IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_chr_letter_insert BEFORE INSERT ON lc_chr_letter FOR EACH ROW IF NEW.state <> 'QUEUED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_chr_letter: a letter starts queued'; END IF;

CREATE TRIGGER lc_chr_letter_transition BEFORE UPDATE ON lc_chr_letter FOR EACH ROW IF NEW.text <> OLD.text OR NEW.author_char <> OLD.author_char OR NOT ((OLD.state, NEW.state) IN (('QUEUED', 'APPROVED'), ('QUEUED', 'REJECTED'), ('APPROVED', 'PUBLISHED'))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_chr_letter: not a transition of the letter machine'; END IF;

-- Narrated prose, with placeholders only (chronicle.md §4.8).
CREATE TABLE lc_chr_prose (
	dto_hash BINARY(32) NOT NULL,
	lang ENUM('EN', 'RO', 'RU') NOT NULL,
	style VARCHAR(24) NOT NULL,
	text TEXT NOT NULL,
	checked_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (dto_hash, lang, style)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_chr_crier_log (
	town_id INT NOT NULL,
	kind VARCHAR(24) NOT NULL,
	ref BIGINT NOT NULL,
	said_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (town_id, kind, ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
