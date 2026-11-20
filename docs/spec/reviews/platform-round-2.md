1. **HIGH — Previous 1: FIXED.** [§4.4](/home/nalyk/gits/l2j/docs/spec/platform.md:175) accurately describes the permissive upstream loader and names the manifest, refusal patch and ledger entry as future work.

2. **HIGH — Previous 2: FIXED.** [§4.4 and AT-PLT-04](/home/nalyk/gits/l2j/docs/spec/platform.md:180) define required regions, test both consumers against differing files, and require geometry probes with independent expectations.

3. **HIGH — Previous 3: PARTLY.** [§4.3](/home/nalyk/gits/l2j/docs/spec/platform.md:135) adds the missing identities and positive grant checks, but the grants still conflict with upstream operations and leave module permissions unresolved; findings 15–16 below.

4. **HIGH — Previous 4: FIXED.** [§4.3](/home/nalyk/gits/l2j/docs/spec/platform.md:149) correctly assigns the narrator credential to the web worker and gives the LLM no database identity.

5. **HIGH — Previous 5: PARTLY.** [§4.2](/home/nalyk/gits/l2j/docs/spec/platform.md:70) correctly separates upstream runtime libraries, test infrastructure and new dependencies; AT-PLT-01’s package scanner still rejects existing code; finding 17.

6. **MEDIUM — Previous 6: FIXED.** [§4.2](/home/nalyk/gits/l2j/docs/spec/platform.md:85) explicitly limits equality to compiled classes, acknowledges manifest timestamps, and requires two clean builds.

7. **MEDIUM — Previous 7: FIXED.** [§4.2](/home/nalyk/gits/l2j/docs/spec/platform.md:90) honestly trusts the Docker daemon and excludes substituted local images; fresh merge-gate assembly is explicitly an implementation obligation.

8. **HIGH — Previous 8: FIXED.** [§4.5](/home/nalyk/gits/l2j/docs/spec/platform.md:197) supplies numerical workload minima, durations, background-load checks and metric bands, including combat, movement, visibility and contracts during the siege. These are declared acceptance targets, not claimed measurements.

9. **HIGH — Previous 9: FIXED.** [§4.3 and AT-PLT-03](/home/nalyk/gits/l2j/docs/spec/platform.md:128) require private networking, no published ports, restricted bindings and LAN-side checks. Compose remains clearly identified as future work.

10. **HIGH — Previous 10: PARTLY.** [§4.4 and AT-PLT-05](/home/nalyk/gits/l2j/docs/spec/platform.md:159) add provenance, item/string checks and human validation, but the rebuild oracle and Community Board fixture contain contradictions; finding 22.

11. **HIGH — Previous 11: PARTLY.** [§4.2](/home/nalyk/gits/l2j/docs/spec/platform.md:97) accurately separates today’s gate from future checks; [§4.6](/home/nalyk/gits/l2j/docs/spec/platform.md:250) still labels nonexistent static scans “Checked today”; finding 21.

12. **MEDIUM — Previous 12: FIXED.** [§4.2](/home/nalyk/gits/l2j/docs/spec/platform.md:104) acknowledges the current skip and filename-only check, then names gate enforcement, field validation and proof-class checks as future work.

13. **HIGH — Previous 13: PARTLY.** [§4.7](/home/nalyk/gits/l2j/docs/spec/platform.md:268) adds retention, expiry purge and binlog recovery, but its retention window breaks the recovery chain and its purge uses the wrong column; findings 18–19.

14. **MEDIUM — Previous 14: FIXED.** [AT-PLT-07 and AT-PLT-09](/home/nalyk/gits/l2j/docs/spec/platform.md:344) now supply concrete authoritative operations, outcome comparisons and feature signatures.

15. **HIGH — The game grants break ordinary Olympiad maintenance.** [§4.3](/home/nalyk/gits/l2j/docs/spec/platform.md:144) permits stock-table CRUD while expressly denying `DROP`/`TRUNCATE`. Existing, unchanged upstream [Olympiad.java](/home/nalyk/gits/l2j/java/org/l2jmobius/gameserver/mechanics/olympiad/Olympiad.java:77) uses `TRUNCATE` for both nobles tables, executed at [monthly processing](/home/nalyk/gits/l2j/java/org/l2jmobius/gameserver/mechanics/olympiad/Olympiad.java:1039) and [reset](/home/nalyk/gits/l2j/java/org/l2jmobius/gameserver/mechanics/olympiad/Olympiad.java:1391). The proposed account cannot perform these operations. Name the required compatibility patches or revise the authority model; grant probes alone do not exercise this behavior.

