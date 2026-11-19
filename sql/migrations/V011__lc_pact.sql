-- SPEC-PACT (docs/spec/pact.md §2): contracts, parties, clauses, their custodies, settlement units, craft outcomes,
-- charter members, the public registry and the objective history.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it. Each trigger is one line.

CREATE TABLE lc_pact_contract (
	contract_id BIGINT NOT NULL AUTO_INCREMENT,
	type ENUM('CRAFTING_PACT', 'CRAFT_ORDER', 'PURCHASE_ORDER', 'DELIVERY_ORDER', 'LOAN', 'LOOT_CHARTER', 'MERCENARY', 'CARAVAN_ESCORT', 'TREATY') NOT NULL,
	state ENUM('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED', 'SIGNED', 'FUNDED', 'ACTIVE', 'COMPLETED', 'EXPIRED', 'MUTUALLY_CANCELLED', 'FAILED_BY_CONDITION', 'WITHDRAWN', 'ADMIN_REVIEW') NOT NULL,
	version INT NOT NULL DEFAULT 0,
	visibility ENUM('PRIVATE', 'PARTIES', 'PUBLIC') NOT NULL,
	publishable BOOLEAN NOT NULL DEFAULT FALSE,
	notarius_tier ENUM('TOWN', 'ROYAL', 'CASTLE', 'CARAVAN') NOT NULL,
	notarius_town SMALLINT NOT NULL,
	created_by INT NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	deadline TIMESTAMP(3) NOT NULL,
	terms_hash BINARY(32) NOT NULL,
	terms JSON NOT NULL,
	value_adena BIGINT NOT NULL DEFAULT 0,
	review_reason VARCHAR(128) NULL,
	PRIMARY KEY (contract_id),
	KEY idx_pact_state (state, deadline),
	CONSTRAINT chk_pact_terms CHECK (JSON_VALID(terms)),
	CONSTRAINT chk_pact_value CHECK (value_adena >= 0),
	CONSTRAINT chk_pact_review CHECK ((state = 'ADMIN_REVIEW') <= (review_reason IS NOT NULL)),
	CONSTRAINT chk_pact_publishable CHECK (NOT publishable OR visibility <> 'PRIVATE')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_pact_party (
	contract_id BIGINT NOT NULL,
	party_no TINYINT NOT NULL,
	role ENUM('CLIENT', 'CONTRACTOR', 'LENDER', 'BORROWER', 'BUYER', 'SELLER', 'DELIVERER', 'EMPLOYER', 'MERCENARY', 'SIGNATORY', 'LEADER', 'MEMBER') NOT NULL,
	char_id INT NULL,
	clan_id INT NULL,
	signed_version INT NULL,
	signed_at TIMESTAMP(3) NULL,
	signed_op BINARY(16) NULL,
	PRIMARY KEY (contract_id, party_no),
	KEY idx_pact_party_char (char_id),
	KEY idx_pact_party_clan (clan_id),
	CONSTRAINT fk_pact_party_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id),
	CONSTRAINT chk_pact_party_who CHECK ((char_id IS NOT NULL) OR (clan_id IS NOT NULL)),
	CONSTRAINT chk_pact_party_signed CHECK ((signed_version IS NULL) = (signed_at IS NULL))
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

-- A settlement unit settles once: its key in the Asset Service's single settlement-key table, its row here.
CREATE TABLE lc_pact_settlement (
	contract_id BIGINT NOT NULL,
	clause_no SMALLINT NOT NULL,
	unit INT NOT NULL,
	scope VARCHAR(16) NOT NULL DEFAULT 'PACT',
	skey VARCHAR(96) NOT NULL,
	outcome ENUM('PAID', 'RETURNED', 'FORFEITED', 'NONE') NOT NULL,
	op_id BINARY(16) NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (contract_id, clause_no, unit),
	UNIQUE KEY uk_pact_settlement_key (scope, skey),
	CONSTRAINT fk_pact_settlement_clause FOREIGN KEY (contract_id, clause_no) REFERENCES lc_pact_clause (contract_id, clause_no),
	CONSTRAINT fk_pact_settlement_key FOREIGN KEY (scope, skey) REFERENCES lc_settlement_key (scope, skey),
	CONSTRAINT chk_pact_settlement_scope CHECK (scope = 'PACT')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- One outcome per attempt, written with the materials' consumption, never changed or deleted (no reroll).
CREATE TABLE lc_pact_craft_outcome (
	contract_id BIGINT NOT NULL,
	attempt_no INT NOT NULL,
	recipe_id INT NOT NULL,
	crafter_char INT NOT NULL,
	success BOOLEAN NOT NULL,
	masterwork BOOLEAN NOT NULL,
	product_item_id INT NULL,
	product_count INT NOT NULL,
	op_id BINARY(16) NOT NULL,
	rolled_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (contract_id, attempt_no),
	CONSTRAINT fk_pact_craft_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id),
	CONSTRAINT chk_pact_craft_attempt CHECK (attempt_no > 0),
	CONSTRAINT chk_pact_craft_product CHECK ((success AND product_item_id IS NOT NULL AND product_count > 0) OR (NOT success AND NOT masterwork AND product_item_id IS NULL AND product_count = 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_pact_craft_outcome_frozen BEFORE UPDATE ON lc_pact_craft_outcome FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_craft_outcome: an outcome is never changed';

CREATE TRIGGER lc_pact_craft_outcome_kept BEFORE DELETE ON lc_pact_craft_outcome FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_craft_outcome: an outcome is never deleted';

CREATE TABLE lc_pact_charter_member (
	contract_id BIGINT NOT NULL,
	char_id INT NOT NULL,
	joined_version INT NOT NULL,
	state ENUM('FROZEN_IN', 'LEFT', 'DISCONNECTED', 'DEAD_AT_KILL') NOT NULL,
	PRIMARY KEY (contract_id, char_id),
	CONSTRAINT fk_pact_member_contract FOREIGN KEY (contract_id) REFERENCES lc_pact_contract (contract_id)
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

-- A contract keeps its id and type; every update advances the version by one; the state moves only along spec §3.1; it
-- leaves the signing states for SIGNED only when every party signed the version being left; terminal states are final.
CREATE TRIGGER lc_pact_contract_transition BEFORE UPDATE ON lc_pact_contract FOR EACH ROW BEGIN IF NEW.contract_id <> OLD.contract_id OR NEW.type <> OLD.type OR NEW.created_by <> OLD.created_by THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: id, type and creator never change'; END IF; IF NEW.version <> OLD.version + 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: every change advances the version by one'; END IF; IF NEW.state <> OLD.state AND NOT ((OLD.state = 'DRAFT' AND NEW.state IN ('PROPOSED', 'ADMIN_REVIEW')) OR (OLD.state = 'PROPOSED' AND NEW.state IN ('PARTIALLY_SIGNED', 'SIGNED', 'WITHDRAWN', 'ADMIN_REVIEW')) OR (OLD.state = 'PARTIALLY_SIGNED' AND NEW.state IN ('PROPOSED', 'SIGNED', 'WITHDRAWN', 'ADMIN_REVIEW')) OR (OLD.state = 'SIGNED' AND NEW.state IN ('FUNDED', 'EXPIRED', 'MUTUALLY_CANCELLED', 'ADMIN_REVIEW')) OR (OLD.state = 'FUNDED' AND NEW.state IN ('ACTIVE', 'MUTUALLY_CANCELLED', 'ADMIN_REVIEW')) OR (OLD.state = 'ACTIVE' AND NEW.state IN ('COMPLETED', 'EXPIRED', 'FAILED_BY_CONDITION', 'MUTUALLY_CANCELLED', 'ADMIN_REVIEW')) OR (OLD.state = 'ADMIN_REVIEW' AND NEW.state NOT IN ('DRAFT'))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: a transition the state machine does not have'; END IF; IF OLD.state IN ('COMPLETED', 'EXPIRED', 'MUTUALLY_CANCELLED', 'FAILED_BY_CONDITION', 'WITHDRAWN') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: a terminal contract never changes'; END IF; IF NEW.state = 'SIGNED' AND OLD.state IN ('PROPOSED', 'PARTIALLY_SIGNED') AND EXISTS (SELECT 1 FROM lc_pact_party WHERE contract_id = OLD.contract_id AND NOT (signed_version <=> OLD.version)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: signed only when every party signed the current version'; END IF; IF NEW.state = OLD.state AND OLD.state NOT IN ('DRAFT', 'PROPOSED', 'PARTIALLY_SIGNED', 'ADMIN_REVIEW') AND (NEW.terms_hash <> OLD.terms_hash) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: terms change only before signing'; END IF; END;

CREATE TRIGGER lc_pact_contract_kept BEFORE DELETE ON lc_pact_contract FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_pact_contract: contracts are never deleted';
