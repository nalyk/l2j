-- SPEC-ACTOR (docs/spec/actor-identity.md §2): accounts with their server-assigned origin, characters' bindings to them,
-- principals, the identity audit, and the policy tables, the only place actors are told apart.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it. Each trigger body is a
-- single IF statement (no BEGIN ... END), on one line, so it needs no delimiter change.

CREATE TABLE lc_principal (
	principal_id INT NOT NULL AUTO_INCREMENT,
	kind ENUM('HUMAN_ACCOUNT', 'CITIZEN_HOUSEHOLD') NOT NULL,
	name VARCHAR(64) NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (principal_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_account (
	account_id INT NOT NULL AUTO_INCREMENT,
	login VARCHAR(45) NOT NULL,
	origin ENUM('HUMAN', 'SYNTHETIC_CITIZEN') NOT NULL,
	sim_profile ENUM('RESIDENT', 'SCENARIO_ADVERSARY', 'TEST') NULL,
	principal_id INT NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	provisioned_by VARCHAR(32) NOT NULL,
	retired BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (account_id),
	UNIQUE KEY uk_account_login (login),
	CONSTRAINT fk_account_principal FOREIGN KEY (principal_id) REFERENCES lc_principal (principal_id),
	CONSTRAINT chk_account_profile CHECK ((origin = 'SYNTHETIC_CITIZEN') = (sim_profile IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_character (
	char_id INT NOT NULL,
	account_id INT NOT NULL,
	bound_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id),
	KEY idx_character_account (account_id),
	CONSTRAINT fk_character_account FOREIGN KEY (account_id) REFERENCES lc_account (account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- An admin change of a citizen's profile or principal: the service writes this row and names it in the session
-- (@lc_identity_audit) in the same transaction as the change; without it the change is refused.
CREATE TABLE lc_identity_audit (
	audit_id BIGINT NOT NULL AUTO_INCREMENT,
	account_id INT NOT NULL,
	field ENUM('SIM_PROFILE', 'PRINCIPAL') NOT NULL,
	before_value VARCHAR(32) NULL,
	after_value VARCHAR(32) NOT NULL,
	admin VARCHAR(32) NOT NULL,
	reason VARCHAR(128) NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (audit_id),
	CONSTRAINT fk_audit_account FOREIGN KEY (account_id) REFERENCES lc_account (account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A human's principal is its own HUMAN_ACCOUNT principal, shared with no other account; a citizen's is a household.
CREATE TRIGGER lc_account_principal_insert BEFORE INSERT ON lc_account FOR EACH ROW IF ((SELECT kind FROM lc_principal WHERE principal_id = NEW.principal_id) <> IF(NEW.origin = 'HUMAN', 'HUMAN_ACCOUNT', 'CITIZEN_HOUSEHOLD')) OR ((NEW.origin = 'HUMAN') AND EXISTS (SELECT 1 FROM lc_account WHERE principal_id = NEW.principal_id)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: principal of the wrong kind, or a human principal shared'; END IF;

-- Id, login and origin never change; profile and principal change only with their audit row; principals keep their kind.
CREATE TRIGGER lc_account_immutable BEFORE UPDATE ON lc_account FOR EACH ROW IF (NEW.account_id <> OLD.account_id) OR (NEW.login <> OLD.login) OR (NEW.origin <> OLD.origin) OR (((NOT (NEW.sim_profile <=> OLD.sim_profile)) OR (NEW.principal_id <> OLD.principal_id)) AND NOT EXISTS (SELECT 1 FROM lc_identity_audit WHERE audit_id = @lc_identity_audit AND account_id = OLD.account_id)) OR ((NEW.principal_id <> OLD.principal_id) AND (((SELECT kind FROM lc_principal WHERE principal_id = NEW.principal_id) <> IF(NEW.origin = 'HUMAN', 'HUMAN_ACCOUNT', 'CITIZEN_HOUSEHOLD')) OR ((NEW.origin = 'HUMAN') AND EXISTS (SELECT 1 FROM lc_account WHERE principal_id = NEW.principal_id AND account_id <> OLD.account_id)))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: id, login and origin never change; profile and principal only with their audit and kind'; END IF;

CREATE TRIGGER lc_account_kept BEFORE DELETE ON lc_account FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: rows are never deleted';

CREATE TRIGGER lc_character_bound BEFORE UPDATE ON lc_character FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_character: a binding never changes';

CREATE TRIGGER lc_character_kept BEFORE DELETE ON lc_character FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_character: bindings are never deleted';

CREATE TABLE lc_actor_policy (
	module VARCHAR(16) NOT NULL,
	rule VARCHAR(48) NOT NULL,
	domain ENUM('PARTICIPATION', 'METRICS', 'PUBLICATION') NOT NULL,
	origin ENUM('HUMAN', 'SYNTHETIC_CITIZEN', 'NPC_FAKE', 'NPC', 'SYSTEM') NOT NULL,
	activity ENUM('INTERACTIVE', 'OFFLINE_TRADE', 'OFFLINE_PLAY', 'DISCONNECTED', 'ANY') NOT NULL,
	allowed BOOLEAN NOT NULL,
	config_ver INT NOT NULL,
	PRIMARY KEY (module, rule, origin, activity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
