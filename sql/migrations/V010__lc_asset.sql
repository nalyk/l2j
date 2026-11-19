-- SPEC-ASSET (docs/spec/asset-service.md §2): custody, asset operations, ledger, settlement keys, item lifecycles,
-- the economy monitor's aggregates, and the module freeze switch.
--
-- Escrowed assets are not here: they stay canonical rows in `items` with owner_id = -1, loc = 'ESCROW' and
-- loc_data = custody_id (spec §2.3); what is owed to a character is system mail (`messages`, `items` loc = 'MAIL'). `items.loc_data` is an INT, so custody ids are INTs.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

CREATE TABLE lc_custody (
	custody_id INT NOT NULL AUTO_INCREMENT,
	kind ENUM('CONTRACT', 'POOL', 'MANIFEST') NOT NULL,
	owner_module VARCHAR(16) NOT NULL,
	owner_ref BIGINT NOT NULL,
	state ENUM('OPEN', 'FROZEN', 'CLOSED') NOT NULL,
	version INT NOT NULL DEFAULT 0,
	created_op BINARY(16) NOT NULL,
	closed_op BINARY(16) NULL,
	created_at TIMESTAMP(3) NOT NULL,
	closed_at TIMESTAMP(3) NULL,
	PRIMARY KEY (custody_id),
	KEY idx_custody_owner (owner_module, owner_ref),
	CONSTRAINT chk_custody_closed CHECK ((state = 'CLOSED') = (closed_op IS NOT NULL)),
	CONSTRAINT chk_custody_closed_at CHECK ((closed_op IS NULL) = (closed_at IS NULL)),
	CONSTRAINT chk_custody_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A row exists exactly when its operation committed: it is written inside the operation's own transaction.
CREATE TABLE lc_asset_op (
	op_id BINARY(16) NOT NULL,
	module VARCHAR(16) NOT NULL,
	action VARCHAR(32) NOT NULL,
	subject_ref BIGINT NOT NULL,
	committed_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (op_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_ledger_posting (
	id BIGINT NOT NULL AUTO_INCREMENT,
	op_id BINARY(16) NOT NULL,
	leg SMALLINT NOT NULL,
	account_kind ENUM('CASTLE_TREASURY', 'CLAN_WAREHOUSE', 'PLAYER', 'PLAYER_WAREHOUSE', 'CUSTODY', 'MAIL', 'SINK', 'SOURCE') NOT NULL,
	account_id BIGINT NOT NULL,
	code VARCHAR(24) NULL,
	item_id INT NOT NULL,
	object_id INT NULL,
	lifecycle_id BIGINT NULL,
	qty BIGINT NOT NULL,
	direction ENUM('DEBIT', 'CREDIT') NOT NULL,
	fact_id BIGINT NULL,
	PRIMARY KEY (id),
	UNIQUE KEY uk_posting_leg (op_id, leg),
	KEY idx_posting_account (account_kind, account_id),
	KEY idx_posting_item (item_id),
	CONSTRAINT fk_posting_op FOREIGN KEY (op_id) REFERENCES lc_asset_op (op_id),
	CONSTRAINT chk_posting_qty CHECK (qty > 0),
	CONSTRAINT chk_posting_leg CHECK (leg >= 0),
	CONSTRAINT chk_posting_code CHECK ((account_kind IN ('SINK', 'SOURCE')) = (code IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The one settlement-uniqueness mechanism for every module (spec §2.2).
CREATE TABLE lc_settlement_key (
	scope VARCHAR(16) NOT NULL,
	skey VARCHAR(96) NOT NULL,
	op_id BINARY(16) NOT NULL,
	PRIMARY KEY (scope, skey),
	CONSTRAINT fk_settlement_op FOREIGN KEY (op_id) REFERENCES lc_asset_op (op_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Mobius reuses object ids after destruction, so provenance follows a lifecycle id; at most one is live per object id.
-- A lifecycle ends when its object leaves existence or merges into another stack; a split starts a child.
CREATE TABLE lc_item_lifecycle (
	lifecycle_id BIGINT NOT NULL AUTO_INCREMENT,
	object_id INT NOT NULL,
	item_id INT NOT NULL,
	parent_lifecycle_id BIGINT NULL,
	began_op BINARY(16) NULL,
	began_at TIMESTAMP(3) NOT NULL,
	ended_at TIMESTAMP(3) NULL,
	end_reason ENUM('DESTROYED', 'MERGED', 'SPLIT_REMAINDER_GONE') NULL,
	merged_into BIGINT NULL,
	live_object_id INT AS (IF(ended_at IS NULL, object_id, NULL)) PERSISTENT,
	PRIMARY KEY (lifecycle_id),
	UNIQUE KEY uk_lifecycle_live (live_object_id),
	KEY idx_lifecycle_object (object_id),
	CONSTRAINT fk_lifecycle_parent FOREIGN KEY (parent_lifecycle_id) REFERENCES lc_item_lifecycle (lifecycle_id),
	CONSTRAINT fk_lifecycle_merged FOREIGN KEY (merged_into) REFERENCES lc_item_lifecycle (lifecycle_id),
	CONSTRAINT chk_lifecycle_end CHECK ((ended_at IS NULL) = (end_reason IS NULL)),
	CONSTRAINT chk_lifecycle_order CHECK ((ended_at IS NULL) OR (ended_at >= began_at)),
	-- Null-safe: a live lifecycle (end_reason NULL) with merged_into set must fail, not evaluate to NULL and pass.
	CONSTRAINT chk_lifecycle_merge CHECK ((end_reason <=> 'MERGED') = (merged_into IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Economy monitor (spec §2.6): quantity counted per classified site (docs/spec/asset-economy-sites.csv) and window,
-- never per event.
CREATE TABLE lc_economy_bucket (
	window_start TIMESTAMP NOT NULL,
	item_id INT NOT NULL,
	site VARCHAR(48) NOT NULL,
	class ENUM('SOURCE', 'SINK', 'MOVE_OUT', 'MOVE_IN') NOT NULL,
	qty BIGINT UNSIGNED NOT NULL,
	events INT UNSIGNED NOT NULL,
	PRIMARY KEY (window_start, item_id, site, class)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Exact only at quiescence: a clean shutdown and the startup after it; a crash makes the startup census a rebase.
CREATE TABLE lc_economy_census (
	census_id BIGINT NOT NULL,
	taken_at TIMESTAMP(3) NOT NULL,
	kind ENUM('SHUTDOWN', 'STARTUP', 'REBASE') NOT NULL,
	item_id INT NOT NULL,
	holding VARCHAR(16) NOT NULL,
	qty BIGINT UNSIGNED NOT NULL,
	PRIMARY KEY (census_id, item_id, holding)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The existence counters, persisted with each shutdown census and compared with the next startup's.
CREATE TABLE lc_economy_counter (
	item_id INT NOT NULL,
	existence BIGINT NOT NULL,
	as_of_census BIGINT NOT NULL,
	PRIMARY KEY (item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The freeze switch every Living Chronicle module honours (blueprint 04 §8).
CREATE TABLE lc_module_state (
	module VARCHAR(16) NOT NULL,
	state ENUM('RUNNING', 'FROZEN') NOT NULL,
	reason VARCHAR(64) NULL,
	since TIMESTAMP(3) NOT NULL,
	evidence_fact_id BIGINT NULL,
	PRIMARY KEY (module),
	CONSTRAINT chk_module_frozen_reason CHECK ((state = 'RUNNING') OR (reason IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every system mail an operation wrote (spec §2.1): what a character is owed arrives as retail mail, never into a live
-- container. The mail itself is Mobius's (`messages` and its attachment rows).
CREATE TABLE lc_mail_delivery (
	message_id INT NOT NULL,
	op_id BINARY(16) NOT NULL,
	receiver_char INT NOT NULL,
	reason ENUM('PAYOUT', 'REFUND', 'RETURN', 'CORRECTION') NOT NULL,
	-- Set by the claim (core patch) or by the census when retail has cleared the attachments; unset means the message must exist.
	claimed_at TIMESTAMP(3) NULL,
	PRIMARY KEY (message_id),
	KEY idx_mail_delivery_op (op_id),
	CONSTRAINT fk_mail_delivery_op FOREIGN KEY (op_id) REFERENCES lc_asset_op (op_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A character whose items an operation has taken in memory and not yet committed or refunded (spec §2.4): committed on
-- its own before the first debit, deleted by the operation's or the refund's transaction; login waits while one exists.
CREATE TABLE lc_asset_pending (
	char_id INT NOT NULL,
	op_id BINARY(16) NOT NULL,
	since TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id, op_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
