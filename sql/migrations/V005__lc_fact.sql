-- SPEC-FACT (docs/spec/fact-journal.md §2): one log with one sequencer, the outbox staging table, stream heads, epochs and
-- batches, gaps, expiring data, telemetry buckets, projector checkpoints and effects, configuration versions, the fact
-- type registry and replay snapshots. Applied before the Asset Service's migration, which stages facts.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

-- The log. Only the journal's writer inserts it, one session, batches in id order (spec §2.1); the game server's
-- database user may only INSERT and SELECT it (spec §8), and nothing that must ever be removed is written into it
-- (spec §2.4). Partitioned by month: MariaDB allows no unique key on a partitioned table without the partition column and
-- no foreign keys, so the log's order is the writer's, checked by the writer and the invariants. The MAXVALUE partition
-- takes whatever no monthly partition does, so an insert never fails for want of one; the maintenance job splits it ahead.
CREATE TABLE lc_fact (
	id BIGINT NOT NULL AUTO_INCREMENT,
	occurred_at TIMESTAMP(3) NOT NULL,
	stream VARCHAR(48) NOT NULL,
	stream_seq BIGINT NULL,
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
	config_ver INT NOT NULL,
	captured_at TIMESTAMP(3) NOT NULL,
	source_outbox_id BIGINT NULL,
	batch_id BIGINT NOT NULL,
	PRIMARY KEY (id, occurred_at),
	KEY idx_fact_source (source_outbox_id),
	KEY idx_fact_stream (stream, stream_seq),
	KEY idx_fact_type_time (type, occurred_at),
	KEY idx_fact_op (op_id),
	KEY idx_fact_batch (batch_id),
	CONSTRAINT chk_fact_seq CHECK ((stream_seq IS NULL) OR (stream_seq > 0)),
	CONSTRAINT chk_fact_sensitivity CHECK (sensitivity BETWEEN 0 AND 3),
	CONSTRAINT chk_fact_payload CHECK (JSON_VALID(payload))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
PARTITION BY RANGE (UNIX_TIMESTAMP(occurred_at)) (
	PARTITION p2026_11 VALUES LESS THAN (UNIX_TIMESTAMP('2026-12-01 00:00:00')),
	PARTITION p2026_12 VALUES LESS THAN (UNIX_TIMESTAMP('2027-01-01 00:00:00')),
	PARTITION p2027_01 VALUES LESS THAN (UNIX_TIMESTAMP('2027-02-01 00:00:00')),
	PARTITION pmax VALUES LESS THAN MAXVALUE
);

-- Outbox facts, staged by a module's own transaction with the change they record, relayed into the log by the writer
-- (inserted there and deleted here in one transaction): exactly once (spec §3.1).
CREATE TABLE lc_fact_outbox (
	outbox_id BIGINT NOT NULL AUTO_INCREMENT,
	staged_at TIMESTAMP(3) NOT NULL,
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
	payload JSON NOT NULL,
	op_id BINARY(16) NULL,
	config_ver INT NOT NULL,
	PRIMARY KEY (outbox_id),
	UNIQUE KEY uk_outbox_stream_seq (stream, stream_seq),
	CONSTRAINT chk_outbox_seq CHECK (stream_seq > 0),
	CONSTRAINT chk_outbox_sensitivity CHECK (sensitivity BETWEEN 0 AND 3),
	CONSTRAINT chk_outbox_payload CHECK (JSON_VALID(payload))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The head of each outbox stream: locked and advanced by the transaction that stages the stream's facts.
CREATE TABLE lc_stream_head (
	stream VARCHAR(48) NOT NULL,
	last_seq BIGINT NOT NULL,
	PRIMARY KEY (stream),
	CONSTRAINT chk_head_seq CHECK (last_seq >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- One run of the writer; clean only when it drained the ring and the outbox at shutdown.
CREATE TABLE lc_journal_epoch (
	epoch INT NOT NULL AUTO_INCREMENT,
	started_at TIMESTAMP(3) NOT NULL,
	ended_at TIMESTAMP(3) NULL,
	clean BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (epoch),
	CONSTRAINT chk_epoch_clean CHECK ((NOT clean) OR (ended_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every batch the writer committed, heartbeats included: a batch id is committed once; a closed phase carries its frontier.
CREATE TABLE lc_journal_batch (
	batch_id BIGINT NOT NULL,
	epoch INT NOT NULL,
	committed_at TIMESTAMP(3) NOT NULL,
	first_id BIGINT NULL,
	last_id BIGINT NULL,
	relayed INT NOT NULL,
	captured INT NOT NULL,
	phase BIGINT NULL,
	frontier_logical_time BIGINT NULL,
	PRIMARY KEY (batch_id),
	KEY idx_batch_epoch (epoch, batch_id),
	CONSTRAINT chk_batch_ids CHECK ((first_id IS NULL) = (last_id IS NULL) AND ((first_id IS NULL) OR (last_id >= first_id))),
	CONSTRAINT chk_batch_counts CHECK ((relayed >= 0) AND (captured >= 0)),
	CONSTRAINT chk_batch_phase CHECK ((phase IS NULL) = (frontier_logical_time IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every loss of captured facts, as the Journal.Gap fact the writer put in the log (fact_id). A STALLED or RESTART gap
-- cannot know its count (NULL).
CREATE TABLE lc_journal_gap (
	fact_id BIGINT NOT NULL,
	epoch INT NOT NULL,
	phase BIGINT NULL,
	family VARCHAR(32) NOT NULL,
	refused BIGINT NULL,
	from_logical_time BIGINT NOT NULL,
	to_logical_time BIGINT NOT NULL,
	reason ENUM('QUEUE_FULL', 'STALLED', 'RESTART') NOT NULL,
	acknowledged_by VARCHAR(32) NULL,
	acknowledged_reason VARCHAR(128) NULL,
	PRIMARY KEY (fact_id),
	CONSTRAINT chk_gap_span CHECK (to_logical_time >= from_logical_time),
	CONSTRAINT chk_gap_refused CHECK ((reason = 'QUEUE_FULL') = (refused IS NOT NULL)),
	CONSTRAINT chk_gap_ack CHECK ((acknowledged_by IS NULL) = (acknowledged_reason IS NULL))
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

-- Telemetry: one row per non-empty (window, region, family, actor class), written once; stalled when its phase did not drain.
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
	stalled BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (window_start, region_id, family, actor_class),
	CONSTRAINT chk_bucket_len CHECK (window_len > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_projector_checkpoint (
	projector VARCHAR(48) NOT NULL,
	last_fact_id BIGINT NOT NULL,
	reducer_ver SMALLINT NOT NULL,
	config_ver INT NOT NULL,
	PRIMARY KEY (projector),
	CONSTRAINT chk_checkpoint_id CHECK (last_fact_id >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A projector's decided effects, written with its checkpoint and executed in (fact id, effect index) order by its
-- dispatcher, exactly once (spec §4.4).
CREATE TABLE lc_effect_outbox (
	application_id BINARY(16) NOT NULL,
	projector VARCHAR(48) NOT NULL,
	fact_id BIGINT NOT NULL,
	effect_index SMALLINT NOT NULL,
	kind VARCHAR(32) NOT NULL,
	payload JSON NOT NULL,
	state ENUM('PENDING', 'DONE', 'FAILED') NOT NULL,
	attempts INT NOT NULL DEFAULT 0,
	created_at TIMESTAMP(3) NOT NULL,
	done_at TIMESTAMP(3) NULL,
	PRIMARY KEY (application_id),
	UNIQUE KEY uk_effect_position (projector, fact_id, effect_index),
	KEY idx_effect_pending (projector, state, fact_id, effect_index),
	CONSTRAINT chk_effect_done CHECK ((state = 'PENDING') = (done_at IS NULL)),
	CONSTRAINT chk_effect_attempts CHECK (attempts >= 0),
	CONSTRAINT chk_effect_index CHECK (effect_index >= 0)
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

-- The registry of every fact type every module emits (spec §2.5).
CREATE TABLE lc_fact_type (
	type SMALLINT NOT NULL,
	name VARCHAR(64) NOT NULL,
	schema_ver SMALLINT NOT NULL,
	durability ENUM('DURABLE', 'TELEMETRY') NOT NULL,
	delivery ENUM('OUTBOX', 'CAPTURED') NOT NULL,
	family VARCHAR(32) NOT NULL,
	sensitivity TINYINT NOT NULL,
	retention_days INT NULL,
	owner_module VARCHAR(16) NOT NULL,
	PRIMARY KEY (type),
	UNIQUE KEY uk_fact_type_name (name),
	CONSTRAINT chk_type_ver CHECK (schema_ver > 0),
	CONSTRAINT chk_type_sensitivity CHECK (sensitivity BETWEEN 0 AND 3),
	CONSTRAINT chk_type_retention CHECK ((retention_days IS NULL) OR (retention_days > 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Copies of derived state, by the log position they cover; replay starts from the newest usable one (spec §4.5).
CREATE TABLE lc_replay_snapshot (
	projector VARCHAR(48) NOT NULL,
	last_fact_id BIGINT NOT NULL,
	taken_at TIMESTAMP(3) NOT NULL,
	store_ref VARCHAR(128) NOT NULL,
	PRIMARY KEY (projector, last_fact_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Per family, the highest fact id ever dropped with a partition: a projector's snapshot is usable only at or beyond the
-- watermarks of the families it consumes (spec §4.5). Advanced, and committed, before the partition is dropped.
CREATE TABLE lc_journal_retention (
	family VARCHAR(32) NOT NULL,
	max_dropped_fact_id BIGINT NOT NULL,
	PRIMARY KEY (family),
	CONSTRAINT chk_retention_id CHECK (max_dropped_fact_id >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
