-- SPEC-FACT (docs/spec/fact-journal.md §2): durable facts, stream heads, precise locations, gaps, telemetry buckets,
-- projector checkpoints, and the fact type registry. Applied before the Asset Service's migration, which writes facts.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

-- Immutable: the game server's database user may only INSERT and SELECT it (spec §8), and nothing that must ever be
-- removed is written into it (spec §2.4). Partitioned by month. MariaDB requires every unique key of a partitioned table to include the partition column, and
-- partitioned InnoDB tables take part in no foreign key: a stream's order is kept by lc_stream_head (spec §2.1), and a
-- fact's type and references are checked by the writer and by the invariants, not by keys. The MAXVALUE partition takes
-- whatever no monthly partition does, so an insert never fails for want of one; the purge job splits it ahead of time.
CREATE TABLE lc_fact (
	id BIGINT NOT NULL AUTO_INCREMENT,
	occurred_at TIMESTAMP(3) NOT NULL,
	stream VARCHAR(48) NOT NULL,
	epoch INT NOT NULL,
	stream_seq BIGINT NOT NULL,
	type SMALLINT NOT NULL,
	schema_ver SMALLINT NOT NULL,
	logical_time BIGINT NOT NULL,
	actor_snapshot JSON NULL,
	region_id SMALLINT NOT NULL,
	zone_id INT NULL,
	instance_id INT NOT NULL DEFAULT 0,
	sensitivity TINYINT NOT NULL,
	payload JSON NOT NULL,
	op_id BINARY(16) NULL,
	batch_id BIGINT NULL,
	PRIMARY KEY (id, occurred_at),
	KEY idx_fact_stream (stream, epoch, stream_seq),
	KEY idx_fact_type_time (type, occurred_at),
	KEY idx_fact_op (op_id),
	KEY idx_fact_batch (batch_id),
	CONSTRAINT chk_fact_seq CHECK (stream_seq > 0),
	CONSTRAINT chk_fact_epoch CHECK (epoch >= 0),
	CONSTRAINT chk_fact_sensitivity CHECK (sensitivity BETWEEN 0 AND 3),
	CONSTRAINT chk_fact_payload CHECK (JSON_VALID(payload))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
PARTITION BY RANGE (UNIX_TIMESTAMP(occurred_at)) (
	PARTITION p2026_11 VALUES LESS THAN (UNIX_TIMESTAMP('2026-12-01 00:00:00')),
	PARTITION p2026_12 VALUES LESS THAN (UNIX_TIMESTAMP('2027-01-01 00:00:00')),
	PARTITION p2027_01 VALUES LESS THAN (UNIX_TIMESTAMP('2027-02-01 00:00:00')),
	PARTITION pmax VALUES LESS THAN MAXVALUE
);

-- The head of each stream: an outbox stream's is locked and advanced by the transaction that writes its facts; a captured
-- stream's is advanced by the batch writer to the highest number it committed in the epoch (spec §3).
CREATE TABLE lc_stream_head (
	stream VARCHAR(48) NOT NULL,
	epoch INT NOT NULL,
	last_seq BIGINT NOT NULL,
	last_logical_time BIGINT NOT NULL,
	clean_end BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (stream),
	CONSTRAINT chk_head_seq CHECK (last_seq >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- One run of the capture side; clean only when the writer drained the queue at shutdown.
CREATE TABLE lc_journal_epoch (
	epoch INT NOT NULL AUTO_INCREMENT,
	started_at TIMESTAMP(3) NOT NULL,
	ended_at TIMESTAMP(3) NULL,
	clean BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (epoch),
	CONSTRAINT chk_epoch_clean CHECK ((NOT clean) OR (ended_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every batch the writer committed, and every heartbeat with its frontier: a batch id is committed once (spec §3.2).
CREATE TABLE lc_journal_batch (
	batch_id BIGINT NOT NULL,
	epoch INT NOT NULL,
	committed_at TIMESTAMP(3) NOT NULL,
	facts INT NOT NULL,
	frontier_logical_time BIGINT NOT NULL,
	PRIMARY KEY (batch_id),
	KEY idx_batch_epoch (epoch, frontier_logical_time),
	CONSTRAINT chk_batch_facts CHECK (facts >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Expiring data other than coordinates, one row per field, which lc_fact never holds (spec §2.4).
CREATE TABLE lc_fact_expiring (
	fact_id BIGINT NOT NULL,
	field VARCHAR(32) NOT NULL,
	value JSON NOT NULL,
	expires_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (fact_id, field),
	KEY idx_expiring_expiry (expires_at),
	CONSTRAINT chk_expiring_value CHECK (JSON_VALID(value))
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

-- Every hole in a captured stream, by its numbers: a gap is never silent. A RESTART gap has an open end and an unknown
-- count (NULL), because what was lost with the queue cannot be known.
CREATE TABLE lc_journal_gap (
	stream VARCHAR(48) NOT NULL,
	epoch INT NOT NULL,
	from_seq BIGINT NOT NULL,
	to_seq BIGINT NULL,
	refused BIGINT NULL,
	reason ENUM('QUEUE_FULL', 'STORE_DOWN', 'RESTART') NOT NULL,
	acknowledged_by VARCHAR(32) NULL,
	acknowledged_reason VARCHAR(128) NULL,
	PRIMARY KEY (stream, epoch, from_seq),
	CONSTRAINT chk_gap_span CHECK ((to_seq IS NULL) OR (to_seq >= from_seq)),
	CONSTRAINT chk_gap_refused CHECK ((refused IS NULL) OR (refused >= 0)),
	CONSTRAINT chk_gap_restart CHECK ((reason = 'RESTART') OR (to_seq IS NOT NULL AND refused IS NOT NULL)),
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
	epoch INT NOT NULL,
	last_seq BIGINT NOT NULL,
	reducer_ver SMALLINT NOT NULL,
	config_ver INT NOT NULL,
	PRIMARY KEY (projector, stream),
	CONSTRAINT chk_checkpoint_seq CHECK (last_seq >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A projector's decided effects, written with its checkpoint and executed exactly once by the dispatcher (spec §4.4).
CREATE TABLE lc_effect_outbox (
	application_id BINARY(16) NOT NULL,
	projector VARCHAR(48) NOT NULL,
	fact_id BIGINT NOT NULL,
	kind VARCHAR(32) NOT NULL,
	payload JSON NOT NULL,
	state ENUM('PENDING', 'DONE', 'FAILED') NOT NULL,
	attempts INT NOT NULL DEFAULT 0,
	created_at TIMESTAMP(3) NOT NULL,
	done_at TIMESTAMP(3) NULL,
	PRIMARY KEY (application_id),
	KEY idx_effect_pending (state, created_at),
	CONSTRAINT chk_effect_done CHECK ((state = 'PENDING') = (done_at IS NULL)),
	CONSTRAINT chk_effect_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every configuration a reducer may read, bound to facts by the config stream (spec §4.1).
CREATE TABLE lc_config_version (
	config_ver INT NOT NULL AUTO_INCREMENT,
	module VARCHAR(16) NOT NULL,
	changed_at TIMESTAMP(3) NOT NULL,
	content JSON NOT NULL,
	PRIMARY KEY (config_ver),
	CONSTRAINT chk_config_content CHECK (JSON_VALID(content))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Copies of derived state with their checkpoints, from which replay starts once older facts may be dropped (spec §4.5).
CREATE TABLE lc_replay_snapshot (
	projector VARCHAR(48) NOT NULL,
	taken_at TIMESTAMP(3) NOT NULL,
	checkpoint JSON NOT NULL,
	store_ref VARCHAR(128) NOT NULL,
	PRIMARY KEY (projector, taken_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The registry of every fact type every module emits (spec §2.4).
CREATE TABLE lc_fact_type (
	type SMALLINT NOT NULL,
	name VARCHAR(64) NOT NULL,
	schema_ver SMALLINT NOT NULL,
	durability ENUM('DURABLE', 'TELEMETRY') NOT NULL,
	delivery ENUM('OUTBOX', 'CAPTURED') NOT NULL,
	stream_family VARCHAR(32) NULL,
	sensitivity TINYINT NOT NULL,
	retention_days INT NULL,
	owner_module VARCHAR(16) NOT NULL,
	PRIMARY KEY (type),
	UNIQUE KEY uk_fact_type_name (name),
	CONSTRAINT chk_type_ver CHECK (schema_ver > 0),
	CONSTRAINT chk_type_sensitivity CHECK (sensitivity BETWEEN 0 AND 3),
	CONSTRAINT chk_type_family CHECK ((delivery = 'CAPTURED') = (stream_family IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
