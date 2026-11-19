-- SPEC-CHR (docs/spec/chronicle.md §2): the publication row store and its views, names and their tokens, Chronicle's
-- publication types, pending rows, days and issues, readings, monuments, sagas, battles, letters, narrated choices,
-- criers, and the two reader roles.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

-- Every publication row of every registered type, written only once due: its delay counted from the newest source fact
-- (chronicle.md §2.1). Frozen; superseded at most once, by a later current row of the same type; never deleted.
CREATE TABLE lc_pub_row (
	pub_row_id BIGINT NOT NULL AUTO_INCREMENT,
	pub_type VARCHAR(48) NOT NULL,
	written_at TIMESTAMP(3) NOT NULL,
	event_at TIMESTAMP(3) NOT NULL,
	fields JSON NOT NULL,
	source_fact_from BIGINT NOT NULL,
	source_fact_to BIGINT NOT NULL,
	superseded_by BIGINT NULL,
	withdrawn_at TIMESTAMP(3) NULL,
	PRIMARY KEY (pub_row_id),
	KEY idx_pub_row_type (pub_type, written_at),
	CONSTRAINT chk_pub_row_fields CHECK (JSON_VALID(fields) AND JSON_TYPE(fields) = 'OBJECT'),
	CONSTRAINT chk_pub_row_facts CHECK (source_fact_to >= source_fact_from),
	CONSTRAINT chk_pub_row_one_end CHECK (superseded_by IS NULL OR withdrawn_at IS NULL),
	CONSTRAINT fk_pub_row_type FOREIGN KEY (pub_type) REFERENCES lc_pub_type (pub_type),
	CONSTRAINT fk_pub_row_successor FOREIGN KEY (superseded_by) REFERENCES lc_pub_row (pub_row_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_pub_row_due BEFORE INSERT ON lc_pub_row FOR EACH ROW BEGIN DECLARE fact_at TIMESTAMP(3); SET NEW.written_at = NOW(3); SELECT MAX(occurred_at) INTO fact_at FROM lc_fact WHERE id = NEW.source_fact_to; IF fact_at IS NULL OR NEW.event_at < fact_at OR NEW.written_at < NEW.event_at + INTERVAL (SELECT min_delay_minutes FROM lc_pub_type WHERE pub_type = NEW.pub_type) MINUTE OR NEW.superseded_by IS NOT NULL OR NEW.withdrawn_at IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pub_row: written only once due by the clock, counted from its newest source fact, and current'; END IF; END;

CREATE TRIGGER lc_pub_row_frozen BEFORE UPDATE ON lc_pub_row FOR EACH ROW IF NOT IFNULL(NEW.pub_row_id = OLD.pub_row_id AND NEW.pub_type = OLD.pub_type AND NEW.written_at = OLD.written_at AND NEW.event_at = OLD.event_at AND NEW.fields = OLD.fields AND NEW.source_fact_from = OLD.source_fact_from AND NEW.source_fact_to = OLD.source_fact_to AND OLD.superseded_by IS NULL AND OLD.withdrawn_at IS NULL AND ((NEW.superseded_by IS NOT NULL AND NEW.withdrawn_at IS NULL AND NEW.superseded_by > OLD.pub_row_id AND (SELECT pub_type = OLD.pub_type AND superseded_by IS NULL AND withdrawn_at IS NULL FROM lc_pub_row WHERE pub_row_id = NEW.superseded_by)) OR (NEW.superseded_by IS NULL AND NEW.withdrawn_at IS NOT NULL)), FALSE) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pub_row: a row never changes; it is corrected once by a later current row of its type, or withdrawn once'; END IF;

CREATE TRIGGER lc_pub_row_kept BEFORE DELETE ON lc_pub_row FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pub_row: rows are never deleted';

CREATE VIEW lc_pub_visible AS SELECT pub_row_id, pub_type, written_at, event_at, fields FROM lc_pub_row WHERE superseded_by IS NULL AND withdrawn_at IS NULL;

CREATE VIEW lc_pub_successor AS WITH RECURSIVE chain (old_row_id, next_row_id) AS (SELECT pub_row_id, superseded_by FROM lc_pub_row WHERE superseded_by IS NOT NULL UNION ALL SELECT c.old_row_id, p.superseded_by FROM chain c JOIN lc_pub_row p ON p.pub_row_id = c.next_row_id WHERE p.superseded_by IS NOT NULL) SELECT c.old_row_id, c.next_row_id FROM chain c JOIN lc_pub_row f ON f.pub_row_id = c.next_row_id WHERE f.superseded_by IS NULL AND f.withdrawn_at IS NULL;

-- Names: rows hold per-row tokens; a token resolves to a name only while its basis holds (chronicle.md §2.2).
CREATE TABLE lc_pub_name (
	char_id INT NOT NULL,
	name VARCHAR(35) NOT NULL,
	opted_in BOOLEAN NOT NULL DEFAULT FALSE,
	consent_gen INT NOT NULL DEFAULT 0,
	page_key BINARY(16) NOT NULL,
	changed_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id),
	UNIQUE KEY uk_pub_name_page (page_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_pub_name_ref (
	token BINARY(16) NOT NULL,
	pub_row_id BIGINT NOT NULL,
	char_id INT NOT NULL,
	basis ENUM('OPTED_IN', 'HERO', 'CASTLE_LORD', 'PACT_PARTY', 'WRIT_MARK', 'LEGEND_MARK') NOT NULL,
	consent_gen INT NOT NULL DEFAULT 0,
	basis_ref BIGINT NULL,
	PRIMARY KEY (token),
	UNIQUE KEY uk_pub_name_ref_row (pub_row_id, char_id),
	CONSTRAINT fk_pub_name_ref_row FOREIGN KEY (pub_row_id) REFERENCES lc_pub_row (pub_row_id),
	CONSTRAINT fk_pub_name_ref_char FOREIGN KEY (char_id) REFERENCES lc_pub_name (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_pub_name_ref_basis BEFORE INSERT ON lc_pub_name_ref FOR EACH ROW BEGIN DECLARE t VARCHAR(48); SELECT pub_type INTO t FROM lc_pub_row WHERE pub_row_id = NEW.pub_row_id; IF NOT IFNULL((NEW.basis = 'HERO' AND t = 'chr_arena') OR (NEW.basis = 'CASTLE_LORD' AND t IN ('chr_realm', 'chr_history')) OR (NEW.basis = 'PACT_PARTY' AND t IN ('pact_registered', 'pact_completed', 'pact_completed_terms')) OR (NEW.basis = 'WRIT_MARK' AND t = 'ledger_wanted' AND NEW.basis_ref IS NOT NULL) OR (NEW.basis = 'LEGEND_MARK' AND t = 'ledger_legend') OR (NEW.basis = 'OPTED_IN' AND t IN ('chr_history', 'chr_saga_entry', 'chr_letter') AND NEW.consent_gen = (SELECT consent_gen FROM lc_pub_name WHERE char_id = NEW.char_id)), FALSE) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pub_name_ref: a basis only on its row types, an opt-in only of the current consent'; END IF; END;

CREATE TRIGGER lc_pub_name_ref_frozen BEFORE UPDATE ON lc_pub_name_ref FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pub_name_ref: tokens never change';

CREATE VIEW lc_pub_name_visible AS SELECT r.token, n.name FROM lc_pub_name_ref r JOIN lc_pub_name n ON n.char_id = r.char_id JOIN lc_pub_row p ON p.pub_row_id = r.pub_row_id AND p.superseded_by IS NULL AND p.withdrawn_at IS NULL WHERE r.basis IN ('HERO', 'CASTLE_LORD', 'PACT_PARTY', 'LEGEND_MARK') OR (r.basis = 'OPTED_IN' AND n.opted_in AND r.consent_gen = n.consent_gen) OR (r.basis = 'WRIT_MARK' AND EXISTS (SELECT 1 FROM lc_ledger_contract c WHERE c.contract_id = r.basis_ref AND c.kind = 'WRIT' AND c.state = 'OPEN' AND c.mark_char = r.char_id));

CREATE VIEW lc_pub_character_page AS SELECT n.page_key, n.name, r.token FROM lc_pub_name_ref r JOIN lc_pub_name n ON n.char_id = r.char_id JOIN lc_pub_row p ON p.pub_row_id = r.pub_row_id AND p.superseded_by IS NULL AND p.withdrawn_at IS NULL WHERE r.basis = 'OPTED_IN' AND n.opted_in AND r.consent_gen = n.consent_gen;

INSERT INTO lc_pub_type (pub_type, min_delay_minutes, location_related) VALUES ('chr_realm', 0, FALSE), ('chr_history', 1440, TRUE), ('chr_monument', 1440, TRUE), ('chr_saga_entry', 1440, TRUE), ('chr_obituary', 1440, TRUE), ('chr_replay', 1440, TRUE), ('chr_arena', 0, FALSE), ('chr_letter', 1440, TRUE), ('chr_gazette', 0, FALSE);

INSERT INTO lc_pub_field_rule (pub_type, field, rule) VALUES ('chr_realm', 'kind', 'TEXT_FROM_FACT'), ('chr_realm', 'castle', 'PUBLIC_ENTITY'), ('chr_realm', 'fortress', 'PUBLIC_ENTITY'), ('chr_realm', 'clan', 'PUBLIC_ENTITY'), ('chr_realm', 'lord', 'NAME_TOKEN'), ('chr_realm', 'day', 'DAY'), ('chr_history', 'kind', 'TEXT_FROM_FACT'), ('chr_history', 'title', 'TEXT_FROM_FACT'), ('chr_history', 'period', 'DAY'), ('chr_history', 'territory', 'AREA_TERRITORY'), ('chr_history', 'clans', 'PUBLIC_ENTITY'), ('chr_history', 'characters', 'NAME_TOKEN'), ('chr_history', 'counts', 'COUNT'), ('chr_monument', 'history_row', 'PUB_REF'), ('chr_monument', 'town', 'PUBLIC_ENTITY'), ('chr_monument', 'slot', 'COUNT'), ('chr_saga_entry', 'item', 'SAGA_ITEM'), ('chr_saga_entry', 'entry_kind', 'TEXT_FROM_FACT'), ('chr_saga_entry', 'territory', 'AREA_TERRITORY'), ('chr_saga_entry', 'day', 'DAY'), ('chr_saga_entry', 'characters', 'NAME_TOKEN'), ('chr_saga_entry', 'counts', 'COUNT'), ('chr_obituary', 'item', 'SAGA_ITEM'), ('chr_obituary', 'cause', 'TEXT_FROM_FACT'), ('chr_obituary', 'day', 'DAY'), ('chr_replay', 'history_row', 'PUB_REF'), ('chr_replay', 'frames', 'REPLAY_FRAMES'), ('chr_arena', 'heroes', 'NAME_TOKEN'), ('chr_arena', 'class', 'TEXT_FROM_FACT'), ('chr_arena', 'period', 'DAY'), ('chr_letter', 'author', 'NAME_TOKEN'), ('chr_letter', 'text', 'MODERATED_TEXT'), ('chr_letter', 'day', 'DAY'), ('chr_gazette', 'issue', 'COUNT'), ('chr_gazette', 'day', 'DAY'), ('chr_gazette', 'sections', 'PUB_REF');

-- Chronicle's pending publications, private until due (chronicle.md §2.4).
CREATE TABLE lc_chr_pending (
	pending_id BIGINT NOT NULL AUTO_INCREMENT,
	pub_type VARCHAR(48) NOT NULL,
	fields JSON NOT NULL,
	event_at TIMESTAMP(3) NOT NULL,
	due_at TIMESTAMP(3) NOT NULL,
	source_fact_from BIGINT NOT NULL,
	source_fact_to BIGINT NOT NULL,
	PRIMARY KEY (pending_id),
	KEY idx_chr_pending_due (due_at),
	CONSTRAINT chk_chr_pending CHECK (JSON_VALID(fields) AND due_at >= event_at),
	CONSTRAINT fk_chr_pending_type FOREIGN KEY (pub_type) REFERENCES lc_pub_type (pub_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Days and issues: each day closed once, its issue composed once (chronicle.md §3.1, §4.3).
CREATE TABLE lc_chr_day (
	day DATE NOT NULL,
	closed_at TIMESTAMP(3) NOT NULL,
	fact_id BIGINT NOT NULL,
	PRIMARY KEY (day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_chr_issue (
	issue_no INT NOT NULL,
	day DATE NOT NULL,
	composed_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (issue_no),
	UNIQUE KEY uk_chr_issue_day (day),
	CONSTRAINT fk_chr_issue_day FOREIGN KEY (day) REFERENCES lc_chr_day (day)
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

CREATE TRIGGER lc_chr_issue_entry_section BEFORE INSERT ON lc_chr_issue_entry FOR EACH ROW BEGIN DECLARE t VARCHAR(48); DECLARE w TIMESTAMP(3); SELECT pub_type, written_at INTO t, w FROM lc_pub_row WHERE pub_row_id = NEW.pub_row_id; IF w > (SELECT composed_at FROM lc_chr_issue WHERE issue_no = NEW.issue_no) OR NOT IFNULL((NEW.section, t) IN (('REALM', 'chr_realm'), ('REALM', 'pact_registered'), ('REALM', 'pact_completed'), ('REALM', 'pact_completed_terms'), ('MARKETS', 'world_market'), ('MARKETS', 'signal_demand'), ('ROADS', 'world_caravan'), ('LAW', 'ledger_wanted'), ('LAW', 'ledger_legend'), ('ARENA', 'chr_arena'), ('OBITUARIES', 'chr_obituary'), ('LETTERS', 'chr_letter'), ('HISTORY', 'chr_history')), FALSE) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_chr_issue_entry: a written row of its section''s types only'; END IF; END;

CREATE TABLE lc_chr_reading (
	char_id INT NOT NULL,
	issue_no INT NOT NULL,
	paid_op BINARY(16) NOT NULL,
	PRIMARY KEY (char_id, issue_no),
	UNIQUE KEY uk_chr_reading_op (paid_op),
	CONSTRAINT fk_chr_reading_issue FOREIGN KEY (issue_no) REFERENCES lc_chr_issue (issue_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

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
	CONSTRAINT fk_chr_monument_slot FOREIGN KEY (town_id, slot) REFERENCES lc_chr_monument_slot (town_id, slot),
	CONSTRAINT fk_chr_monument_history FOREIGN KEY (history_row_id) REFERENCES lc_pub_row (pub_row_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_chr_monument_raise BEFORE INSERT ON lc_chr_monument FOR EACH ROW IF NOT IFNULL((SELECT pub_type = 'chr_history' AND superseded_by IS NULL AND withdrawn_at IS NULL FROM lc_pub_row WHERE pub_row_id = NEW.history_row_id), FALSE) OR NEW.state <> 'STANDING' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_chr_monument: raised standing, for a written, current history row'; END IF;

CREATE TRIGGER lc_chr_monument_retire BEFORE UPDATE ON lc_chr_monument FOR EACH ROW IF OLD.state <> 'STANDING' OR NEW.town_id <> OLD.town_id OR NEW.slot <> OLD.slot OR NEW.history_row_id <> OLD.history_row_id OR NEW.application_id <> OLD.application_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_chr_monument: a monument only retires, once'; END IF;

-- Item sagas, keyed by Asset item lifecycles (chronicle.md §4.6); private.
CREATE TABLE lc_chr_saga (
	lifecycle_id BIGINT NOT NULL,
	item_id INT NOT NULL,
	object_id INT NOT NULL,
	began_at TIMESTAMP(3) NOT NULL,
	began_kind ENUM('CRAFTED', 'LOOTED', 'BOUGHT', 'REWARDED', 'EXCHANGED', 'OTHER') NOT NULL,
	ended_at TIMESTAMP(3) NULL,
	end_kind ENUM('DESTROYED', 'CRYSTALLIZED', 'MERGED', 'OTHER') NULL,
	pvp_kills INT NOT NULL DEFAULT 0,
	live_object INT AS (IF(ended_at IS NULL, object_id, NULL)) PERSISTENT,
	PRIMARY KEY (lifecycle_id),
	UNIQUE KEY uk_chr_saga_live_object (live_object),
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

-- Battles: samples and marks are private until their replay; a cell never shows fewer than two (chronicle.md §4.7).
CREATE TABLE lc_chr_battle (
	battle_key VARCHAR(48) NOT NULL,
	kind ENUM('CASTLE_SIEGE', 'FORT_SIEGE', 'TERRITORY_WAR') NOT NULL,
	place_id INT NOT NULL,
	started_at TIMESTAMP(3) NOT NULL,
	ended_at TIMESTAMP(3) NULL,
	end_fact_id BIGINT NULL,
	history_row_id BIGINT NULL,
	PRIMARY KEY (battle_key),
	CONSTRAINT chk_chr_battle_span CHECK ((ended_at IS NULL) = (end_fact_id IS NULL) AND (ended_at IS NULL OR ended_at >= started_at))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_chr_battle_sample (
	battle_key VARCHAR(48) NOT NULL,
	minute SMALLINT NOT NULL,
	cell SMALLINT NOT NULL,
	side ENUM('ATTACKER', 'DEFENDER') NOT NULL,
	count SMALLINT NOT NULL,
	PRIMARY KEY (battle_key, minute, cell, side),
	CONSTRAINT chk_chr_battle_sample CHECK (count >= 2 AND minute >= 0 AND cell >= 0),
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

-- Letters: queued, decided by a moderator, published once, withdrawn with their author (chronicle.md §3.4).
CREATE TABLE lc_chr_letter (
	letter_id BIGINT NOT NULL AUTO_INCREMENT,
	author_char INT NOT NULL,
	text VARCHAR(600) NOT NULL,
	submitted_at TIMESTAMP(3) NOT NULL,
	state ENUM('QUEUED', 'APPROVED', 'REJECTED', 'PUBLISHED', 'WITHDRAWN') NOT NULL DEFAULT 'QUEUED',
	moderator VARCHAR(64) NULL,
	decided_at TIMESTAMP(3) NULL,
	reason VARCHAR(128) NULL,
	PRIMARY KEY (letter_id),
	CONSTRAINT chk_chr_letter_decided CHECK ((state = 'QUEUED') = (moderator IS NULL AND decided_at IS NULL) AND (state <> 'REJECTED' OR reason IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_chr_letter_insert BEFORE INSERT ON lc_chr_letter FOR EACH ROW IF NEW.state <> 'QUEUED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_chr_letter: a letter starts queued'; END IF;

CREATE TRIGGER lc_chr_letter_transition BEFORE UPDATE ON lc_chr_letter FOR EACH ROW IF NEW.text <> OLD.text OR NEW.author_char <> OLD.author_char OR NOT ((OLD.state, NEW.state) IN (('QUEUED', 'APPROVED'), ('QUEUED', 'REJECTED'), ('APPROVED', 'PUBLISHED'), ('APPROVED', 'WITHDRAWN'), ('PUBLISHED', 'WITHDRAWN'))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_chr_letter: not a transition of the letter machine'; END IF;

-- Narrated choices and name-free flavour (chronicle.md §4.8).
CREATE TABLE lc_chr_prose (
	dto_hash BINARY(32) NOT NULL,
	lang ENUM('EN', 'RO', 'RU') NOT NULL,
	style VARCHAR(24) NOT NULL,
	choices JSON NOT NULL,
	flavour TEXT NULL,
	checked_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (dto_hash, lang, style),
	CONSTRAINT chk_chr_prose_choices CHECK (JSON_VALID(choices) AND JSON_TYPE(choices) = 'ARRAY')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_chr_crier (
	crier_id INT NOT NULL,
	town_id INT NOT NULL,
	last_said_at TIMESTAMP(3) NULL,
	PRIMARY KEY (crier_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_chr_crier_log (
	town_id INT NOT NULL,
	kind VARCHAR(24) NOT NULL,
	ref BIGINT NOT NULL,
	said_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (town_id, kind, ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The readers (chronicle.md §2.1, §4.9): the views and the prose cache, nothing else.
CREATE ROLE IF NOT EXISTS lc_chronicle_web;

CREATE ROLE IF NOT EXISTS lc_chronicle_narrator;

GRANT SELECT ON lc_pub_visible TO lc_chronicle_web, lc_chronicle_narrator;

GRANT SELECT ON lc_pub_successor TO lc_chronicle_web, lc_chronicle_narrator;

GRANT SELECT ON lc_pub_name_visible TO lc_chronicle_web, lc_chronicle_narrator;

GRANT SELECT ON lc_pub_character_page TO lc_chronicle_web, lc_chronicle_narrator;

GRANT SELECT ON lc_chr_prose TO lc_chronicle_web;

GRANT SELECT, INSERT, UPDATE ON lc_chr_prose TO lc_chronicle_narrator;
