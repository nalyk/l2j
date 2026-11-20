-- SPEC-ASSET (docs/spec/asset-service.md §5): the Asset Service's fact types, registered as fact-journal.md §2.5 requires.
-- Every one is durable and staged through the outbox in the operation's own transaction. Ids 1000-1024, in §5's order.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1000, 'Asset.CustodyOpened', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1001, 'Asset.EscrowFunded', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1002, 'Asset.EscrowReleased', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1003, 'Asset.FeeCharged', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1004, 'Asset.TreasuryCommitted', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1005, 'Asset.TreasuryReturned', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1006, 'Asset.Crafted', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1007, 'Asset.LootRouted', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1008, 'Asset.ParchmentIssued', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1009, 'Asset.SealIssued', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1010, 'Asset.ManifestRaided', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1011, 'Asset.CustodyMoved', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1012, 'Asset.Exchanged', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1013, 'Asset.DropRelocated', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1014, 'Asset.SinkRecycled', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1015, 'Asset.LifecycleTracked', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1016, 'Asset.Refunded', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1017, 'Asset.CustodyClosed', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1018, 'Asset.CustodyFrozen', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1019, 'Asset.AdminCorrection', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1020, 'Asset.InvariantViolated', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1021, 'Asset.ConservationBroken', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1022, 'Asset.MonitorRebased', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1023, 'Asset.MoveImbalance', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1024, 'Asset.RecoveryCompleted', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
