-- SPEC-CIT (docs/spec/citizens.md §2): the citizen runner's own schema. Applied to the runner's own database (lc_runner),
-- never the game's; the runner's user can read nothing of the game, and the game's user nothing of this.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it. A trigger is written on
-- one line: a body of several statements is a BEGIN ... END block whose inner ';' are not at a line's end.
-- Triggers come after every table, since several read tables defined later.

CREATE TABLE cit_principal (
	principal_id INT NOT NULL,
	name VARCHAR(64) NOT NULL,
	budget_policy JSON NOT NULL,
	information_policy JSON NOT NULL,
	goals JSON NOT NULL,
	PRIMARY KEY (principal_id),
	CONSTRAINT chk_cit_principal_json CHECK (JSON_VALID(budget_policy) AND JSON_VALID(information_policy) AND JSON_VALID(goals))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Mirrors lc_account (actor-identity.md §2.2) through the identity import only (§4.6); identity_ver is lc_account's.
CREATE TABLE cit_account (
	account_id INT NOT NULL,
	login VARCHAR(45) NOT NULL,
	secret_ref VARCHAR(128) NOT NULL,
	principal_id INT NOT NULL,
	identity_ver INT NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	retired BOOLEAN NOT NULL DEFAULT FALSE,
	suspended_at TIMESTAMP(3) NULL,
	PRIMARY KEY (account_id),
	UNIQUE KEY uk_cit_account_login (login),
	CONSTRAINT chk_cit_account CHECK (identity_ver >= 1),
	CONSTRAINT fk_cit_account_principal FOREIGN KEY (principal_id) REFERENCES cit_principal (principal_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE cit_character (
	char_id INT NOT NULL,
	account_id INT NOT NULL,
	name VARCHAR(35) NOT NULL,
	class_id SMALLINT NOT NULL,
	persona VARCHAR(32) NOT NULL,
	state ENUM('ACTIVE', 'RESTING', 'QUIT') NOT NULL DEFAULT 'ACTIVE',
	online BOOLEAN NOT NULL DEFAULT FALSE,
	online_account INT AS (IF(online, account_id, NULL)) STORED,
	PRIMARY KEY (char_id),
	UNIQUE KEY uk_cit_character_online (online_account),
	CONSTRAINT chk_cit_character CHECK (NOT (online AND state = 'QUIT')),
	CONSTRAINT fk_cit_character_account FOREIGN KEY (account_id) REFERENCES cit_account (account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE cit_persona_state (
	char_id INT NOT NULL,
	goal VARCHAR(48) NOT NULL,
	plan JSON NOT NULL,
	budget JSON NOT NULL,
	updated_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id),
	CONSTRAINT fk_cit_persona_char FOREIGN KEY (char_id) REFERENCES cit_character (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE cit_schedule (
	char_id INT NOT NULL,
	weekday TINYINT NOT NULL,
	start_minute SMALLINT NOT NULL,
	end_minute SMALLINT NOT NULL,
	PRIMARY KEY (char_id, weekday, start_minute),
	CONSTRAINT chk_cit_schedule CHECK (weekday BETWEEN 1 AND 7 AND start_minute BETWEEN 0 AND 1439 AND end_minute > start_minute AND end_minute <= 1440),
	CONSTRAINT fk_cit_schedule_char FOREIGN KEY (char_id) REFERENCES cit_character (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Partitioned by id ranges (ids grow with time; the runner splits p_catchall at each month's first id), so obs_id alone
-- is the key every reference uses. Only SEEN and HEARD rows are ever summarised.
CREATE TABLE cit_observation (
	obs_id BIGINT NOT NULL AUTO_INCREMENT,
	char_id INT NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	kind VARCHAR(32) NOT NULL,
	subject_kind VARCHAR(16) NOT NULL,
	subject_ref BIGINT NOT NULL,
	payload JSON NOT NULL,
	provenance ENUM('SEEN', 'HEARD', 'TOLD', 'OOB', 'INFERRED') NOT NULL,
	source_ref BIGINT NULL,
	tier ENUM('RAW', 'SUMMARY') NOT NULL DEFAULT 'RAW',
	expires_at TIMESTAMP(3) NULL,
	PRIMARY KEY (obs_id),
	KEY idx_cit_observation_char (char_id, kind, at),
	CONSTRAINT chk_cit_observation CHECK (JSON_VALID(payload) AND ((provenance IN ('TOLD', 'OOB', 'INFERRED')) = (source_ref IS NOT NULL)) AND (tier = 'RAW' OR provenance IN ('SEEN', 'HEARD')))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 PARTITION BY RANGE (obs_id) (PARTITION p_catchall VALUES LESS THAN MAXVALUE);

-- A summary's raw rows and an inference's further evidence, kept after the rows themselves expire.
CREATE TABLE cit_observation_source (
	obs_id BIGINT NOT NULL,
	source_obs_id BIGINT NOT NULL,
	PRIMARY KEY (obs_id, source_obs_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Sentiment is the function of §4.9, computed by the database: nothing can write another value.
CREATE TABLE cit_relation (
	citizen_char_id INT NOT NULL,
	other_char_id INT NOT NULL,
	first_seen TIMESTAMP(3) NOT NULL,
	last_seen TIMESTAMP(3) NOT NULL,
	trades INT NOT NULL DEFAULT 0,
	trade_value BIGINT NOT NULL DEFAULT 0,
	contracts_ok INT NOT NULL DEFAULT 0,
	contracts_failed INT NOT NULL DEFAULT 0,
	parties INT NOT NULL DEFAULT 0,
	killed_by INT NOT NULL DEFAULT 0,
	killed INT NOT NULL DEFAULT 0,
	rescued_by INT NOT NULL DEFAULT 0,
	debts_owed BIGINT NOT NULL DEFAULT 0,
	notes_tags JSON NOT NULL,
	sentiment SMALLINT AS (LEAST(1000, GREATEST(-1000, 30 * trades + 60 * contracts_ok - 150 * contracts_failed + 20 * parties + 200 * rescued_by - 250 * killed_by - 50 * killed - LEAST(300, debts_owed DIV 10000)))) STORED,
	PRIMARY KEY (citizen_char_id, other_char_id),
	CONSTRAINT chk_cit_relation CHECK (citizen_char_id <> other_char_id AND last_seen >= first_seen AND trades >= 0 AND trade_value >= 0 AND contracts_ok >= 0 AND contracts_failed >= 0 AND parties >= 0 AND killed_by >= 0 AND killed >= 0 AND rescued_by >= 0 AND debts_owed >= 0 AND JSON_VALID(notes_tags)),
	CONSTRAINT fk_cit_relation_char FOREIGN KEY (citizen_char_id) REFERENCES cit_character (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE cit_oob_message (
	msg_id BIGINT NOT NULL AUTO_INCREMENT,
	principal_id INT NOT NULL,
	from_char INT NOT NULL,
	channel VARCHAR(32) NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	template_id VARCHAR(48) NOT NULL,
	args JSON NOT NULL,
	text VARCHAR(500) NOT NULL,
	observation_refs JSON NOT NULL,
	PRIMARY KEY (msg_id),
	KEY idx_cit_oob_principal (principal_id, at),
	CONSTRAINT chk_cit_oob_refs CHECK (JSON_VALID(args) AND JSON_VALID(observation_refs) AND JSON_TYPE(observation_refs) = 'ARRAY' AND JSON_LENGTH(observation_refs) > 0),
	CONSTRAINT fk_cit_oob_principal FOREIGN KEY (principal_id) REFERENCES cit_principal (principal_id),
	CONSTRAINT fk_cit_oob_from FOREIGN KEY (from_char) REFERENCES cit_character (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The governor's caps (§4.10): imported by an admin, computed from telemetry windows ending at least a day before they apply.
CREATE TABLE cit_governor_cap (
	cap_id BIGINT NOT NULL AUTO_INCREMENT,
	principal_id INT NOT NULL,
	kind ENUM('EXTRACTION', 'RETUNE') NOT NULL,
	region_id SMALLINT NULL,
	family SMALLINT NULL,
	value BIGINT NOT NULL,
	window_end TIMESTAMP(3) NOT NULL,
	valid_from TIMESTAMP(3) NOT NULL,
	imported_by VARCHAR(32) NOT NULL,
	PRIMARY KEY (cap_id),
	KEY idx_cit_governor_principal (principal_id, kind, valid_from),
	CONSTRAINT chk_cit_governor_cap CHECK (value >= 0 AND valid_from >= window_end + INTERVAL 1 DAY AND (kind <> 'EXTRACTION' OR (region_id IS NOT NULL AND family IS NOT NULL))),
	CONSTRAINT fk_cit_governor_principal FOREIGN KEY (principal_id) REFERENCES cit_principal (principal_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- inputs: the citizen's own observation ids the decision read (written by the policy view, §4.5); constraints: the
-- governor caps that masked its options; the snapshot holds what was read, so a decision replays after retention.
CREATE TABLE cit_decision_log (
	decision_id BIGINT NOT NULL AUTO_INCREMENT,
	char_id INT NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	policy VARCHAR(48) NOT NULL,
	policy_ver SMALLINT NOT NULL,
	config_ver INT NOT NULL,
	inputs JSON NOT NULL,
	constraints JSON NOT NULL,
	persona_state JSON NOT NULL,
	input_snapshot JSON NOT NULL,
	snapshot_sha256 BINARY(32) NOT NULL,
	choice JSON NOT NULL,
	rng_key BINARY(16) NOT NULL,
	PRIMARY KEY (decision_id),
	KEY idx_cit_decision_char (char_id, at),
	CONSTRAINT chk_cit_decision CHECK (JSON_VALID(inputs) AND JSON_TYPE(inputs) = 'ARRAY' AND JSON_VALID(constraints) AND JSON_TYPE(constraints) = 'ARRAY' AND JSON_VALID(persona_state) AND JSON_VALID(input_snapshot) AND JSON_VALID(choice))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The shared-address login guard (§4.7): one row; failures since the last solo success and credentials awaiting an answer.
CREATE TABLE cit_login_guard (
	guard_id TINYINT NOT NULL,
	ban_threshold INT NOT NULL,
	halt_at INT NOT NULL,
	failures INT NOT NULL,
	in_flight INT NOT NULL,
	halted_at TIMESTAMP(3) NULL,
	PRIMARY KEY (guard_id),
	CONSTRAINT chk_cit_login_guard CHECK (guard_id = 1 AND failures >= 0 AND in_flight >= 0 AND halt_at >= 1 AND halt_at < ban_threshold AND failures + in_flight < ban_threshold)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE cit_participation_limit (
	kind ENUM('OLYMPIAD', 'EVENT') NOT NULL,
	max_slots TINYINT NOT NULL,
	PRIMARY KEY (kind),
	CONSTRAINT chk_cit_participation_limit CHECK (max_slots BETWEEN 0 AND 50)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A principal's concurrent registrations (§4.7): a slot is reserved before the registration is sent, released (deleted)
-- only when its citizen observes itself unregistered.
CREATE TABLE cit_participation (
	principal_id INT NOT NULL,
	kind ENUM('OLYMPIAD', 'EVENT') NOT NULL,
	slot TINYINT NOT NULL,
	char_id INT NOT NULL,
	state ENUM('RESERVED', 'REGISTERED') NOT NULL,
	since TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (principal_id, kind, slot),
	UNIQUE KEY uk_cit_participation_char (char_id, kind),
	CONSTRAINT chk_cit_participation CHECK (slot >= 1),
	CONSTRAINT fk_cit_participation_char FOREIGN KEY (char_id) REFERENCES cit_character (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER cit_account_mirror BEFORE UPDATE ON cit_account FOR EACH ROW BEGIN IF NEW.account_id <> OLD.account_id OR NEW.login <> OLD.login THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_account: id and login never change'; END IF; IF OLD.retired AND NOT NEW.retired THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_account: retirement is one-way'; END IF; IF NEW.identity_ver < OLD.identity_ver OR ((NEW.principal_id <> OLD.principal_id OR NEW.retired <> OLD.retired) AND NEW.identity_ver <= OLD.identity_ver) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_account: an identity change carries a newer identity version'; END IF; IF NEW.principal_id <> OLD.principal_id AND (EXISTS (SELECT 1 FROM cit_character WHERE account_id = OLD.account_id AND online) OR EXISTS (SELECT 1 FROM cit_participation p JOIN cit_character c ON c.char_id = p.char_id WHERE c.account_id = OLD.account_id)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_account: a principal changes only with no character online and no participation held'; END IF; END;

CREATE TRIGGER cit_character_quit_final BEFORE UPDATE ON cit_character FOR EACH ROW IF OLD.state = 'QUIT' AND NEW.state <> 'QUIT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_character: a citizen who quit never returns'; END IF;

CREATE TRIGGER cit_observation_source_valid BEFORE INSERT ON cit_observation FOR EACH ROW BEGIN IF NEW.provenance = 'INFERRED' AND NOT EXISTS (SELECT 1 FROM cit_observation WHERE obs_id = NEW.source_ref AND char_id = NEW.char_id AND at <= NEW.at) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_observation: an inference rests on an earlier observation of the same citizen'; END IF; IF NEW.provenance = 'OOB' AND NOT EXISTS (SELECT 1 FROM cit_oob_message m JOIN cit_character c ON c.char_id = NEW.char_id JOIN cit_account a ON a.account_id = c.account_id WHERE m.msg_id = NEW.source_ref AND m.principal_id = a.principal_id AND m.at <= NEW.at) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_observation: only a member reads its principal''s channel, after the message was written'; END IF; END;

CREATE TRIGGER cit_observation_frozen BEFORE UPDATE ON cit_observation FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_observation: observations are never changed';

CREATE TRIGGER cit_observation_source_own BEFORE INSERT ON cit_observation_source FOR EACH ROW IF NOT EXISTS (SELECT 1 FROM cit_observation o JOIN cit_observation s ON s.obs_id = NEW.source_obs_id WHERE o.obs_id = NEW.obs_id AND s.char_id = o.char_id AND s.at <= o.at AND (o.tier = 'SUMMARY' OR o.provenance = 'INFERRED')) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_observation_source: a summary or inference rests on earlier observations of the same citizen'; END IF;

CREATE TRIGGER cit_observation_source_frozen BEFORE UPDATE ON cit_observation_source FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_observation_source: sources are never changed';

CREATE TRIGGER cit_oob_valid BEFORE INSERT ON cit_oob_message FOR EACH ROW BEGIN IF NOT EXISTS (SELECT 1 FROM cit_character c JOIN cit_account a ON a.account_id = c.account_id WHERE c.char_id = NEW.from_char AND a.principal_id = NEW.principal_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_oob_message: only a member writes to its principal''s channel'; END IF; IF (SELECT COUNT(DISTINCT r.id) FROM JSON_TABLE(NEW.observation_refs, '$[*]' COLUMNS (id BIGINT PATH '$')) r JOIN cit_observation o ON o.obs_id = r.id WHERE o.char_id = NEW.from_char AND o.at <= NEW.at AND o.provenance <> 'OOB') <> JSON_LENGTH(NEW.observation_refs) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_oob_message: every reference is a distinct earlier observation of the writer''s own, never one read from the channel'; END IF; END;

CREATE TRIGGER cit_oob_frozen BEFORE UPDATE ON cit_oob_message FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_oob_message: messages are never changed';

CREATE TRIGGER cit_governor_cap_frozen BEFORE UPDATE ON cit_governor_cap FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_governor_cap: caps are never changed';

CREATE TRIGGER cit_decision_valid BEFORE INSERT ON cit_decision_log FOR EACH ROW BEGIN IF (SELECT COUNT(DISTINCT r.id) FROM JSON_TABLE(NEW.inputs, '$[*]' COLUMNS (id BIGINT PATH '$')) r JOIN cit_observation o ON o.obs_id = r.id WHERE o.char_id = NEW.char_id AND o.at <= NEW.at) <> JSON_LENGTH(NEW.inputs) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_decision_log: every input is a distinct earlier observation of the deciding citizen'; END IF; IF (SELECT COUNT(DISTINCT r.id) FROM JSON_TABLE(NEW.constraints, '$[*]' COLUMNS (id BIGINT PATH '$')) r JOIN cit_governor_cap g ON g.cap_id = r.id JOIN cit_character c ON c.char_id = NEW.char_id JOIN cit_account a ON a.account_id = c.account_id WHERE g.principal_id = a.principal_id AND g.valid_from <= NEW.at) <> JSON_LENGTH(NEW.constraints) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_decision_log: every constraint is a valid cap of the deciding citizen''s principal'; END IF; END;

CREATE TRIGGER cit_decision_log_frozen BEFORE UPDATE ON cit_decision_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_decision_log: decisions are never changed';

CREATE TRIGGER cit_login_guard_reset BEFORE UPDATE ON cit_login_guard FOR EACH ROW IF NEW.failures < OLD.failures AND NOT (NEW.failures = 0 AND ((OLD.in_flight = 1 AND NEW.in_flight = 0) OR (OLD.halted_at IS NOT NULL AND NEW.halted_at IS NULL AND OLD.in_flight = 0 AND NEW.in_flight = 0))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_login_guard: failures reset to 0 only when a solo credential resolves or an admin clears a halt with nothing in flight'; END IF;

CREATE TRIGGER cit_participation_slot BEFORE INSERT ON cit_participation FOR EACH ROW BEGIN IF NEW.slot > COALESCE((SELECT max_slots FROM cit_participation_limit WHERE kind = NEW.kind), 0) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_participation: a slot above the limit'; END IF; IF NOT EXISTS (SELECT 1 FROM cit_character c JOIN cit_account a ON a.account_id = c.account_id WHERE c.char_id = NEW.char_id AND a.principal_id = NEW.principal_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_participation: a slot holds a character of its principal'; END IF; END;

CREATE TRIGGER cit_participation_forward BEFORE UPDATE ON cit_participation FOR EACH ROW IF NEW.principal_id <> OLD.principal_id OR NEW.kind <> OLD.kind OR NEW.slot <> OLD.slot OR NEW.char_id <> OLD.char_id OR NOT (OLD.state = 'RESERVED' AND NEW.state = 'REGISTERED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_participation: a reservation only becomes a registration'; END IF;
