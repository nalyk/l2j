-- SPEC-ASSET revision 16 (docs/spec/asset-service.md §5, §11.35): Asset.LifecycleEnded, written wherever a lifecycle ends
-- (the lifecycle writer's end, an operation's sink, the startup pass), registered as fact-journal.md §2.5 requires, after
-- V017's 1000-1024.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, retention_days, owner_module) VALUES (1025, 'Asset.LifecycleEnded', 1, 'DURABLE', 'OUTBOX', 'asset', 1, NULL, 'ASSET');
