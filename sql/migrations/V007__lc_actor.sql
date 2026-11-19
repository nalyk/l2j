-- SPEC-ACTOR (docs/spec/actor-identity.md §2): accounts with their server-assigned origin, characters' bindings to them,
-- principals, the identity audit, and the policy tables, the only place actors are told apart.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it. Each trigger is written on
-- one line: a body of several statements is a BEGIN ... END block whose inner ';' are not at a line's end, and the whole
-- line is sent as one statement, which the server parses as a compound statement.

CREATE TABLE lc_principal (
	principal_id INT NOT NULL AUTO_INCREMENT,
	kind ENUM('HUMAN_ACCOUNT', 'CITIZEN_HOUSEHOLD') NOT NULL,
	name VARCHAR(64) NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (principal_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- human_principal_id makes a human's principal exclusive by a unique key, so concurrent inserts cannot share one.
CREATE TABLE lc_account (
	account_id INT NOT NULL AUTO_INCREMENT,
	login VARCHAR(45) NOT NULL,
	origin ENUM('HUMAN', 'SYNTHETIC_CITIZEN') NOT NULL,
	sim_profile ENUM('RESIDENT', 'SCENARIO_ADVERSARY', 'TEST') NULL,
	principal_id INT NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	provisioned_by VARCHAR(32) NOT NULL,
	retired BOOLEAN NOT NULL DEFAULT FALSE,
	identity_ver INT NOT NULL DEFAULT 0,
	human_principal_id INT AS (IF(origin = 'HUMAN', principal_id, NULL)) PERSISTENT,
	PRIMARY KEY (account_id),
	UNIQUE KEY uk_account_login (login),
	UNIQUE KEY uk_account_human_principal (human_principal_id),
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

-- One admin change of one field of one account: named in the session (@lc_identity_audit) by the transaction that makes
-- the change, used once (the account trigger marks it), otherwise immutable and never deleted.
CREATE TABLE lc_identity_audit (
	audit_id BIGINT NOT NULL AUTO_INCREMENT,
	account_id INT NOT NULL,
	field ENUM('SIM_PROFILE', 'PRINCIPAL', 'RETIRED') NOT NULL,
	before_value VARCHAR(32) NULL,
	after_value VARCHAR(32) NOT NULL,
	admin VARCHAR(32) NOT NULL,
	reason VARCHAR(128) NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	used_at TIMESTAMP(3) NULL,
	PRIMARY KEY (audit_id),
	CONSTRAINT fk_audit_account FOREIGN KEY (account_id) REFERENCES lc_account (account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_principal_kind_frozen BEFORE UPDATE ON lc_principal FOR EACH ROW IF NEW.kind <> OLD.kind OR NEW.principal_id <> OLD.principal_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_principal: a principal keeps its id and kind'; END IF;

CREATE TRIGGER lc_principal_kept BEFORE DELETE ON lc_principal FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_principal: rows are never deleted';

-- A human's principal is a HUMAN_ACCOUNT principal, a citizen's a household; exclusivity is the unique key above.
CREATE TRIGGER lc_account_principal_insert BEFORE INSERT ON lc_account FOR EACH ROW IF (SELECT kind FROM lc_principal WHERE principal_id = NEW.principal_id) <> IF(NEW.origin = 'HUMAN', 'HUMAN_ACCOUNT', 'CITIZEN_HOUSEHOLD') OR NEW.retired OR NEW.identity_ver <> 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: principal of the wrong kind, or created retired or versioned'; END IF;

-- Id, login and origin never change. At most one of profile, principal and retirement changes per statement, only with an
-- unused audit row for that account, that field and that new value, the identity version growing by one; the audit row is
-- then marked used. Retirement is one-way. A principal must be of the account's kind.
CREATE TRIGGER lc_account_guarded BEFORE UPDATE ON lc_account FOR EACH ROW BEGIN DECLARE changed INT DEFAULT (NOT (NEW.sim_profile <=> OLD.sim_profile)) + (NEW.principal_id <> OLD.principal_id) + (NEW.retired <> OLD.retired); DECLARE field_name VARCHAR(16) DEFAULT CASE WHEN NOT (NEW.sim_profile <=> OLD.sim_profile) THEN 'SIM_PROFILE' WHEN NEW.principal_id <> OLD.principal_id THEN 'PRINCIPAL' ELSE 'RETIRED' END; DECLARE new_value VARCHAR(32) DEFAULT CASE WHEN NOT (NEW.sim_profile <=> OLD.sim_profile) THEN NEW.sim_profile WHEN NEW.principal_id <> OLD.principal_id THEN CAST(NEW.principal_id AS CHAR) ELSE IF(NEW.retired, 'TRUE', 'FALSE') END; IF NEW.account_id <> OLD.account_id OR NEW.login <> OLD.login OR NEW.origin <> OLD.origin OR (OLD.retired AND NOT NEW.retired) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: id, login and origin never change; retirement is one-way'; END IF; IF changed > 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: one audited change per statement'; END IF; IF changed = 0 AND NEW.identity_ver <> OLD.identity_ver THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: the identity version changes only with an audited change'; END IF; IF changed = 1 THEN IF NEW.identity_ver <> OLD.identity_ver + 1 OR NOT EXISTS (SELECT 1 FROM lc_identity_audit WHERE audit_id = @lc_identity_audit AND account_id = OLD.account_id AND field = field_name AND after_value = new_value AND used_at IS NULL) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: a change needs its unused audit row and the next identity version'; END IF; IF NEW.principal_id <> OLD.principal_id AND (SELECT kind FROM lc_principal WHERE principal_id = NEW.principal_id) <> IF(NEW.origin = 'HUMAN', 'HUMAN_ACCOUNT', 'CITIZEN_HOUSEHOLD') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: principal of the wrong kind'; END IF; UPDATE lc_identity_audit SET used_at = NOW(3) WHERE audit_id = @lc_identity_audit; END IF; END;

CREATE TRIGGER lc_account_kept BEFORE DELETE ON lc_account FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_account: rows are never deleted';

-- An audit row may only be marked used, once; it is never otherwise changed or deleted.
CREATE TRIGGER lc_identity_audit_frozen BEFORE UPDATE ON lc_identity_audit FOR EACH ROW IF NOT (OLD.used_at IS NULL AND NEW.used_at IS NOT NULL AND NEW.audit_id = OLD.audit_id AND NEW.account_id = OLD.account_id AND NEW.field = OLD.field AND NEW.before_value <=> OLD.before_value AND NEW.after_value = OLD.after_value AND NEW.admin = OLD.admin AND NEW.reason = OLD.reason AND NEW.at = OLD.at) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_identity_audit: rows are only marked used, once'; END IF;

CREATE TRIGGER lc_identity_audit_kept BEFORE DELETE ON lc_identity_audit FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_identity_audit: rows are never deleted';

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
