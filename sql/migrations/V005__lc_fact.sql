-- SPEC-FACT (docs/spec/fact-journal.md §2): durable facts, stream heads, precise locations, gaps, telemetry buckets,
-- projector checkpoints, and the fact type registry. Applied before the Asset Service's migration, which writes facts.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

-- Partitioned by month. MariaDB requires every unique key of a partitioned table to include the partition column, and
-- partitioned InnoDB tables take part in no foreign key: a stream's order is kept by lc_stream_head (spec §2.1), and a
-- fact's type and references are checked by the writer and by the invariants, not by keys. The MAXVALUE partition takes
-- whatever no monthly partition does, so an insert never fails for want of one; the purge job splits it ahead of time.
CREATE TABLE lc_fact (
	id BIGINT NOT NULL AUTO_INCREMENT,
	occurred_at TIMESTAMP(3) NOT NULL,
	stream VARCHAR(48) NOT NULL,
	stream_seq BIGINT NOT NULL,
	type SMALLINT NOT NULL,
	schema_ver SMALLINT NOT NULL,
	logical_time BIGINT NOT NULL,
	actor_snapshot JSON NULL,
	region_id SMALLINT NOT NULL,
	zone_id INT NULL,
	instance_id INT NOT NULL DEFAULT 0,
	sensitivity TINYINT NOT NULL,
	expires_at TIMESTAMP(3) NULL,
	payload JSON NOT NULL,
	op_id BINARY(16) NULL,
	PRIMARY KEY (id, occurred_at),
	KEY idx_fact_stream (stream, stream_seq),
	KEY idx_fact_type_time (type, occurred_at),
	KEY idx_fact_op (op_id),
	KEY idx_fact_expiry (expires_at),
	CONSTRAINT chk_fact_seq CHECK (stream_seq > 0),
	CONSTRAINT chk_fact_sensitivity CHECK (sensitivity BETWEEN 0 AND 3),
	CONSTRAINT chk_fact_payload CHECK (JSON_VALID(payload))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
PARTITION BY RANGE (UNIX_TIMESTAMP(occurred_at)) (
	PARTITION p2026_11 VALUES LESS THAN (UNIX_TIMESTAMP('2026-12-01 00:00:00')),
	PARTITION p2026_12 VALUES LESS THAN (UNIX_TIMESTAMP('2027-01-01 00:00:00')),
	PARTITION p2027_01 VALUES LESS THAN (UNIX_TIMESTAMP('2027-02-01 00:00:00')),
	PARTITION pmax VALUES LESS THAN MAXVALUE
);

-- The head of each stream: locked and advanced in the transaction that inserts the stream's facts.
CREATE TABLE lc_stream_head (
	stream VARCHAR(48) NOT NULL,
	last_seq BIGINT NOT NULL,
	last_fact_id BIGINT NULL,
	PRIMARY KEY (stream),
	CONSTRAINT chk_head_seq CHECK (last_seq >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Exact coordinates, which lc_fact never holds: read only by the Intel Service and forensics, deleted at expiry.
CREATE TABLE lc_fact_precise (
	fact_id BIGINT NOT NULL,
	x INT NOT NULL,
	y INT NOT NULL,
	z INT NOT NULL,
	instance_id INT NOT NULL DEFAULT 0,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (fact_id),
	KEY idx_precise_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every capture the journal refused or lost: a gap is never silent.
CREATE TABLE lc_journal_gap (
	stream_or_type VARCHAR(48) NOT NULL,
	from_at TIMESTAMP(3) NOT NULL,
	to_at TIMESTAMP(3) NOT NULL,
	refused BIGINT NOT NULL,
	reason ENUM('QUEUE_FULL', 'STORE_DOWN', 'RESTART') NOT NULL,
	acknowledged_by VARCHAR(32) NULL,
	acknowledged_reason VARCHAR(128) NULL,
	PRIMARY KEY (stream_or_type, from_at),
	CONSTRAINT chk_gap_span CHECK (to_at >= from_at),
	CONSTRAINT chk_gap_refused CHECK (refused >= 0),
	CONSTRAINT chk_gap_ack CHECK ((acknowledged_by IS NULL) = (acknowledged_reason IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Telemetry: one row per non-empty (window, region, family, actor class), written once (spec §2.3).
CREATE TABLE lc_telemetry_bucket (
	window_start TIMESTAMP NOT NULL,
	window_len INT NOT NULL,
	region_id SMALLINT NOT NULL,
	family SMALLINT NOT NULL,
	actor_class TINYINT NOT NULL,
	events BIGINT UNSIGNED NOT NULL,
	sum_a BIGINT NOT NULL,
	sum_b BIGINT NOT NULL,
	unique_actors INT UNSIGNED NOT NULL,
	PRIMARY KEY (window_start, region_id, family, actor_class),
	CONSTRAINT chk_bucket_len CHECK (window_len > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_projector_checkpoint (
	projector VARCHAR(48) NOT NULL,
	stream VARCHAR(48) NOT NULL,
	last_seq BIGINT NOT NULL,
	reducer_ver SMALLINT NOT NULL,
	PRIMARY KEY (projector, stream),
	CONSTRAINT chk_checkpoint_seq CHECK (last_seq >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The registry of every fact type every module emits (spec §2.4).
CREATE TABLE lc_fact_type (
	type SMALLINT NOT NULL,
	name VARCHAR(64) NOT NULL,
	schema_ver SMALLINT NOT NULL,
	durability ENUM('DURABLE', 'TELEMETRY') NOT NULL,
	delivery ENUM('OUTBOX', 'CAPTURED') NOT NULL,
	sensitivity TINYINT NOT NULL,
	retention_days INT NULL,
	owner_module VARCHAR(16) NOT NULL,
	PRIMARY KEY (type),
	UNIQUE KEY uk_fact_type_name (name),
	CONSTRAINT chk_type_ver CHECK (schema_ver > 0),
	CONSTRAINT chk_type_sensitivity CHECK (sensitivity BETWEEN 0 AND 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
