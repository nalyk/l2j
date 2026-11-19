-- SPEC-BL (docs/spec/black-ledger.md §2): contracts, funding, custodies, hunters, aliases, acceptances, kills, holds,
-- seals, reverse cases, informant offers, and the expiring counters of inquiries and client-mark pairs.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it. Each trigger is one line.

CREATE TABLE lc_ledger_contract (
	contract_id BIGINT NOT NULL AUTO_INCREMENT,
	kind ENUM('WRIT', 'BLACK') NOT NULL,
	cause ENUM('KARMA', 'CURSED_WEAPON', 'CLAN_WAR', 'ADMIN', 'OPEN') NOT NULL,
	state ENUM('OPEN', 'CLAIMABLE', 'SETTLING', 'HELD', 'SETTLED', 'RETURNED', 'EXPIRED', 'CANCELLED', 'LAPSED') NOT NULL,
	version INT NOT NULL DEFAULT 0,
	mark_char INT NOT NULL,
	client_char INT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	deadline TIMESTAMP(3) NOT NULL,
	principal BIGINT NOT NULL,
	intel_budget BIGINT NOT NULL DEFAULT 0,
	broker_fee BIGINT NOT NULL DEFAULT 0,
	conditions JSON NOT NULL,
	terms_hash BINARY(32) NOT NULL,
	origin_npc VARCHAR(32) NOT NULL,
	origin_town SMALLINT NOT NULL,
	sealed_identity BOOLEAN NOT NULL DEFAULT FALSE,
	min_rank TINYINT NOT NULL DEFAULT 0,
	max_hunters TINYINT NOT NULL DEFAULT 1,
	kills_required TINYINT NOT NULL DEFAULT 1,
	kills_done TINYINT NOT NULL DEFAULT 0,
	PRIMARY KEY (contract_id),
	KEY idx_ledger_mark (mark_char, state),
	KEY idx_ledger_client (client_char),
	CONSTRAINT chk_ledger_kind CHECK ((kind = 'BLACK' AND cause = 'OPEN' AND client_char IS NOT NULL) OR (kind = 'WRIT' AND cause <> 'OPEN' AND client_char IS NULL AND intel_budget = 0 AND NOT sealed_identity)),
	CONSTRAINT chk_ledger_amounts CHECK (principal >= 0 AND intel_budget >= 0 AND broker_fee >= 0),
	CONSTRAINT chk_ledger_kills CHECK (kills_required BETWEEN 1 AND 5 AND kills_done BETWEEN 0 AND kills_required),
	CONSTRAINT chk_ledger_hunters CHECK (max_hunters >= 1 AND min_rank BETWEEN 0 AND 4),
	CONSTRAINT chk_ledger_conditions CHECK (JSON_VALID(conditions)),
	CONSTRAINT chk_ledger_deadline CHECK (deadline > created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_ledger_funding (
	contract_id BIGINT NOT NULL,
	funding_no INT NOT NULL,
	source ENUM('PLAYER', 'CROWN_LAW', 'GUILD', 'HOUSE') NOT NULL,
	source_id BIGINT NOT NULL,
	amount BIGINT NOT NULL,
	op_id BINARY(16) NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (contract_id, funding_no),
	CONSTRAINT fk_ledger_funding_contract FOREIGN KEY (contract_id) REFERENCES lc_ledger_contract (contract_id),
	CONSTRAINT chk_ledger_funding_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_ledger_custody (
	contract_id BIGINT NOT NULL,
	purpose ENUM('PRINCIPAL', 'INTEL') NOT NULL,
	custody_id INT NOT NULL,
	PRIMARY KEY (contract_id, purpose),
	UNIQUE KEY uk_ledger_custody (custody_id),
	CONSTRAINT fk_ledger_custody_contract FOREIGN KEY (contract_id) REFERENCES lc_ledger_contract (contract_id),
	CONSTRAINT fk_ledger_custody_custody FOREIGN KEY (custody_id) REFERENCES lc_custody (custody_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_ledger_hunter (
	char_id INT NOT NULL,
	rank ENUM('LISTENER', 'TRACKER', 'STALKER', 'HEAD_HUNTER', 'GHOST') NOT NULL DEFAULT 'LISTENER',
	registered_at TIMESTAMP(3) NOT NULL,
	completed INT NOT NULL DEFAULT 0,
	failed INT NOT NULL DEFAULT 0,
	abandoned INT NOT NULL DEFAULT 0,
	sightings_verified INT NOT NULL DEFAULT 0,
	PRIMARY KEY (char_id),
	CONSTRAINT chk_ledger_hunter_counts CHECK (completed >= 0 AND failed >= 0 AND abandoned >= 0 AND sightings_verified >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A rank never falls; counters never shrink.
CREATE TRIGGER lc_ledger_hunter_rank BEFORE UPDATE ON lc_ledger_hunter FOR EACH ROW IF NEW.rank + 0 < OLD.rank + 0 OR NEW.completed < OLD.completed OR NEW.failed < OLD.failed OR NEW.abandoned < OLD.abandoned OR NEW.sightings_verified < OLD.sightings_verified OR NEW.char_id <> OLD.char_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_hunter: ranks and counters never fall'; END IF;

-- One alias per character, unique by name; its owner never changes; exposure is set once.
CREATE TABLE lc_ledger_alias (
	alias_id INT NOT NULL AUTO_INCREMENT,
	name VARCHAR(24) NOT NULL,
	owner_char INT NOT NULL,
	card ENUM('FEATHER', 'COIN', 'CARD', 'SYMBOL') NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	exposed_at TIMESTAMP(3) NULL,
	PRIMARY KEY (alias_id),
	UNIQUE KEY uk_ledger_alias_name (name),
	UNIQUE KEY uk_ledger_alias_owner (owner_char),
	CONSTRAINT fk_ledger_alias_hunter FOREIGN KEY (owner_char) REFERENCES lc_ledger_hunter (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_alias_fixed BEFORE UPDATE ON lc_ledger_alias FOR EACH ROW IF NEW.alias_id <> OLD.alias_id OR NEW.name <> OLD.name OR NEW.owner_char <> OLD.owner_char OR NEW.created_at <> OLD.created_at OR (OLD.exposed_at IS NOT NULL AND NOT (NEW.exposed_at <=> OLD.exposed_at)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_alias: an alias keeps its name and owner; exposure is set once'; END IF;

CREATE TRIGGER lc_ledger_alias_kept BEFORE DELETE ON lc_ledger_alias FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_alias: aliases are never deleted';

CREATE TABLE lc_ledger_acceptance (
	contract_id BIGINT NOT NULL,
	hunter_char INT NOT NULL,
	accepted_at TIMESTAMP(3) NOT NULL,
	card_mode ENUM('AT_KILL', 'DELAYED', 'NONE') NOT NULL,
	state ENUM('ACTIVE', 'WON', 'FAILED', 'ABANDONED', 'EXPIRED', 'VOID') NOT NULL DEFAULT 'ACTIVE',
	PRIMARY KEY (contract_id, hunter_char),
	KEY idx_ledger_acceptance_hunter (hunter_char, state),
	CONSTRAINT fk_ledger_acceptance_contract FOREIGN KEY (contract_id) REFERENCES lc_ledger_contract (contract_id),
	CONSTRAINT fk_ledger_acceptance_hunter FOREIGN KEY (hunter_char) REFERENCES lc_ledger_hunter (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_acceptance_insert BEFORE INSERT ON lc_ledger_acceptance FOR EACH ROW IF NEW.state <> 'ACTIVE' OR (SELECT state FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) <> 'OPEN' OR NEW.hunter_char = (SELECT mark_char FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) OR NEW.hunter_char <=> (SELECT client_char FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_acceptance: an open contract, accepted by neither its mark nor its client'; END IF;

CREATE TRIGGER lc_ledger_acceptance_end BEFORE UPDATE ON lc_ledger_acceptance FOR EACH ROW IF OLD.state <> 'ACTIVE' OR NEW.state = 'ACTIVE' OR NEW.contract_id <> OLD.contract_id OR NEW.hunter_char <> OLD.hunter_char OR NEW.card_mode <> OLD.card_mode OR NEW.accepted_at <> OLD.accepted_at THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_acceptance: an acceptance ends once'; END IF;

-- A kill is recorded once per key (a death event may be dispatched twice).
CREATE TABLE lc_ledger_kill (
	kill_key VARCHAR(64) NOT NULL,
	contract_id BIGINT NOT NULL,
	hunter_char INT NOT NULL,
	mark_char INT NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	region_id SMALLINT NOT NULL,
	territory SMALLINT NULL,
	context JSON NOT NULL,
	outcome ENUM('QUALIFIED', 'EXCLUDED') NOT NULL,
	reason VARCHAR(48) NULL,
	claim_deadline TIMESTAMP(3) NULL,
	claimed_op BINARY(16) NULL,
	PRIMARY KEY (kill_key, contract_id),
	KEY idx_ledger_kill_contract (contract_id),
	CONSTRAINT fk_ledger_kill_contract FOREIGN KEY (contract_id) REFERENCES lc_ledger_contract (contract_id),
	CONSTRAINT chk_ledger_kill_reason CHECK ((outcome = 'EXCLUDED') = (reason IS NOT NULL)),
	CONSTRAINT chk_ledger_kill_context CHECK (JSON_VALID(context))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_kill_fixed BEFORE UPDATE ON lc_ledger_kill FOR EACH ROW IF NEW.kill_key <> OLD.kill_key OR NEW.contract_id <> OLD.contract_id OR NEW.hunter_char <> OLD.hunter_char OR NEW.outcome <> OLD.outcome OR NEW.at <> OLD.at OR (OLD.claimed_op IS NOT NULL) OR (NEW.claimed_op IS NULL) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_kill: a kill is only ever claimed, once'; END IF;

CREATE TABLE lc_ledger_hold (
	contract_id BIGINT NOT NULL,
	reason VARCHAR(48) NOT NULL,
	evidence JSON NOT NULL,
	opened_at TIMESTAMP(3) NOT NULL,
	review_until TIMESTAMP(3) NOT NULL,
	state ENUM('HELD', 'RELEASED', 'RETURNED') NOT NULL DEFAULT 'HELD',
	resolved_by VARCHAR(32) NULL,
	resolved_at TIMESTAMP(3) NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (contract_id),
	CONSTRAINT fk_ledger_hold_contract FOREIGN KEY (contract_id) REFERENCES lc_ledger_contract (contract_id),
	CONSTRAINT chk_ledger_hold_resolved CHECK ((state = 'HELD') = (resolved_at IS NULL) AND (state = 'RETURNED') <= (resolved_by IS NOT NULL)),
	CONSTRAINT chk_ledger_hold_evidence CHECK (JSON_VALID(evidence))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_hold_resolve BEFORE UPDATE ON lc_ledger_hold FOR EACH ROW IF OLD.state <> 'HELD' OR NEW.contract_id <> OLD.contract_id OR NEW.reason <> OLD.reason OR NEW.opened_at <> OLD.opened_at THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_hold: a hold is resolved once'; END IF;

-- One seal per (contract, hunter), keyed by its item's Asset lifecycle id (item variables do not survive a drop).
CREATE TABLE lc_ledger_seal (
	lifecycle_id BIGINT NOT NULL,
	contract_id BIGINT NOT NULL,
	hunter_char INT NOT NULL,
	mark_char INT NOT NULL,
	value_band TINYINT NOT NULL,
	origin_town SMALLINT NOT NULL,
	issued_op BINARY(16) NOT NULL,
	PRIMARY KEY (lifecycle_id),
	UNIQUE KEY uk_ledger_seal (contract_id, hunter_char),
	CONSTRAINT fk_ledger_seal_lifecycle FOREIGN KEY (lifecycle_id) REFERENCES lc_item_lifecycle (lifecycle_id),
	CONSTRAINT fk_ledger_seal_acceptance FOREIGN KEY (contract_id, hunter_char) REFERENCES lc_ledger_acceptance (contract_id, hunter_char)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_seal_insert BEFORE INSERT ON lc_ledger_seal FOR EACH ROW IF (SELECT kind FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) <> 'BLACK' OR (SELECT state FROM lc_ledger_acceptance WHERE contract_id = NEW.contract_id AND hunter_char = NEW.hunter_char) <> 'FAILED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_seal: a seal only for a failed hunter of a Black Contract'; END IF;

CREATE TRIGGER lc_ledger_seal_fixed BEFORE UPDATE ON lc_ledger_seal FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_seal: a seal never changes';

-- A reverse case advances one step at a time, to step 3 at most, before it expires; its holder never changes.
CREATE TABLE lc_ledger_reverse_case (
	case_id BIGINT NOT NULL AUTO_INCREMENT,
	lifecycle_id BIGINT NOT NULL,
	holder_char INT NOT NULL,
	step TINYINT NOT NULL DEFAULT 0,
	opened_at TIMESTAMP(3) NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (case_id),
	UNIQUE KEY uk_ledger_reverse_seal (lifecycle_id),
	KEY idx_ledger_reverse_expiry (expires_at),
	CONSTRAINT fk_ledger_reverse_seal FOREIGN KEY (lifecycle_id) REFERENCES lc_ledger_seal (lifecycle_id),
	CONSTRAINT chk_ledger_reverse_step CHECK (step BETWEEN 0 AND 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_reverse_step BEFORE UPDATE ON lc_ledger_reverse_case FOR EACH ROW IF NEW.step <> OLD.step + 1 OR NEW.holder_char <> OLD.holder_char OR NEW.lifecycle_id <> OLD.lifecycle_id OR NOT (NEW.expires_at <=> OLD.expires_at) OR OLD.expires_at <= NOW(3) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_reverse_case: one step at a time, before it expires'; END IF;

CREATE TABLE lc_ledger_offer (
	offer_id BIGINT NOT NULL AUTO_INCREMENT,
	informant_char INT NOT NULL,
	mark_char INT NOT NULL,
	trace_id BIGINT NOT NULL,
	price BIGINT NOT NULL,
	state ENUM('OFFERED', 'SOLD', 'HELD', 'PAID', 'EXPIRED') NOT NULL DEFAULT 'OFFERED',
	buyer_contract BIGINT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (offer_id),
	KEY idx_ledger_offer_mark (mark_char, state),
	KEY idx_ledger_offer_expiry (expires_at),
	CONSTRAINT chk_ledger_offer_price CHECK (price > 0),
	CONSTRAINT chk_ledger_offer_party CHECK (informant_char <> mark_char)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_ledger_inquiry (
	mark_char INT NOT NULL,
	window_start TIMESTAMP NOT NULL,
	inquiries INT NOT NULL DEFAULT 0,
	warned BOOLEAN NOT NULL DEFAULT FALSE,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (mark_char, window_start),
	KEY idx_ledger_inquiry_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_ledger_pair (
	client_char INT NOT NULL,
	mark_char INT NOT NULL,
	window_start TIMESTAMP NOT NULL,
	contracts INT NOT NULL DEFAULT 0,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (client_char, mark_char, window_start),
	KEY idx_ledger_pair_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_ledger_mark_cooldown (
	mark_char INT NOT NULL,
	until TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (mark_char)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO lc_expiring_table (table_name, expires_column, owner_module, max_life_hours) VALUES ('lc_ledger_offer', 'expires_at', 'LEDGER', 24), ('lc_ledger_inquiry', 'expires_at', 'LEDGER', 24), ('lc_ledger_pair', 'expires_at', 'LEDGER', 2160), ('lc_ledger_hold', 'expires_at', 'LEDGER', 2160), ('lc_ledger_reverse_case', 'expires_at', 'LEDGER', 168);

-- Inserted open at version 0 with no kills.
CREATE TRIGGER lc_ledger_contract_insert BEFORE INSERT ON lc_ledger_contract FOR EACH ROW IF NEW.state <> 'OPEN' OR NEW.version <> 0 OR NEW.kills_done <> 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: a contract is created open'; END IF;

-- Spec §3.1: fixed identity and terms (a Writ's principal only grows, by contributions); the version advances by one; kills
-- grow by at most one; the state machine, with Black-only and Writ-only transitions; cancel only before any acceptance;
-- terminal is final.
CREATE TRIGGER lc_ledger_contract_transition BEFORE UPDATE ON lc_ledger_contract FOR EACH ROW BEGIN IF NEW.contract_id <> OLD.contract_id OR NEW.kind <> OLD.kind OR NEW.cause <> OLD.cause OR NEW.mark_char <> OLD.mark_char OR NOT (NEW.client_char <=> OLD.client_char) OR NEW.created_at <> OLD.created_at OR NEW.deadline <> OLD.deadline OR NEW.intel_budget <> OLD.intel_budget OR NEW.broker_fee <> OLD.broker_fee OR NOT (NEW.conditions <=> OLD.conditions) OR NEW.terms_hash <> OLD.terms_hash OR NEW.origin_npc <> OLD.origin_npc OR NEW.origin_town <> OLD.origin_town OR NEW.sealed_identity <> OLD.sealed_identity OR NEW.min_rank <> OLD.min_rank OR NEW.max_hunters <> OLD.max_hunters OR NEW.kills_required <> OLD.kills_required THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: identity and terms never change'; END IF; IF NEW.principal <> OLD.principal AND NOT (OLD.kind = 'WRIT' AND NEW.principal > OLD.principal AND OLD.state = 'OPEN' AND NEW.state = 'OPEN') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: only an open Writ grows, by contributions'; END IF; IF NEW.version <> OLD.version + 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: every change advances the version by one'; END IF; IF NEW.kills_done < OLD.kills_done OR NEW.kills_done > OLD.kills_done + 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: kills grow one at a time'; END IF; IF OLD.state IN ('SETTLED', 'RETURNED', 'EXPIRED', 'CANCELLED', 'LAPSED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: a terminal contract never changes'; END IF; IF NEW.state <> OLD.state AND NOT ((OLD.state = 'OPEN' AND (NEW.state IN ('EXPIRED') OR (NEW.state = 'CANCELLED' AND OLD.kind = 'BLACK') OR (NEW.state = 'CLAIMABLE' AND OLD.kind = 'BLACK') OR (NEW.state IN ('SETTLING', 'LAPSED') AND OLD.kind = 'WRIT'))) OR (OLD.state = 'CLAIMABLE' AND NEW.state IN ('SETTLING', 'OPEN', 'EXPIRED')) OR (OLD.state = 'SETTLING' AND NEW.state IN ('SETTLED', 'HELD', 'OPEN', 'EXPIRED')) OR (OLD.state = 'HELD' AND NEW.state IN ('SETTLED', 'RETURNED'))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: a transition the state machine does not have'; END IF; IF NEW.state = 'CANCELLED' AND EXISTS (SELECT 1 FROM lc_ledger_acceptance WHERE contract_id = OLD.contract_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: cancelled only before any acceptance'; END IF; END;

CREATE TRIGGER lc_ledger_contract_kept BEFORE DELETE ON lc_ledger_contract FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: contracts are never deleted';
