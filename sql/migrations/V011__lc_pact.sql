-- SPEC-PACT (docs/spec/pact.md §2): contracts, parties, clauses, their custodies, settlement units, craft intents and
-- outcomes, the roll secret, charter members, routed kills, participation, the public registry and the objective history.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it. Each trigger is one line.

CREATE TABLE lc_pact_contract (
	contract_id BIGINT NOT NULL AUTO_INCREMENT,
	type ENUM('CRAFTING_PACT', 'CRAFT_ORDER', 'PURCHASE_ORDER', 'DELIVERY_ORDER', 'LOAN', 'LOOT_CHARTER', 'MERCENARY', 'CARAVAN_ESCORT', 'TREATY') NOT NULL,
	state ENUM('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED', 'SIGNED', 'FUNDED', 'ACTIVE', 'COMPLETED', 'EXPIRED', 'MUTUALLY_CANCELLED', 'FAILED_BY_CONDITION', 'WITHDRAWN', 'ADMIN_REVIEW') NOT NULL,
	review_from ENUM('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED', 'SIGNED', 'FUNDED', 'ACTIVE') NULL,
	version INT NOT NULL DEFAULT 0,
	terms_version INT NOT NULL DEFAULT 1,
	visibility ENUM('PRIVATE', 'PARTIES', 'PUBLIC') NOT NULL,
	publishable BOOLEAN NOT NULL DEFAULT FALSE,
	notarius_tier ENUM('TOWN', 'ROYAL', 'CASTLE', 'CARAVAN') NOT NULL,
	notarius_town SMALLINT NOT NULL,
	territory SMALLINT NULL,
	created_by INT NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	deadline TIMESTAMP(3) NOT NULL,
	terms JSON NOT NULL,
	terms_hash BINARY(32) NOT NULL,
	value_adena BIGINT NOT NULL DEFAULT 0,
	fee_adena BIGINT NOT NULL DEFAULT 0,
	fee_payer TINYINT NOT NULL DEFAULT 1,
	review_reason VARCHAR(128) NULL,
	PRIMARY KEY (contract_id),
	KEY idx_pact_state (state, deadline),
	CONSTRAINT chk_pact_terms CHECK (JSON_VALID(terms)),
	CONSTRAINT chk_pact_terms_hash CHECK (terms_hash = UNHEX(SHA2(terms, 256))),
	CONSTRAINT chk_pact_amounts CHECK (value_adena >= 0 AND fee_adena >= 0),
	CONSTRAINT chk_pact_review CHECK ((state = 'ADMIN_REVIEW') = (review_from IS NOT NULL) AND (state = 'ADMIN_REVIEW') <= (review_reason IS NOT NULL)),
	CONSTRAINT chk_pact_publishable CHECK (NOT publishable OR visibility <> 'PRIVATE')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_pact_party (
	contract_id BIGINT NOT NULL,
	party_no TINYINT NOT NULL,
	role ENUM('CLIENT', 'CONTRACTOR', 'LENDER', 'BORROWER', 'BUYER', 'SELLER', 'DELIVERER', 'EMPLOYER', 'MERCENARY', 'SIGNATORY', 'LEADER', 'MEMBER') NOT NULL,
	kind ENUM('CHAR', 'CLAN', 'ALLIANCE') NOT NULL,
	char_id INT NULL,
	clan_id INT NULL,
	ally_id INT NULL,
	signed_terms_version INT NULL,
	signed_at TIMESTAMP(3) NULL,
	signed_token BINARY(16) NULL,
	PRIMARY KEY (contract_id, party_no),
	KEY idx_pact_party_char (char_id),
	KEY idx_pact_party_clan (clan_id),
	CONSTRAINT fk_pact_party_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id),
	CONSTRAINT chk_pact_party_kind CHECK ((kind = 'CHAR' AND char_id IS NOT NULL AND clan_id IS NULL AND ally_id IS NULL) OR (kind = 'CLAN' AND clan_id IS NOT NULL AND char_id IS NULL AND ally_id IS NULL) OR (kind = 'ALLIANCE' AND ally_id IS NOT NULL AND char_id IS NULL AND clan_id IS NULL)),
	CONSTRAINT chk_pact_party_signed CHECK ((signed_terms_version IS NULL) = (signed_at IS NULL) AND (signed_at IS NULL) = (signed_token IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_pact_clause (
	contract_id BIGINT NOT NULL,
	clause_no SMALLINT NOT NULL,
	kind ENUM('DELIVER_ITEMS', 'PAY_ADENA', 'CRAFT_ATTEMPT', 'DEADLINE', 'PARTICIPATION', 'REGISTRATION', 'LOOT_POLICY', 'COLLATERAL', 'REPAY', 'FORFEIT', 'CANCEL_CONDITION') NOT NULL,
	params JSON NOT NULL,
	PRIMARY KEY (contract_id, clause_no),
	CONSTRAINT fk_pact_clause_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id),
	CONSTRAINT chk_pact_clause_params CHECK (JSON_VALID(params))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Parties and clauses are part of the terms: they change only while the contract is in DRAFT, PROPOSED or PARTIALLY_SIGNED.
CREATE TRIGGER lc_pact_party_insert BEFORE INSERT ON lc_pact_party FOR EACH ROW IF (SELECT state FROM lc_pact_contract WHERE contract_id = NEW.contract_id) NOT IN ('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_party: parties change only before signing'; END IF;

CREATE TRIGGER lc_pact_party_update BEFORE UPDATE ON lc_pact_party FOR EACH ROW IF (SELECT state FROM lc_pact_contract WHERE contract_id = OLD.contract_id) NOT IN ('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED') OR NEW.contract_id <> OLD.contract_id OR NEW.party_no <> OLD.party_no THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_party: parties and signatures change only before signing'; END IF;

CREATE TRIGGER lc_pact_party_delete BEFORE DELETE ON lc_pact_party FOR EACH ROW IF (SELECT state FROM lc_pact_contract WHERE contract_id = OLD.contract_id) NOT IN ('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_party: parties change only before signing'; END IF;

CREATE TRIGGER lc_pact_clause_insert BEFORE INSERT ON lc_pact_clause FOR EACH ROW IF (SELECT state FROM lc_pact_contract WHERE contract_id = NEW.contract_id) NOT IN ('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_clause: clauses change only before signing'; END IF;

CREATE TRIGGER lc_pact_clause_update BEFORE UPDATE ON lc_pact_clause FOR EACH ROW IF (SELECT state FROM lc_pact_contract WHERE contract_id = OLD.contract_id) NOT IN ('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED') OR NEW.contract_id <> OLD.contract_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_clause: clauses change only before signing'; END IF;

CREATE TRIGGER lc_pact_clause_delete BEFORE DELETE ON lc_pact_clause FOR EACH ROW IF (SELECT state FROM lc_pact_contract WHERE contract_id = OLD.contract_id) NOT IN ('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_clause: clauses change only before signing'; END IF;

CREATE TABLE lc_pact_custody (
	contract_id BIGINT NOT NULL,
	purpose ENUM('FUNDING', 'COLLATERAL', 'OUTPUT', 'CHARTER', 'FORFEIT') NOT NULL,
	party_no TINYINT NULL,
	custody_id INT NOT NULL,
	PRIMARY KEY (contract_id, purpose, custody_id),
	UNIQUE KEY uk_pact_custody (custody_id),
	CONSTRAINT fk_pact_custody_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id),
	CONSTRAINT fk_pact_custody_custody FOREIGN KEY (custody_id) REFERENCES lc_custody (custody_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A unit is OPEN with what it owes until the one movement that settles it writes its key; then it never changes. A budget
-- drawn in parts is SPLIT into a settled child and an open remainder, both naming it as parent (spec §2.1).
CREATE TABLE lc_pact_unit (
	contract_id BIGINT NOT NULL,
	clause_no SMALLINT NOT NULL,
	unit INT NOT NULL,
	parent_unit INT NULL,
	state ENUM('OPEN', 'PAID', 'RETURNED', 'FORFEITED', 'NONE', 'SPLIT') NOT NULL DEFAULT 'OPEN',
	owed JSON NOT NULL,
	scope VARCHAR(16) NOT NULL DEFAULT 'PACT',
	skey VARCHAR(96) NULL,
	op_id BINARY(16) NULL,
	at TIMESTAMP(3) NULL,
	PRIMARY KEY (contract_id, clause_no, unit),
	UNIQUE KEY uk_pact_unit_key (scope, skey),
	CONSTRAINT fk_pact_unit_clause FOREIGN KEY (contract_id, clause_no) REFERENCES lc_pact_clause (contract_id, clause_no),
	CONSTRAINT fk_pact_unit_parent FOREIGN KEY (contract_id, clause_no, parent_unit) REFERENCES lc_pact_unit (contract_id, clause_no, unit),
	CONSTRAINT fk_pact_unit_key FOREIGN KEY (scope, skey) REFERENCES lc_settlement_key (scope, skey),
	CONSTRAINT chk_pact_unit_scope CHECK (scope = 'PACT'),
	CONSTRAINT chk_pact_unit_owed CHECK (JSON_VALID(owed)),
	CONSTRAINT chk_pact_unit_settled CHECK ((state = 'OPEN') = (skey IS NULL) AND (skey IS NULL) = (op_id IS NULL) AND (op_id IS NULL) = (at IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_pact_unit_insert BEFORE INSERT ON lc_pact_unit FOR EACH ROW IF NEW.state <> 'OPEN' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_unit: a unit starts open'; END IF;

CREATE TRIGGER lc_pact_unit_settle BEFORE UPDATE ON lc_pact_unit FOR EACH ROW IF OLD.state <> 'OPEN' OR NOT (NEW.owed <=> OLD.owed) OR NOT (NEW.parent_unit <=> OLD.parent_unit) OR NEW.contract_id <> OLD.contract_id OR NEW.clause_no <> OLD.clause_no OR NEW.unit <> OLD.unit THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_unit: a unit settles once and owes what it owed'; END IF;

CREATE TRIGGER lc_pact_unit_kept BEFORE DELETE ON lc_pact_unit FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_unit: units are never deleted';

-- The roll's key: generated once at installation, never changed; a new version only by an audited admin operation.
CREATE TABLE lc_pact_roll_secret (
	secret_version INT NOT NULL,
	secret BINARY(32) NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (secret_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_pact_roll_secret_frozen BEFORE UPDATE ON lc_pact_roll_secret FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_roll_secret: a secret never changes';

CREATE TRIGGER lc_pact_roll_secret_kept BEFORE DELETE ON lc_pact_roll_secret FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_roll_secret: a secret is never deleted';

-- An attempt: its inputs committed before it rolls (spec §4.3), immutable; at most one outcome, immutable.
CREATE TABLE lc_pact_craft_intent (
	contract_id BIGINT NOT NULL,
	attempt_no INT NOT NULL,
	recipe_id INT NOT NULL,
	crafter_char INT NOT NULL,
	secret_version INT NOT NULL,
	success_rate INT NOT NULL,
	masterwork_rate_ppm INT NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (contract_id, attempt_no),
	CONSTRAINT fk_pact_intent_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id),
	CONSTRAINT fk_pact_intent_secret FOREIGN KEY (secret_version) REFERENCES lc_pact_roll_secret (secret_version),
	CONSTRAINT chk_pact_intent CHECK (attempt_no > 0 AND success_rate BETWEEN 0 AND 100 AND masterwork_rate_ppm BETWEEN 0 AND 1000000)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_pact_craft_intent_frozen BEFORE UPDATE ON lc_pact_craft_intent FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_craft_intent: an intent is never changed';

CREATE TRIGGER lc_pact_craft_intent_kept BEFORE DELETE ON lc_pact_craft_intent FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_craft_intent: an intent is never deleted';

CREATE TABLE lc_pact_craft_outcome (
	contract_id BIGINT NOT NULL,
	attempt_no INT NOT NULL,
	success BOOLEAN NOT NULL,
	masterwork BOOLEAN NOT NULL,
	product_item_id INT NULL,
	product_count INT NOT NULL,
	op_id BINARY(16) NOT NULL,
	rolled_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (contract_id, attempt_no),
	CONSTRAINT fk_pact_outcome_intent FOREIGN KEY (contract_id, attempt_no) REFERENCES lc_pact_craft_intent (contract_id, attempt_no),
	CONSTRAINT chk_pact_craft_product CHECK ((success AND product_item_id IS NOT NULL AND product_count > 0) OR (NOT success AND NOT masterwork AND product_item_id IS NULL AND product_count = 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_pact_craft_outcome_frozen BEFORE UPDATE ON lc_pact_craft_outcome FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_craft_outcome: an outcome is never changed';

CREATE TRIGGER lc_pact_craft_outcome_kept BEFORE DELETE ON lc_pact_craft_outcome FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_craft_outcome: an outcome is never deleted';

CREATE TABLE lc_pact_charter_member (
	contract_id BIGINT NOT NULL,
	char_id INT NOT NULL,
	state ENUM('FROZEN_IN', 'LEFT', 'DISCONNECTED') NOT NULL,
	PRIMARY KEY (contract_id, char_id),
	CONSTRAINT fk_pact_member_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A character is frozen in at most one active charter.
CREATE TABLE lc_pact_charter_active (
	char_id INT NOT NULL,
	contract_id BIGINT NOT NULL,
	PRIMARY KEY (char_id),
	CONSTRAINT fk_pact_active_member FOREIGN KEY (contract_id, char_id) REFERENCES lc_pact_charter_member (contract_id, char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A kill is routed at most once: its key (journal epoch and the hook's kill sequence) written by the one routing operation.
CREATE TABLE lc_pact_kill (
	kill_key VARCHAR(64) NOT NULL,
	contract_id BIGINT NOT NULL,
	op_id BINARY(16) NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (kill_key),
	CONSTRAINT fk_pact_kill_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- PACT's own participation measurement (spec §4.6): one row per sampled minute of a mercenary party's member.
CREATE TABLE lc_pact_participation (
	contract_id BIGINT NOT NULL,
	battle_id BIGINT NOT NULL,
	char_id INT NOT NULL,
	clan_id INT NULL,
	minute TIMESTAMP NOT NULL,
	present BOOLEAN NOT NULL,
	active BOOLEAN NOT NULL,
	member BOOLEAN NOT NULL,
	qualifying BOOLEAN AS (present AND active AND member) PERSISTENT,
	PRIMARY KEY (contract_id, battle_id, char_id, minute),
	CONSTRAINT fk_pact_participation_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_pact_registry (
	contract_id BIGINT NOT NULL,
	public_text_key VARCHAR(64) NOT NULL,
	registered_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (contract_id),
	CONSTRAINT fk_pact_registry_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A projection of PACT facts: objective counts only (spec §4.11).
CREATE TABLE lc_pact_history (
	char_id INT NOT NULL,
	completed INT NOT NULL DEFAULT 0,
	cancelled INT NOT NULL DEFAULT 0,
	defaults INT NOT NULL DEFAULT 0,
	craft_attempts INT NOT NULL DEFAULT 0,
	craft_success INT NOT NULL DEFAULT 0,
	as_of_fact BIGINT NOT NULL,
	PRIMARY KEY (char_id),
	CONSTRAINT chk_pact_history CHECK (completed >= 0 AND cancelled >= 0 AND defaults >= 0 AND craft_success BETWEEN 0 AND craft_attempts)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Inserted only as a draft at version 0.
CREATE TRIGGER lc_pact_contract_insert BEFORE INSERT ON lc_pact_contract FOR EACH ROW IF NEW.state <> 'DRAFT' OR NEW.version <> 0 OR NEW.terms_version <> 1 OR NEW.review_from IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: a contract is created as a draft'; END IF;

-- Spec §3.1: id, type and creator fixed; the row version advances by one; the state machine, with review returning only to
-- the state it left or a terminal one; terminal is final; leaving the signing states for SIGNED only with every party's
-- signature on the current terms version; terms change only in the signing states, each change advancing the terms
-- version by one and returning the contract to DRAFT or PROPOSED.
CREATE TRIGGER lc_pact_contract_transition BEFORE UPDATE ON lc_pact_contract FOR EACH ROW BEGIN DECLARE terms_changed BOOLEAN DEFAULT NOT (NEW.terms <=> OLD.terms AND NEW.terms_hash <=> OLD.terms_hash AND NEW.terms_version <=> OLD.terms_version AND NEW.visibility <=> OLD.visibility AND NEW.publishable <=> OLD.publishable AND NEW.deadline <=> OLD.deadline AND NEW.value_adena <=> OLD.value_adena AND NEW.fee_adena <=> OLD.fee_adena AND NEW.fee_payer <=> OLD.fee_payer AND NEW.notarius_tier <=> OLD.notarius_tier AND NEW.notarius_town <=> OLD.notarius_town AND NEW.territory <=> OLD.territory); IF NEW.contract_id <> OLD.contract_id OR NEW.type <> OLD.type OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: id, type and creator never change'; END IF; IF NEW.version <> OLD.version + 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: every change advances the version by one'; END IF; IF OLD.state IN ('COMPLETED', 'EXPIRED', 'MUTUALLY_CANCELLED', 'FAILED_BY_CONDITION', 'WITHDRAWN') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: a terminal contract never changes'; END IF; IF NEW.state <> OLD.state AND NOT ((OLD.state = 'DRAFT' AND NEW.state = 'PROPOSED') OR (OLD.state = 'PROPOSED' AND NEW.state IN ('PARTIALLY_SIGNED', 'SIGNED', 'WITHDRAWN')) OR (OLD.state = 'PARTIALLY_SIGNED' AND NEW.state IN ('PROPOSED', 'SIGNED', 'WITHDRAWN')) OR (OLD.state = 'SIGNED' AND NEW.state IN ('FUNDED', 'EXPIRED', 'MUTUALLY_CANCELLED')) OR (OLD.state = 'FUNDED' AND NEW.state IN ('ACTIVE', 'MUTUALLY_CANCELLED')) OR (OLD.state = 'ACTIVE' AND NEW.state IN ('COMPLETED', 'EXPIRED', 'FAILED_BY_CONDITION', 'MUTUALLY_CANCELLED')) OR (NEW.state = 'ADMIN_REVIEW' AND NEW.review_from <=> OLD.state) OR (OLD.state = 'ADMIN_REVIEW' AND (NEW.state = OLD.review_from OR NEW.state IN ('COMPLETED', 'EXPIRED', 'MUTUALLY_CANCELLED', 'FAILED_BY_CONDITION', 'WITHDRAWN')))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: a transition the state machine does not have'; END IF; IF NEW.state = OLD.state AND NOT (NEW.review_from <=> OLD.review_from) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: review_from changes only on entering or leaving review'; END IF; IF NEW.state = 'SIGNED' AND OLD.state IN ('PROPOSED', 'PARTIALLY_SIGNED') AND EXISTS (SELECT 1 FROM lc_pact_party WHERE contract_id = OLD.contract_id AND NOT (signed_terms_version <=> OLD.terms_version)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: signed only when every party signed the current terms'; END IF; IF terms_changed AND NOT (OLD.state IN ('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED') AND NEW.state IN ('DRAFT', 'PROPOSED') AND NEW.terms_version = OLD.terms_version + 1) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: terms change only before signing, with the next terms version'; END IF; END;

CREATE TRIGGER lc_pact_contract_kept BEFORE DELETE ON lc_pact_contract FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: contracts are never deleted';

-- PACT's publication types (spec §4.10; intel-service.md §4.7).
INSERT INTO lc_pub_type (pub_type, min_delay_minutes, location_related) VALUES ('pact_registered', 0, FALSE), ('pact_completed', 0, FALSE);

INSERT INTO lc_pub_field_rule (pub_type, field, rule) VALUES ('pact_registered', 'contract_type', 'TEXT_FROM_FACT'), ('pact_registered', 'parties', 'PUBLIC_ENTITY'), ('pact_registered', 'day', 'DAY'), ('pact_completed', 'contract_type', 'TEXT_FROM_FACT'), ('pact_completed', 'parties', 'PUBLIC_ENTITY'), ('pact_completed', 'outcome', 'TEXT_FROM_FACT'), ('pact_completed', 'day', 'DAY'), ('pact_completed', 'clause_kinds', 'TEXT_FROM_FACT'), ('pact_completed', 'totals', 'COUNT');
