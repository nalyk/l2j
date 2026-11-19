-- SPEC-ACTOR (docs/spec/actor-identity.md §2): accounts with their server-assigned origin, principals, and the policy
-- tables, the only place actors are told apart.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it. The two triggers are single
-- statements (no BEGIN ... END), so they need no delimiter change.

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
	PRIMARY KEY (account_id),
	UNIQUE KEY uk_account_login (login),
	CONSTRAINT fk_account_principal FOREIGN KEY (principal_id) REFERENCES lc_principal (principal_id),
	CONSTRAINT chk_account_profile CHECK ((origin = 'SYNTHETIC_CITIZEN') = (sim_profile IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Origin and login never change, and an account row is never deleted (ACTOR-INV01).
CREATE TRIGGER lc_account_immutable BEFORE UPDATE ON lc_account FOR EACH ROW IF (NEW.origin <> OLD.origin) OR (NEW.login <> OLD.login) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: origin and login never change'; END IF;

CREATE TRIGGER lc_account_kept BEFORE DELETE ON lc_account FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: rows are never deleted';

CREATE TABLE lc_actor_policy (
	module VARCHAR(16) NOT NULL,
	rule VARCHAR(48) NOT NULL,
	origin ENUM('HUMAN', 'SYNTHETIC_CITIZEN', 'NPC_FAKE', 'NPC', 'SYSTEM') NOT NULL,
	activity ENUM('INTERACTIVE', 'OFFLINE_TRADE', 'OFFLINE_PLAY', 'DISCONNECTED', 'ANY') NOT NULL,
	allowed BOOLEAN NOT NULL,
	config_ver INT NOT NULL,
	PRIMARY KEY (module, rule, origin, activity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
