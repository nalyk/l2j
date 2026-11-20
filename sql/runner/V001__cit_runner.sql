-- SPEC-CIT (docs/spec/citizens.md §2): the citizen runner's own schema. Applied to the runner's database, never the
-- game's; its user can read nothing of the game.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

CREATE TABLE cit_principal (
	principal_id INT NOT NULL,
	name VARCHAR(64) NOT NULL,
	budget_policy JSON NOT NULL,
	information_policy JSON NOT NULL,
	goals JSON NOT NULL,
	PRIMARY KEY (principal_id),
	CONSTRAINT chk_cit_principal_json CHECK (JSON_VALID(budget_policy) AND JSON_VALID(information_policy) AND JSON_VALID(goals))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE cit_account (
	account_id INT NOT NULL,
	login VARCHAR(45) NOT NULL,
	secret_ref VARCHAR(128) NOT NULL,
	principal_id INT NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	retired BOOLEAN NOT NULL DEFAULT FALSE,
	suspended_at TIMESTAMP(3) NULL,
	PRIMARY KEY (account_id),
	UNIQUE KEY uk_cit_account_login (login),
	CONSTRAINT fk_cit_account_principal FOREIGN KEY (principal_id) REFERENCES cit_principal (principal_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE cit_character (
	char_id INT NOT NULL,
	account_id INT NOT NULL,
	name VARCHAR(35) NOT NULL,
	class_id SMALLINT NOT NULL,
	persona VARCHAR(32) NOT NULL,
	state ENUM('ACTIVE', 'RESTING', 'QUIT') NOT NULL DEFAULT 'ACTIVE',
	PRIMARY KEY (char_id),
	CONSTRAINT fk_cit_character_account FOREIGN KEY (account_id) REFERENCES cit_account (account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER cit_character_quit_final BEFORE UPDATE ON cit_character FOR EACH ROW IF OLD.state = 'QUIT' AND NEW.state <> 'QUIT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_character: a citizen who quit never returns'; END IF;

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
	PRIMARY KEY (obs_id, at),
	KEY idx_cit_observation_char (char_id, kind, at),
	CONSTRAINT chk_cit_observation CHECK (JSON_VALID(payload) AND ((provenance IN ('TOLD', 'OOB', 'INFERRED')) = (source_ref IS NOT NULL)))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 PARTITION BY RANGE (UNIX_TIMESTAMP(at)) (PARTITION p_catchall VALUES LESS THAN MAXVALUE);

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
	sentiment SMALLINT NOT NULL DEFAULT 0,
	PRIMARY KEY (citizen_char_id, other_char_id),
	CONSTRAINT chk_cit_relation CHECK (citizen_char_id <> other_char_id AND last_seen >= first_seen AND trades >= 0 AND contracts_ok >= 0 AND contracts_failed >= 0 AND parties >= 0 AND killed_by >= 0 AND killed >= 0 AND rescued_by >= 0 AND sentiment BETWEEN -1000 AND 1000 AND JSON_VALID(notes_tags)),
	CONSTRAINT fk_cit_relation_char FOREIGN KEY (citizen_char_id) REFERENCES cit_character (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE cit_oob_message (
	msg_id BIGINT NOT NULL AUTO_INCREMENT,
	principal_id INT NOT NULL,
	from_char INT NOT NULL,
	channel VARCHAR(32) NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	text VARCHAR(500) NOT NULL,
	observation_refs JSON NOT NULL,
	PRIMARY KEY (msg_id),
	CONSTRAINT chk_cit_oob_refs CHECK (JSON_VALID(observation_refs) AND JSON_TYPE(observation_refs) = 'ARRAY' AND JSON_LENGTH(observation_refs) > 0),
	CONSTRAINT fk_cit_oob_principal FOREIGN KEY (principal_id) REFERENCES cit_principal (principal_id),
	CONSTRAINT fk_cit_oob_from FOREIGN KEY (from_char) REFERENCES cit_character (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER cit_oob_member BEFORE INSERT ON cit_oob_message FOR EACH ROW IF NOT EXISTS (SELECT 1 FROM cit_character c JOIN cit_account a ON a.account_id = c.account_id WHERE c.char_id = NEW.from_char AND a.principal_id = NEW.principal_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_oob_message: only a member writes to its principal''s channel'; END IF;

CREATE TABLE cit_decision_log (
	decision_id BIGINT NOT NULL AUTO_INCREMENT,
	char_id INT NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	policy VARCHAR(48) NOT NULL,
	policy_ver SMALLINT NOT NULL,
	inputs JSON NOT NULL,
	choice JSON NOT NULL,
	rng_key BINARY(16) NOT NULL,
	PRIMARY KEY (decision_id),
	KEY idx_cit_decision_char (char_id, at),
	CONSTRAINT chk_cit_decision CHECK (JSON_VALID(inputs) AND JSON_TYPE(inputs) = 'ARRAY' AND JSON_VALID(choice))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER cit_decision_log_frozen BEFORE UPDATE ON cit_decision_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cit_decision_log: decisions are never changed';
