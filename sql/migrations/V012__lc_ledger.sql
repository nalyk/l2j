-- SPEC-BL (docs/spec/black-ledger.md §2): contracts and their bases, patrons and funders (expiring), custodies, hunters,
-- aliases, acceptances, death evidence, decisions, claims, payments, seals, reverse cases, sighting offers, and the
-- expiring counters of inquiries, pairs and endowment use.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it. Each trigger is one line.
-- UNTIL is a reserved word in MariaDB, hence cooldown_until.

CREATE TABLE lc_ledger_contract (
	contract_id BIGINT NOT NULL AUTO_INCREMENT,
	kind ENUM('WRIT', 'BLACK') NOT NULL,
	cause ENUM('KARMA', 'CURSED_WEAPON', 'CLAN_WAR', 'OPEN') NOT NULL,
	state ENUM('OPEN', 'CLAIMABLE', 'SETTLING', 'REVIEW', 'SETTLED', 'EXPIRED', 'CANCELLED', 'LAPSED') NOT NULL,
	version INT NOT NULL DEFAULT 0,
	mark_char INT NOT NULL,
	issuer_clan INT NULL,
	target_clan INT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	deadline TIMESTAMP(3) NOT NULL,
	player_principal BIGINT NOT NULL DEFAULT 0,
	external_principal BIGINT NOT NULL DEFAULT 0,
	principal BIGINT AS (player_principal + external_principal) PERSISTENT,
	intel_budget BIGINT NOT NULL DEFAULT 0,
	max_sighting_price BIGINT NOT NULL DEFAULT 0,
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
	settling_death VARCHAR(64) NULL,
	PRIMARY KEY (contract_id),
	KEY idx_ledger_mark (mark_char, state),
	CONSTRAINT chk_ledger_kind CHECK ((kind = 'BLACK' AND cause = 'OPEN' AND issuer_clan IS NULL AND target_clan IS NULL) OR (kind = 'WRIT' AND cause <> 'OPEN' AND intel_budget = 0 AND max_sighting_price = 0 AND NOT sealed_identity AND (cause = 'CLAN_WAR') = (issuer_clan IS NOT NULL AND target_clan IS NOT NULL))),
	CONSTRAINT chk_ledger_amounts CHECK (player_principal >= 0 AND external_principal >= 0 AND intel_budget >= 0 AND broker_fee >= 0 AND max_sighting_price BETWEEN 0 AND intel_budget),
	CONSTRAINT chk_ledger_external CHECK (external_principal = 0 OR cause = 'CURSED_WEAPON'),
	CONSTRAINT chk_ledger_kills CHECK (kills_required BETWEEN 1 AND 5 AND kills_done BETWEEN 0 AND kills_required),
	CONSTRAINT chk_ledger_hunters CHECK (max_hunters >= 1 AND min_rank BETWEEN 0 AND 4),
	CONSTRAINT chk_ledger_conditions CHECK (JSON_VALID(conditions)),
	CONSTRAINT chk_ledger_deadline CHECK (deadline > created_at),
	CONSTRAINT chk_ledger_settling CHECK ((state IN ('CLAIMABLE', 'SETTLING', 'SETTLED')) = (settling_death IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The depositor of a Black Contract: deleted when its use ends (spec §2.2).
CREATE TABLE lc_ledger_patron (
	contract_id BIGINT NOT NULL,
	client_char INT NOT NULL,
	expires_at TIMESTAMP(3) NULL,
	PRIMARY KEY (contract_id),
	KEY idx_ledger_patron_expiry (expires_at),
	CONSTRAINT fk_ledger_patron_contract FOREIGN KEY (contract_id) REFERENCES lc_ledger_contract (contract_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_patron_black BEFORE INSERT ON lc_ledger_patron FOR EACH ROW IF (SELECT kind FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) <> 'BLACK' OR NEW.client_char = (SELECT mark_char FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_patron: a Black Contract, not on its own client'; END IF;

CREATE TABLE lc_ledger_funding (
	contract_id BIGINT NOT NULL,
	funding_no INT NOT NULL,
	source ENUM('PLAYER', 'CROWN_LAW', 'GUILD', 'HOUSE') NOT NULL,
	amount BIGINT NOT NULL,
	op_id BINARY(16) NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (contract_id, funding_no),
	CONSTRAINT fk_ledger_funding_contract FOREIGN KEY (contract_id) REFERENCES lc_ledger_contract (contract_id),
	CONSTRAINT chk_ledger_funding_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Who contributed: deleted when the Writ is terminal and its returns are made (spec §2.2).
-- External money (Crown Law, guild pools) funds only cursed-weapon Writs, which cannot be manufactured (spec §4.3).
CREATE TRIGGER lc_ledger_funding_external BEFORE INSERT ON lc_ledger_funding FOR EACH ROW IF NEW.source IN ('CROWN_LAW', 'GUILD') AND (SELECT cause FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) <> 'CURSED_WEAPON' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_funding: external money only for cursed-weapon Writs'; END IF;

CREATE TABLE lc_ledger_funder (
	contract_id BIGINT NOT NULL,
	funding_no INT NOT NULL,
	source_id BIGINT NOT NULL,
	expires_at TIMESTAMP(3) NULL,
	PRIMARY KEY (contract_id, funding_no),
	KEY idx_ledger_funder_expiry (expires_at),
	CONSTRAINT fk_ledger_funder_funding FOREIGN KEY (contract_id, funding_no) REFERENCES lc_ledger_funding (contract_id, funding_no) ON DELETE CASCADE
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

CREATE TRIGGER lc_ledger_hunter_rank BEFORE UPDATE ON lc_ledger_hunter FOR EACH ROW IF NEW.rank + 0 < OLD.rank + 0 OR NEW.completed < OLD.completed OR NEW.failed < OLD.failed OR NEW.abandoned < OLD.abandoned OR NEW.sightings_verified < OLD.sightings_verified OR NEW.char_id <> OLD.char_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_hunter: ranks and counters never fall'; END IF;

-- One active alias per character, unique names (kept after retirement); the owner is only ever cleared, by retirement.
CREATE TABLE lc_ledger_alias (
	alias_id INT NOT NULL AUTO_INCREMENT,
	name VARCHAR(24) NOT NULL,
	owner_char INT NULL,
	card ENUM('FEATHER', 'COIN', 'CARD', 'SYMBOL') NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	exposed_at TIMESTAMP(3) NULL,
	retired_at TIMESTAMP(3) NULL,
	PRIMARY KEY (alias_id),
	UNIQUE KEY uk_ledger_alias_name (name),
	UNIQUE KEY uk_ledger_alias_owner (owner_char),
	CONSTRAINT chk_ledger_alias_retired CHECK ((retired_at IS NULL) = (owner_char IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_alias_fixed BEFORE UPDATE ON lc_ledger_alias FOR EACH ROW IF NEW.alias_id <> OLD.alias_id OR NEW.name <> OLD.name OR NEW.created_at <> OLD.created_at OR (NOT (NEW.owner_char <=> OLD.owner_char) AND NOT (OLD.owner_char IS NOT NULL AND NEW.owner_char IS NULL)) OR (OLD.exposed_at IS NOT NULL AND NOT (NEW.exposed_at <=> OLD.exposed_at)) OR (OLD.retired_at IS NOT NULL AND NOT (NEW.retired_at <=> OLD.retired_at)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_alias: a name is kept; an owner is only cleared by retirement; exposure and retirement are set once'; END IF;

CREATE TRIGGER lc_ledger_alias_kept BEFORE DELETE ON lc_ledger_alias FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_alias: aliases are never deleted (names stay reserved)';

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

CREATE TRIGGER lc_ledger_acceptance_insert BEFORE INSERT ON lc_ledger_acceptance FOR EACH ROW IF NEW.state <> 'ACTIVE' OR (SELECT state FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) <> 'OPEN' OR NEW.hunter_char = (SELECT mark_char FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) OR NEW.hunter_char <=> (SELECT client_char FROM lc_ledger_patron WHERE contract_id = NEW.contract_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_acceptance: an open contract, accepted by neither its mark nor its client'; END IF;

CREATE TRIGGER lc_ledger_acceptance_end BEFORE UPDATE ON lc_ledger_acceptance FOR EACH ROW IF OLD.state <> 'ACTIVE' OR NEW.state = 'ACTIVE' OR NEW.contract_id <> OLD.contract_id OR NEW.hunter_char <> OLD.hunter_char OR NEW.card_mode <> OLD.card_mode OR NEW.accepted_at <> OLD.accepted_at THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_acceptance: an acceptance ends once'; END IF;

-- Death evidence (spec §4.5): one row per real death, never changed.
CREATE TABLE lc_ledger_death (
	death_key VARCHAR(64) NOT NULL,
	fact_id BIGINT NOT NULL,
	victim_char INT NOT NULL,
	killer_char INT NULL,
	killer_kind ENUM('SELF', 'SUMMON', 'TRAP', 'DOT', 'OTHER') NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	region_id SMALLINT NOT NULL,
	territory SMALLINT NULL,
	context JSON NOT NULL,
	PRIMARY KEY (death_key),
	UNIQUE KEY uk_ledger_death_fact (fact_id),
	KEY idx_ledger_death_victim (victim_char, at),
	CONSTRAINT chk_ledger_death_context CHECK (JSON_VALID(context))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_death_frozen BEFORE UPDATE ON lc_ledger_death FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_death: evidence never changes';

CREATE TRIGGER lc_ledger_death_kept BEFORE DELETE ON lc_ledger_death FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_death: evidence is never deleted';

-- One decision per death and contract, never changed; anti-abuse is the payment's, not the decision's.
CREATE TABLE lc_ledger_decision (
	death_key VARCHAR(64) NOT NULL,
	contract_id BIGINT NOT NULL,
	outcome ENUM('QUALIFIED', 'EXCLUDED') NOT NULL,
	reason VARCHAR(48) NULL,
	decided_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (death_key, contract_id),
	KEY idx_ledger_decision_contract (contract_id, outcome),
	CONSTRAINT fk_ledger_decision_death FOREIGN KEY (death_key) REFERENCES lc_ledger_death (death_key),
	CONSTRAINT fk_ledger_decision_contract FOREIGN KEY (contract_id) REFERENCES lc_ledger_contract (contract_id),
	CONSTRAINT chk_ledger_decision_reason CHECK ((outcome = 'EXCLUDED') = (reason IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_decision_frozen BEFORE UPDATE ON lc_ledger_decision FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_decision: a decision never changes';

CREATE TRIGGER lc_ledger_decision_kept BEFORE DELETE ON lc_ledger_decision FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_decision: decisions are never deleted';

-- Counted kills (spec §3.1): one per (contract, death), only for a qualifying decision while the contract is open, never
-- deleted, only voided; a contract's kills_done always equals its unvoided count rows.
CREATE TABLE lc_ledger_count (
	contract_id BIGINT NOT NULL,
	death_key VARCHAR(64) NOT NULL,
	counted_at TIMESTAMP(3) NOT NULL,
	voided_at TIMESTAMP(3) NULL,
	PRIMARY KEY (contract_id, death_key),
	CONSTRAINT fk_ledger_count_decision FOREIGN KEY (death_key, contract_id) REFERENCES lc_ledger_decision (death_key, contract_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_count_insert BEFORE INSERT ON lc_ledger_count FOR EACH ROW IF (SELECT outcome FROM lc_ledger_decision WHERE death_key = NEW.death_key AND contract_id = NEW.contract_id) <> 'QUALIFIED' OR (SELECT state FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) NOT IN ('OPEN', 'REVIEW') OR NEW.voided_at IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_count: a qualifying death, counted while the contract is open'; END IF;

CREATE TRIGGER lc_ledger_count_void BEFORE UPDATE ON lc_ledger_count FOR EACH ROW IF OLD.voided_at IS NOT NULL OR NEW.voided_at IS NULL OR NEW.contract_id <> OLD.contract_id OR NEW.death_key <> OLD.death_key OR NEW.counted_at <> OLD.counted_at THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_count: a count is only ever voided, once'; END IF;

CREATE TRIGGER lc_ledger_count_kept BEFORE DELETE ON lc_ledger_count FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_count: counts are never deleted';

-- A Black Contract's qualifying death waits for its killer's claim; claimed once.
CREATE TABLE lc_ledger_claim (
	contract_id BIGINT NOT NULL,
	death_key VARCHAR(64) NOT NULL,
	claim_deadline TIMESTAMP(3) NOT NULL,
	claimed_at TIMESTAMP(3) NULL,
	claim_op BINARY(16) NULL,
	PRIMARY KEY (contract_id, death_key),
	CONSTRAINT fk_ledger_claim_decision FOREIGN KEY (death_key, contract_id) REFERENCES lc_ledger_decision (death_key, contract_id),
	CONSTRAINT chk_ledger_claim CHECK ((claimed_at IS NULL) = (claim_op IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_claim_insert BEFORE INSERT ON lc_ledger_claim FOR EACH ROW IF (SELECT outcome FROM lc_ledger_decision WHERE death_key = NEW.death_key AND contract_id = NEW.contract_id) <> 'QUALIFIED' OR NEW.claimed_at IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_claim: a claim for a qualifying death, unclaimed'; END IF;

CREATE TRIGGER lc_ledger_claim_once BEFORE UPDATE ON lc_ledger_claim FOR EACH ROW IF OLD.claimed_at IS NOT NULL OR NEW.claimed_at IS NULL OR NEW.contract_id <> OLD.contract_id OR NEW.death_key <> OLD.death_key OR NEW.claim_deadline <> OLD.claim_deadline OR NEW.claimed_at > OLD.claim_deadline THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_claim: claimed once, before its deadline'; END IF;

-- Every payout, checked and resolved on its own (spec §3.3, §4.11).
CREATE TABLE lc_ledger_payment (
	payment_id BIGINT NOT NULL AUTO_INCREMENT,
	kind ENUM('BOUNTY', 'SIGHTING', 'ALIAS_LINK') NOT NULL,
	contract_id BIGINT NULL,
	death_key VARCHAR(64) NULL,
	funding ENUM('PLAYER', 'EXTERNAL') NOT NULL DEFAULT 'PLAYER',
	source_custody INT NULL,
	beneficiary_char INT NOT NULL,
	amount BIGINT NOT NULL,
	state ENUM('PENDING', 'HELD', 'PAID', 'BLOCKED', 'RETURNED') NOT NULL DEFAULT 'PENDING',
	reason VARCHAR(48) NULL,
	evidence JSON NULL,
	review_until TIMESTAMP(3) NULL,
	resolved_by VARCHAR(32) NULL,
	op_id BINARY(16) NULL,
	expires_at TIMESTAMP(3) NULL,
	PRIMARY KEY (payment_id),
	KEY idx_ledger_payment_contract (contract_id, death_key, kind, state),
	UNIQUE KEY uk_ledger_payment_leg (contract_id, death_key, funding),
	KEY idx_ledger_payment_expiry (expires_at),
	CONSTRAINT fk_ledger_payment_contract FOREIGN KEY (contract_id) REFERENCES lc_ledger_contract (contract_id),
	CONSTRAINT chk_ledger_payment_amount CHECK (amount > 0),
	CONSTRAINT chk_ledger_payment_contract CHECK ((kind = 'ALIAS_LINK') = (contract_id IS NULL) AND (kind = 'BOUNTY') = (death_key IS NOT NULL) AND (funding = 'EXTERNAL') <= (kind = 'BOUNTY')),
	CONSTRAINT chk_ledger_payment_hold CHECK ((state = 'HELD') <= (review_until IS NOT NULL AND reason IS NOT NULL) AND (state IN ('BLOCKED', 'RETURNED')) <= (reason IS NOT NULL)),
	CONSTRAINT chk_ledger_payment_evidence CHECK (evidence IS NULL OR JSON_VALID(evidence))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_payment_insert BEFORE INSERT ON lc_ledger_payment FOR EACH ROW IF NEW.state <> 'PENDING' OR NEW.expires_at IS NOT NULL OR (NEW.kind = 'BOUNTY' AND (NOT NEW.death_key <=> (SELECT settling_death FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) OR (SELECT state FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) <> 'SETTLING' OR (SELECT IF(NEW.funding = 'PLAYER', player_principal, external_principal) FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) <= 0)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_payment: a payment starts pending; a bounty leg only while settling, for the settling death, for a funding share present'; END IF;

CREATE TRIGGER lc_ledger_payment_resolve BEFORE UPDATE ON lc_ledger_payment FOR EACH ROW IF NEW.payment_id <> OLD.payment_id OR NEW.kind <> OLD.kind OR NOT (NEW.contract_id <=> OLD.contract_id) OR NEW.beneficiary_char <> OLD.beneficiary_char OR NEW.amount <> OLD.amount OR NEW.funding <> OLD.funding OR NOT (NEW.death_key <=> OLD.death_key) OR NOT ((OLD.state = NEW.state AND OLD.state IN ('PAID', 'BLOCKED', 'RETURNED') AND OLD.expires_at IS NULL AND NEW.expires_at IS NOT NULL) OR (OLD.state = 'PENDING' AND NEW.state IN ('PAID', 'BLOCKED', 'HELD')) OR (OLD.state = 'HELD' AND NEW.state IN ('PAID', 'RETURNED'))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_payment: resolved once, along spec 3.3'; END IF;

-- One seal per (contract, hunter) of a Black Contract whose hunter failed, keyed by its item's Asset lifecycle id.
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

CREATE TRIGGER lc_ledger_seal_insert BEFORE INSERT ON lc_ledger_seal FOR EACH ROW IF (SELECT kind FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) <> 'BLACK' OR (SELECT state FROM lc_ledger_acceptance WHERE contract_id = NEW.contract_id AND hunter_char = NEW.hunter_char) <> 'FAILED' OR NEW.mark_char <> (SELECT mark_char FROM lc_ledger_contract WHERE contract_id = NEW.contract_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_seal: a seal only for a failed hunter of a Black Contract, to its mark'; END IF;

CREATE TRIGGER lc_ledger_seal_fixed BEFORE UPDATE ON lc_ledger_seal FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_seal: a seal never changes';

-- A reverse case starts at step 0, for the seal's mark, and advances one step at a time before it expires.
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

CREATE TRIGGER lc_ledger_reverse_insert BEFORE INSERT ON lc_ledger_reverse_case FOR EACH ROW IF NEW.step <> 0 OR NEW.holder_char <> (SELECT mark_char FROM lc_ledger_seal WHERE lifecycle_id = NEW.lifecycle_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_reverse_case: a case starts at step 0, held by the seal mark'; END IF;

CREATE TRIGGER lc_ledger_reverse_step BEFORE UPDATE ON lc_ledger_reverse_case FOR EACH ROW IF NEW.step <> OLD.step + 1 OR NEW.holder_char <> OLD.holder_char OR NEW.lifecycle_id <> OLD.lifecycle_id OR NOT (NEW.expires_at <=> OLD.expires_at) OR OLD.expires_at <= NOW(3) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_reverse_case: one step at a time, before it expires'; END IF;

-- A sighting offer dies with its trace and never outlives it.
CREATE TABLE lc_ledger_offer (
	offer_id BIGINT NOT NULL AUTO_INCREMENT,
	informant_char INT NOT NULL,
	mark_char INT NOT NULL,
	trace_id BIGINT NOT NULL,
	price BIGINT NOT NULL,
	state ENUM('OFFERED', 'SOLD', 'EXPIRED') NOT NULL DEFAULT 'OFFERED',
	payment_id BIGINT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (offer_id),
	KEY idx_ledger_offer_mark (mark_char, state),
	KEY idx_ledger_offer_expiry (expires_at),
	CONSTRAINT fk_ledger_offer_trace FOREIGN KEY (trace_id) REFERENCES lc_trace (trace_id) ON DELETE CASCADE,
	CONSTRAINT fk_ledger_offer_payment FOREIGN KEY (payment_id) REFERENCES lc_ledger_payment (payment_id),
	CONSTRAINT chk_ledger_offer_price CHECK (price > 0),
	CONSTRAINT chk_ledger_offer_party CHECK (informant_char <> mark_char)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_ledger_offer_insert BEFORE INSERT ON lc_ledger_offer FOR EACH ROW IF NOT EXISTS (SELECT 1 FROM lc_trace WHERE trace_id = NEW.trace_id AND kind = 'INFORMANT' AND subject_char = NEW.mark_char AND expires_at >= NEW.expires_at) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_offer: an informant trace of the mark, never outliving it'; END IF;

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
	cooldown_until TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (mark_char)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_ledger_endowment_use (
	mark_char INT NOT NULL,
	window_start TIMESTAMP NOT NULL,
	paid BIGINT NOT NULL DEFAULT 0,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (mark_char, window_start),
	KEY idx_ledger_endowment_expiry (expires_at),
	CONSTRAINT chk_ledger_endowment_paid CHECK (paid >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO lc_expiring_table (table_name, expires_column, owner_module, max_life_hours) VALUES ('lc_ledger_patron', 'expires_at', 'LEDGER', 1440), ('lc_ledger_funder', 'expires_at', 'LEDGER', 1440), ('lc_ledger_payment', 'expires_at', 'LEDGER', 2160), ('lc_ledger_offer', 'expires_at', 'LEDGER', 24), ('lc_ledger_inquiry', 'expires_at', 'LEDGER', 24), ('lc_ledger_pair', 'expires_at', 'LEDGER', 2160), ('lc_ledger_reverse_case', 'expires_at', 'LEDGER', 168), ('lc_ledger_endowment_use', 'expires_at', 'LEDGER', 2160);

-- Inserted open at version 0 with no kills.
CREATE TRIGGER lc_ledger_contract_insert BEFORE INSERT ON lc_ledger_contract FOR EACH ROW IF NEW.state <> 'OPEN' OR NEW.version <> 0 OR NEW.kills_done <> 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: a contract is created open'; END IF;

-- Spec §3.1 with its row guards: identity and terms fixed; principal shares grow only while an open Writ stays open, and
-- drop to 0 only when a settlement ends with that share's leg returned; the version advances by one; the transition table
-- per kind; kills_done always equals the unvoided count rows; entering CLAIMABLE or SETTLING names a death with an
-- unvoided count row and needs every required kill counted; SETTLING from CLAIMABLE needs that death's claim claimed;
-- SETTLED needs the complete set of bounty legs, all resolved, one paid; leaving CLAIMABLE or SETTLING for OPEN or
-- EXPIRED needs the death's count voided and clears it (from SETTLING only once every leg was blocked or returned, and to
-- OPEN only with principal left); CANCELLED needs no acceptance; terminal is final.
CREATE TRIGGER lc_ledger_contract_transition BEFORE UPDATE ON lc_ledger_contract FOR EACH ROW BEGIN DECLARE entering BOOLEAN DEFAULT OLD.state IN ('OPEN', 'REVIEW') AND NEW.state IN ('CLAIMABLE', 'SETTLING'); DECLARE leaving BOOLEAN DEFAULT OLD.state IN ('CLAIMABLE', 'SETTLING') AND NEW.state IN ('OPEN', 'EXPIRED'); DECLARE ending BOOLEAN DEFAULT OLD.state = 'SETTLING' AND NEW.state IN ('SETTLED', 'OPEN', 'EXPIRED'); DECLARE player_leg VARCHAR(16) DEFAULT (SELECT state FROM lc_ledger_payment WHERE contract_id = OLD.contract_id AND death_key = OLD.settling_death AND kind = 'BOUNTY' AND funding = 'PLAYER'); DECLARE external_leg VARCHAR(16) DEFAULT (SELECT state FROM lc_ledger_payment WHERE contract_id = OLD.contract_id AND death_key = OLD.settling_death AND kind = 'BOUNTY' AND funding = 'EXTERNAL'); IF NEW.contract_id <> OLD.contract_id OR NEW.kind <> OLD.kind OR NEW.cause <> OLD.cause OR NEW.mark_char <> OLD.mark_char OR NOT (NEW.issuer_clan <=> OLD.issuer_clan) OR NOT (NEW.target_clan <=> OLD.target_clan) OR NEW.created_at <> OLD.created_at OR NEW.deadline <> OLD.deadline OR NEW.intel_budget <> OLD.intel_budget OR NEW.max_sighting_price <> OLD.max_sighting_price OR NEW.broker_fee <> OLD.broker_fee OR NOT (NEW.conditions <=> OLD.conditions) OR NEW.terms_hash <> OLD.terms_hash OR NEW.origin_npc <> OLD.origin_npc OR NEW.origin_town <> OLD.origin_town OR NEW.sealed_identity <> OLD.sealed_identity OR NEW.min_rank <> OLD.min_rank OR NEW.max_hunters <> OLD.max_hunters OR NEW.kills_required <> OLD.kills_required THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: identity and terms never change'; END IF; IF (NEW.player_principal <> OLD.player_principal AND NOT ((OLD.kind = 'WRIT' AND OLD.state = 'OPEN' AND NEW.state = 'OPEN' AND NEW.player_principal > OLD.player_principal) OR (ending AND player_leg = 'RETURNED' AND NEW.player_principal = 0))) OR (NEW.external_principal <> OLD.external_principal AND NOT ((OLD.kind = 'WRIT' AND OLD.state = 'OPEN' AND NEW.state = 'OPEN' AND NEW.external_principal > OLD.external_principal) OR (ending AND external_leg = 'RETURNED' AND NEW.external_principal = 0))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: principal shares grow only while an open Writ, and drop to 0 only with their leg returned'; END IF; IF ending AND ((player_leg <=> 'RETURNED' AND NEW.player_principal <> 0) OR (external_leg <=> 'RETURNED' AND NEW.external_principal <> 0)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: a returned share leaves the contract'; END IF; IF NEW.version <> OLD.version + 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: every change advances the version by one'; END IF; IF OLD.state IN ('SETTLED', 'EXPIRED', 'CANCELLED', 'LAPSED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: a terminal contract never changes'; END IF; IF NEW.state <> OLD.state AND NOT ((OLD.state = 'OPEN' AND (NEW.state IN ('EXPIRED', 'REVIEW') OR (OLD.kind = 'BLACK' AND NEW.state IN ('CANCELLED', 'CLAIMABLE')) OR (OLD.kind = 'WRIT' AND NEW.state IN ('SETTLING', 'LAPSED')))) OR (OLD.state = 'CLAIMABLE' AND NEW.state IN ('SETTLING', 'OPEN', 'EXPIRED')) OR (OLD.state = 'SETTLING' AND NEW.state IN ('SETTLED', 'OPEN', 'EXPIRED')) OR (OLD.state = 'REVIEW' AND NEW.state IN ('OPEN', 'EXPIRED', 'SETTLING'))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: a transition the state machine does not have'; END IF; IF NEW.kills_done <> (SELECT COUNT(*) FROM lc_ledger_count WHERE contract_id = OLD.contract_id AND voided_at IS NULL) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: kills_done is the count of unvoided counted kills'; END IF; IF NOT (entering OR leaving) AND NOT (NEW.settling_death <=> OLD.settling_death) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: the settling death changes only on entering or leaving a settlement'; END IF; IF entering AND (NEW.kills_done <> NEW.kills_required OR NOT EXISTS (SELECT 1 FROM lc_ledger_count WHERE contract_id = OLD.contract_id AND death_key = NEW.settling_death AND voided_at IS NULL)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: a settlement needs a counted, unvoided death and every required kill'; END IF; IF leaving AND (NEW.settling_death IS NOT NULL OR NOT EXISTS (SELECT 1 FROM lc_ledger_count WHERE contract_id = OLD.contract_id AND death_key = OLD.settling_death AND voided_at IS NOT NULL)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: leaving a settlement voids its death and clears it'; END IF; IF OLD.state = 'CLAIMABLE' AND NEW.state = 'SETTLING' AND NOT EXISTS (SELECT 1 FROM lc_ledger_claim WHERE contract_id = OLD.contract_id AND death_key = OLD.settling_death AND claimed_at IS NOT NULL) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: the settling death was not claimed'; END IF; IF ending AND ((OLD.player_principal > 0 AND player_leg IS NULL) OR (OLD.external_principal > 0 AND external_leg IS NULL) OR player_leg IN ('PENDING', 'HELD') OR external_leg IN ('PENDING', 'HELD')) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: a settlement ends only with its complete set of legs resolved'; END IF; IF OLD.state = 'SETTLING' AND NEW.state = 'SETTLED' AND NOT (player_leg <=> 'PAID' OR external_leg <=> 'PAID') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: settled only with a paid leg'; END IF; IF OLD.state = 'SETTLING' AND leaving AND (player_leg <=> 'PAID' OR external_leg <=> 'PAID' OR (NEW.state = 'OPEN' AND NEW.principal <= 0)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: a settlement reopens only with every leg blocked or returned, and principal left'; END IF; IF NEW.state = 'CANCELLED' AND EXISTS (SELECT 1 FROM lc_ledger_acceptance WHERE contract_id = OLD.contract_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: cancelled only before any acceptance'; END IF; END;

CREATE TRIGGER lc_ledger_contract_kept BEFORE DELETE ON lc_ledger_contract FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_ledger_contract: contracts are never deleted';