16. **HIGH — The purported exact module grants still have no determinate privilege set.** [§4.3](/home/nalyk/gits/l2j/docs/spec/platform.md:137) delegates permissions to owning specs, but Intel specifies behavior and restrictions rather than grants for `lc_intel`. Its evidence table must be unreadable by the game user ([AT-INTEL-01](/home/nalyk/gits/l2j/docs/spec/intel-service.md:393)), while Platform’s fallback assigns tables without a named user to `lc_game`. Neither fallback states permitted operations. Likewise, `lc_maint` lacks explicit access to retention watermarks, replay leases, checkpoints, telemetry buckets and the expiry registry, although [Fact Journal maintenance](/home/nalyk/gits/l2j/docs/spec/fact-journal.md:338) reads, locks and modifies these. “What … needs” is not an exact grant list. AT-PLT-02 cannot derive a unique oracle from these rules.

17. **HIGH — The new dependency scanner rejects JDK dependencies already used by upstream.** [AT-PLT-01](/home/nalyk/gits/l2j/docs/spec/platform.md:338) rejects packages outside its listed prefixes. Existing [IXmlReader.java](/home/nalyk/gits/l2j/java/org/l2jmobius/commons/util/IXmlReader.java:40) references `org.w3c.dom` and `org.xml.sax`; numerous game classes do likewise. These are JDK APIs excluded by the proposed allowlist. Classify JDK dependencies by module ownership or explicitly include their actual packages.

18. **HIGH — Backup retention destroys the chain needed for the promised recovery window.** [§4.7](/home/nalyk/gits/l2j/docs/spec/platform.md:274) deletes binlogs after the registry’s shortest lifetime and claims the default is 72 hours. The existing [registry migration](/home/nalyk/gits/l2j/sql/migrations/V008__lc_intel.sql:208) registers `lc_signal_presence` at **one hour**, and other tables below 72 hours. With nightly snapshots, earlier binlogs needed to advance the snapshot can disappear long before the next snapshot. Retaining only the most recent hour cannot recover that hour from a snapshot many hours old. The recovery window needs a retained continuous chain from an available baseline.

19. **HIGH — Restore purging does not follow the expiry registry.** [§4.7](/home/nalyk/gits/l2j/docs/spec/platform.md:279) and [AT-PLT-08](/home/nalyk/gits/l2j/docs/spec/platform.md:345) assume every registered table expires through `expires_at`. The registry explicitly stores `expires_column`; [the existing migration](/home/nalyk/gits/l2j/sql/migrations/V008__lc_intel.sql:208) registers `lc_witness_suppression` using `suppressed_until`. The specified purge either fails on that table or leaves expired suppression rows. Require the registered column and test this existing case.

20. **MEDIUM — Narrator grants contradict the existing role definition.** [§4.3](/home/nalyk/gits/l2j/docs/spec/platform.md:150) describes the narrator’s writes as `INSERT` only, while saying it holds the Chronicle role “and nothing else.” Existing [V016](/home/nalyk/gits/l2j/sql/migrations/V016__lc_chronicle.sql:296) grants that role `SELECT, INSERT, UPDATE` on `lc_chr_prose`; Chronicle §2.1 permits writing without narrowing this to insertion. The exact-grants oracle must choose one contract and reconcile the migration.

21. **MEDIUM — The doctrine map still overstates existing coverage.** [§4.6](/home/nalyk/gits/l2j/docs/spec/platform.md:250) places AT-SIG-09 and AT-MEM-09 static scans under “Checked today.” Neither exists in the inspected source, tests or tools; the corresponding schema tests cover different acceptance cases. Move these to future work. Also, §12’s claim that `TR-engineering-doctrine` still cites PLT-R01 alone is stale: the [actual row](/home/nalyk/gits/l2j/docs/blueprint/traceability.csv:193) cites PLT-R01, PLT-R02 and PLT-R06.

22. **MEDIUM — The client oracle has two internally inconsistent success conditions.** [§4.4](/home/nalyk/gits/l2j/docs/spec/platform.md:164) permits decrypted-table equality when encryption is nondeterministic, but PLT-R05 and [AT-PLT-05](/home/nalyk/gits/l2j/docs/spec/platform.md:342) still require identical archive hashes without that exception. The same test requires rendering “three full chunks”: three 16,250-character chunks total 48,750 characters, which existing [HtmlUtil.java](/home/nalyk/gits/l2j/java/org/l2jmobius/gameserver/util/HtmlUtil.java:442) rejects. Define one consistent rebuild comparison, and test the largest accepted Community Board payload plus rejection at the limit.

The manifest/refusal patch, geometry probes, deployment configuration, benchmarks, gate extensions and GPU comparison are legitimate named implementation obligations. Their absence alone does not block specification review. The contradictions above do.

**NO SIGN-OFF**
