-- SPEC-ASSET revision 16 (docs/spec/asset-service.md §2.2, §11.35): a lifecycle is a non-stackable item's provenance id.
-- Retail keeps no stack's object through its own moves, so no lifecycle is ever merged or split: one ends DESTROYED (its
-- object destroyed, or its custody row taken by a sink, ended_op that operation) or VANISHED (a start found its object in
-- no row). Nothing wrote a lifecycle before this migration, so no row holds a reason it removes.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

ALTER TABLE lc_item_lifecycle DROP CONSTRAINT chk_lifecycle_merge;
ALTER TABLE lc_item_lifecycle DROP FOREIGN KEY fk_lifecycle_parent;
ALTER TABLE lc_item_lifecycle DROP FOREIGN KEY fk_lifecycle_merged;
ALTER TABLE lc_item_lifecycle DROP COLUMN parent_lifecycle_id, DROP COLUMN merged_into;
ALTER TABLE lc_item_lifecycle MODIFY end_reason ENUM('DESTROYED', 'VANISHED') NULL;
ALTER TABLE lc_item_lifecycle ADD COLUMN ended_op BINARY(16) NULL AFTER end_reason, ADD COLUMN ended_run INT NULL AFTER ended_op;
-- Null-safe, as chk_lifecycle_merge was: a live lifecycle names no ending operation and no run it ended in.
ALTER TABLE lc_item_lifecycle ADD CONSTRAINT chk_lifecycle_ended_op CHECK ((ended_at IS NOT NULL) OR ((ended_op IS NULL) AND (ended_run IS NULL)));

-- One row per start while Living Chronicle runs, written by the startup pass before any id is handed out: within a run an
-- object id is never reused for a non-stackable item, so a lifecycle ended in this run (ended_run) means its object is gone.
CREATE TABLE lc_asset_run (
  run_id INT NOT NULL AUTO_INCREMENT,
  started_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
