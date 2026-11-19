-- SPEC-INTEL (docs/spec/intel-service.md §2): traces and their evidence (never journaled), intel items and their grants,
-- the disclosure budget's ledger, registered copies, witness suppressions, Signal presence, and the registry of every
-- table that holds expiring data.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it. precision and until are
-- reserved words in MariaDB, hence precision_level and suppressed_until.

CREATE TABLE lc_trace (
	trace_id BIGINT NOT NULL AUTO_INCREMENT,
	subject_char INT NOT NULL,
	kind ENUM('SIGHTING', 'GATEKEEPER', 'WAREHOUSE', 'MERCHANT', 'LOGIN', 'KILL', 'INFORMANT', 'OTHER') NOT NULL,
	witness_ref VARCHAR(32) NOT NULL,
	zone_id INT NULL,
	region_id SMALLINT NOT NULL,
	territory_id SMALLINT NULL,
	coarse_dir TINYINT NULL,
	occurred_at TIMESTAMP(3) NOT NULL,
	disclosable_at TIMESTAMP(3) NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	sensitivity TINYINT NOT NULL,
	PRIMARY KEY (trace_id),
	KEY idx_trace_subject (subject_char, witness_ref, occurred_at),
	KEY idx_trace_expiry (expires_at),
	CONSTRAINT chk_trace_quantum CHECK (disclosable_at >= occurred_at),
	CONSTRAINT chk_trace_life CHECK (expires_at > disclosable_at),
	CONSTRAINT chk_trace_dir CHECK ((coarse_dir IS NULL) OR (coarse_dir BETWEEN 0 AND 7)),
	CONSTRAINT chk_trace_sensitivity CHECK (sensitivity BETWEEN 0 AND 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A trace's exact evidence and where the witness stood: read only by the service and forensics, expiring exactly with the trace.
CREATE TABLE lc_trace_evidence (
	trace_id BIGINT NOT NULL,
	x INT NOT NULL,
	y INT NOT NULL,
	z INT NOT NULL,
	instance_id INT NOT NULL DEFAULT 0,
	observer_x INT NOT NULL,
	observer_y INT NOT NULL,
	observed_ms INT NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (trace_id),
	KEY idx_evidence_expiry (expires_at),
	CONSTRAINT fk_evidence_trace FOREIGN KEY (trace_id) REFERENCES lc_trace (trace_id) ON DELETE CASCADE,
	CONSTRAINT chk_evidence_observed CHECK (observed_ms >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The one coarse statement ever made from a trace. Its precision is never EXACT_INTERNAL: that value does not exist here.
CREATE TABLE lc_intel (
	intel_id BIGINT NOT NULL AUTO_INCREMENT,
	trace_id BIGINT NOT NULL,
	kind VARCHAR(32) NOT NULL,
	subject_char INT NOT NULL,
	precision_level ENUM('ZONE', 'REGION', 'TERRITORY', 'NONE') NOT NULL,
	confidence ENUM('CONFIRMED', 'WITNESS', 'INFERRED', 'RUMOR', 'STALE') NOT NULL,
	payload_type VARCHAR(32) NOT NULL,
	payload JSON NOT NULL,
	age_band ENUM('FRESH', 'RECENT', 'OLD') NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	shareable BOOLEAN NOT NULL,
	publishable BOOLEAN NOT NULL,
	PRIMARY KEY (intel_id),
	UNIQUE KEY uk_intel_trace (trace_id),
	KEY idx_intel_subject (subject_char, created_at),
	KEY idx_intel_expiry (expires_at),
	CONSTRAINT fk_intel_trace FOREIGN KEY (trace_id) REFERENCES lc_trace (trace_id) ON DELETE CASCADE,
	CONSTRAINT chk_intel_life CHECK (expires_at > created_at),
	CONSTRAINT chk_intel_payload CHECK (JSON_VALID(payload))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_trace_evidence_within_trace BEFORE INSERT ON lc_trace_evidence FOR EACH ROW IF NOT EXISTS (SELECT 1 FROM lc_trace WHERE trace_id = NEW.trace_id AND NEW.expires_at = expires_at) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_trace_evidence: evidence expires exactly with its trace'; END IF;

-- An item expires exactly with its trace, so it is never purged and replaced while its trace lives (spec §4.5).
CREATE TRIGGER lc_intel_within_trace BEFORE INSERT ON lc_intel FOR EACH ROW IF NOT EXISTS (SELECT 1 FROM lc_trace WHERE trace_id = NEW.trace_id AND expires_at = NEW.expires_at) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_intel: an item expires exactly with its trace'; END IF;

CREATE TABLE lc_intel_grant (
	intel_id BIGINT NOT NULL,
	audience_kind ENUM('CHAR', 'CONTRACT', 'CLAN') NOT NULL,
	audience_id INT NOT NULL,
	purpose VARCHAR(32) NOT NULL,
	granted_at TIMESTAMP(3) NOT NULL,
	view_precision ENUM('ZONE', 'REGION', 'TERRITORY', 'NONE') NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (intel_id, audience_kind, audience_id, purpose),
	KEY idx_grant_expiry (expires_at),
	CONSTRAINT fk_grant_intel FOREIGN KEY (intel_id) REFERENCES lc_intel (intel_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A grant is viewed no finer than its item and lives no longer (spec §2.2).
CREATE TRIGGER lc_intel_grant_within_item BEFORE INSERT ON lc_intel_grant FOR EACH ROW IF NOT EXISTS (SELECT 1 FROM lc_intel WHERE intel_id = NEW.intel_id AND NEW.expires_at <= expires_at AND NEW.view_precision + 0 >= precision_level + 0) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_intel_grant: a grant is never finer or longer-lived than its item'; END IF;

-- The budget's ledger: per subject and witness, over a rolling window, never per requester; it expires with the window.
CREATE TABLE lc_disclosure (
	target_char INT NOT NULL,
	witness_ref VARCHAR(32) NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	units INT NOT NULL,
	implied_cells INT NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (target_char, witness_ref, at),
	KEY idx_disclosure_target (target_char, at),
	KEY idx_disclosure_expiry (expires_at),
	CONSTRAINT chk_disclosure_units CHECK (units > 0),
	CONSTRAINT chk_disclosure_cells CHECK (implied_cells > 0),
	CONSTRAINT chk_disclosure_life CHECK (expires_at > at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The cells each positive answer implied, for the combined bound (spec §4.3); expires with the window.
CREATE TABLE lc_disclosure_cell (
	target_char INT NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	cell INT NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (target_char, at, cell),
	KEY idx_disclosure_cell_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every module row that depends on intel, one row per dependency (spec §4.5).
CREATE TABLE lc_intel_copy (
	module VARCHAR(16) NOT NULL,
	table_name VARCHAR(64) NOT NULL,
	row_key VARCHAR(64) NOT NULL,
	intel_id BIGINT NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (module, table_name, row_key, intel_id),
	KEY idx_copy_expiry (expires_at),
	KEY idx_copy_intel (intel_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A copy never outlives the item it depends on.
CREATE TRIGGER lc_intel_copy_within_item BEFORE INSERT ON lc_intel_copy FOR EACH ROW IF NEW.expires_at > IFNULL((SELECT expires_at FROM lc_intel WHERE intel_id = NEW.intel_id), TIMESTAMP('1970-01-02')) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_intel_copy: a copy never outlives its item, nor depends on one that does not exist'; END IF;

-- Rows are immutable: inserted with their expiry, parent and precision, deleted at expiry, never updated (spec §2.2).
CREATE TRIGGER lc_trace_immutable BEFORE UPDATE ON lc_trace FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_trace: rows are inserted and deleted, never updated';
CREATE TRIGGER lc_trace_evidence_immutable BEFORE UPDATE ON lc_trace_evidence FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_trace_evidence: rows are inserted and deleted, never updated';
CREATE TRIGGER lc_intel_immutable BEFORE UPDATE ON lc_intel FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_intel: rows are inserted and deleted, never updated';
CREATE TRIGGER lc_intel_grant_immutable BEFORE UPDATE ON lc_intel_grant FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_intel_grant: rows are inserted and deleted, never updated';
CREATE TRIGGER lc_intel_copy_immutable BEFORE UPDATE ON lc_intel_copy FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_intel_copy: rows are inserted and deleted, never updated';
CREATE TRIGGER lc_disclosure_immutable BEFORE UPDATE ON lc_disclosure FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_disclosure: rows are inserted and deleted, never updated';
CREATE TRIGGER lc_disclosure_cell_immutable BEFORE UPDATE ON lc_disclosure_cell FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_disclosure_cell: rows are inserted and deleted, never updated';

CREATE TABLE lc_witness_suppression (
	witness_ref VARCHAR(32) NOT NULL,
	subject_char INT NOT NULL,
	suppressed_until TIMESTAMP(3) NOT NULL,
	source_fact_id BIGINT NOT NULL,
	PRIMARY KEY (witness_ref, subject_char)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_signal_presence (
	char_id INT NOT NULL,
	opted_in BOOLEAN NOT NULL,
	region_id SMALLINT NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id),
	KEY idx_signal_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every table holding expiring data, with its expiry column and the longest life its rows may have: the purge job, the
-- backup policy and the restore procedure work from this list (spec §4.5).
CREATE TABLE lc_expiring_table (
	table_name VARCHAR(64) NOT NULL,
	expires_column VARCHAR(32) NOT NULL,
	owner_module VARCHAR(16) NOT NULL,
	max_life_hours INT NOT NULL,
	PRIMARY KEY (table_name),
	CONSTRAINT chk_expiring_life CHECK (max_life_hours > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The publication registry (spec §4.7): every lc_pub_* row is of a registered type, every field has a rule, and a
-- location-related type waits at least the tactical delay (24 h; the configuration may raise it, never lower it).
CREATE TABLE lc_pub_type (
	pub_type VARCHAR(48) NOT NULL,
	min_delay_minutes INT NOT NULL,
	location_related BOOLEAN NOT NULL,
	PRIMARY KEY (pub_type),
	CONSTRAINT chk_pub_delay CHECK (min_delay_minutes >= 0 AND (NOT location_related OR min_delay_minutes >= 1440))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_pub_field_rule (
	pub_type VARCHAR(48) NOT NULL,
	field VARCHAR(48) NOT NULL,
	rule ENUM('PUBLIC_ENTITY', 'AREA_TERRITORY', 'DAY', 'COUNT', 'TEXT_FROM_FACT') NOT NULL,
	PRIMARY KEY (pub_type, field),
	CONSTRAINT fk_pub_field_type FOREIGN KEY (pub_type) REFERENCES lc_pub_type (pub_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO lc_expiring_table (table_name, expires_column, owner_module, max_life_hours) VALUES ('lc_trace', 'expires_at', 'INTEL', 24), ('lc_trace_evidence', 'expires_at', 'INTEL', 24), ('lc_intel', 'expires_at', 'INTEL', 24), ('lc_intel_grant', 'expires_at', 'INTEL', 24), ('lc_intel_copy', 'expires_at', 'INTEL', 24), ('lc_disclosure', 'expires_at', 'INTEL', 24), ('lc_disclosure_cell', 'expires_at', 'INTEL', 24), ('lc_signal_presence', 'expires_at', 'INTEL', 1), ('lc_fact_precise', 'expires_at', 'JOURNAL', 72), ('lc_fact_expiring', 'expires_at', 'JOURNAL', 720);
