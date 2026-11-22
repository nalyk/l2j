-- SPEC-ASSET (docs/spec/asset-service.md §2.6): one row per counted SQL statement, written in its own transaction, so a
-- commit whose acknowledgement is lost is resolved by that transaction's identity, never by quantities other writers
-- may have changed meanwhile. Deleted once the commit is known; the startup census clears what a crash left.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

CREATE TABLE lc_economy_statement (
  statement_id BINARY(16) NOT NULL,
  staged_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (statement_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
