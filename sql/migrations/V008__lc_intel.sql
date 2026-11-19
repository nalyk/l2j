-- SPEC-INTEL (docs/spec/intel-service.md §2): traces, disclosed intel, disclosure budgets, registered copies and witness
-- suppressions. Exact evidence is not here: it is the Fact Journal's lc_fact_precise row of a trace's source fact.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

CREATE TABLE lc_trace (
	trace_id BIGINT NOT NULL AUTO_INCREMENT,
	subject_char INT NOT NULL,
	kind ENUM('SIGHTING', 'GATEKEEPER', 'WAREHOUSE', 'MERCHANT', 'LOGIN', 'KILL', 'OTHER') NOT NULL,
	witness_ref VARCHAR(32) NULL,
	zone_id INT NULL,
	region_id SMALLINT NOT NULL,
	territory_id SMALLINT NULL,
	coarse_dir TINYINT NULL,
	occurred_at TIMESTAMP(3) NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	sensitivity TINYINT NOT NULL,
	source_fact_id BIGINT NOT NULL,
	PRIMARY KEY (trace_id),
	KEY idx_trace_subject (subject_char, occurred_at),
	KEY idx_trace_expiry (expires_at),
	CONSTRAINT chk_trace_life CHECK (expires_at > occurred_at),
	CONSTRAINT chk_trace_dir CHECK ((coarse_dir IS NULL) OR (coarse_dir BETWEEN 0 AND 7)),
	CONSTRAINT chk_trace_sensitivity CHECK (sensitivity BETWEEN 0 AND 3),
	CONSTRAINT chk_trace_witness CHECK ((kind <> 'SIGHTING') OR (witness_ref IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- One disclosure to one audience. Its precision is never EXACT_INTERNAL: that value does not exist here. (precision and
-- until are reserved words in MariaDB, hence precision_level and suppressed_until.)
CREATE TABLE lc_intel (
	intel_id BIGINT NOT NULL AUTO_INCREMENT,
	trace_id BIGINT NULL,
	kind VARCHAR(32) NOT NULL,
	subject_char INT NOT NULL,
	precision_level ENUM('ZONE', 'REGION', 'TERRITORY', 'NONE') NOT NULL,
	confidence ENUM('CONFIRMED', 'WITNESS', 'INFERRED', 'RUMOR', 'STALE') NOT NULL,
	payload JSON NOT NULL,
	audience_kind ENUM('CHAR', 'CONTRACT', 'CLAN') NOT NULL,
	audience_id INT NOT NULL,
	purpose VARCHAR(32) NOT NULL,
	created_at TIMESTAMP(3) NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	shareable BOOLEAN NOT NULL,
	publishable BOOLEAN NOT NULL,
	dedup_key BINARY(16) NOT NULL,
	PRIMARY KEY (intel_id),
	KEY idx_intel_dedup (dedup_key, expires_at),
	KEY idx_intel_subject (subject_char, created_at),
	KEY idx_intel_expiry (expires_at),
	CONSTRAINT chk_intel_life CHECK (expires_at > created_at),
	CONSTRAINT chk_intel_payload CHECK (JSON_VALID(payload))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The budget's state, keyed by target and witness, never by the requester (spec §2.2).
CREATE TABLE lc_disclosure (
	target_char INT NOT NULL,
	witness_ref VARCHAR(32) NOT NULL,
	window_start TIMESTAMP NOT NULL,
	units INT NOT NULL,
	PRIMARY KEY (target_char, witness_ref, window_start),
	CONSTRAINT chk_disclosure_units CHECK (units >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every row a module keeps that holds intel, so it is deleted when the intel expires (spec §4.4).
CREATE TABLE lc_intel_copy (
	module VARCHAR(16) NOT NULL,
	table_name VARCHAR(64) NOT NULL,
	row_key VARCHAR(64) NOT NULL,
	intel_id BIGINT NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (module, table_name, row_key),
	KEY idx_copy_expiry (expires_at),
	KEY idx_copy_intel (intel_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_witness_suppression (
	witness_ref VARCHAR(32) NOT NULL,
	subject_char INT NOT NULL,
	suppressed_until TIMESTAMP(3) NOT NULL,
	source_fact_id BIGINT NOT NULL,
	PRIMARY KEY (witness_ref, subject_char)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
