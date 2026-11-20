1. **HIGH — Previous 1: PARTLY.** [citizens.md:265](/home/nalyk/gits/l2j/docs/spec/citizens.md:265) separates telemetry caps from observation inputs, but passes their predicate into the policy; `RETUNE` also explicitly changes price aggressiveness ([line 460](/home/nalyk/gits/l2j/docs/spec/citizens.md:460)), conflicting with the promised unchanged ranking. See 16.

2. **HIGH — Previous 2: FIXED.** [citizens.md:282](/home/nalyk/gits/l2j/docs/spec/citizens.md:282) makes the steward a citizen deciding from its own observations; [line 299](/home/nalyk/gits/l2j/docs/spec/citizens.md:299) routes house grudges through received channel messages. The steward and channel writer remain implementation obligations.

3. **HIGH — Previous 3: PARTLY.** [V001__cit_runner.sql:210](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:210), [line 218](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:218) and [line 224](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:224) now check referenced ownership, existence and time, and reject UPDATE. Permitted deletion and reinsertion still replace referenced evidence; identity bindings remain mutable. See 19–20.

4. **HIGH — Previous 4: FIXED at specification level.** [citizens.md:259](/home/nalyk/gits/l2j/docs/spec/citizens.md:259) requires an independently recorded read set and restricted policy dependencies; [AT-CIT-03](/home/nalyk/gits/l2j/docs/spec/citizens.md:570) adds paired replay and a planted leaking policy. These are named implementation obligations, not existing passing tests.

5. **MEDIUM — Previous 5: PARTLY.** [citizens.md:268](/home/nalyk/gits/l2j/docs/spec/citizens.md:268) preserves observation snapshots, persona state and configuration version, and the observation key is corrected. Effective constraint inputs are still absent from the promised self-contained replay. See 17.

6. **HIGH — Previous 6: FIXED.** [citizens.md:184](/home/nalyk/gits/l2j/docs/spec/citizens.md:184) correctly preserves both envelopes and bookmark subcommands, matching [GamePacketHandler.java:60](/home/nalyk/gits/l2j/java/org/l2jmobius/gameserver/network/GamePacketHandler.java:60). The revised null-supplier counts match the enums.

7. **HIGH — Previous 7: PARTLY.** [citizens.md:334](/home/nalyk/gits/l2j/docs/spec/citizens.md:334) adds durable pending credentials, conservative failures and crash recovery. Its proof still loses unresolved server work when a later “solo” success resets the mirror. See 18.

8. **HIGH — Previous 8: FIXED by narrowing the claim.** [citizens.md:316](/home/nalyk/gits/l2j/docs/spec/citizens.md:316) states an absolute admission rate and acknowledges the absence of human reservations; [AT-CIT-02](/home/nalyk/gits/l2j/docs/spec/citizens.md:569) supplies a baseline latency/success oracle. This establishes a measured workload guarantee, not universal human availability.

9. **HIGH — Previous 9: PARTLY.** [citizens.md:156](/home/nalyk/gits/l2j/docs/spec/citizens.md:156) defines durable concurrent slots and [line 305](/home/nalyk/gits/l2j/docs/spec/citizens.md:305) adds versioned identity import. Mutable character bindings and mutable limits still invalidate the asserted schema guarantees. See 20.

10. **MEDIUM — Previous 10: PARTLY.** [citizens.md:363](/home/nalyk/gits/l2j/docs/spec/citizens.md:363) adds answer-based spacing and server flood logging; the conditional shout citation is corrected. The no-answer fallback remains unsafe, and the category map cannot represent all checked categories as written. See 21.

11. **HIGH — Previous 11: PARTLY.** [citizens.md:454](/home/nalyk/gits/l2j/docs/spec/citizens.md:454) adds measurements, windows and attribution, and explicitly calls stockpile accounting an estimate. Principal attribution, cap calculation and durable farming-budget enforcement remain incomplete. See 22–23.

12. **HIGH — Previous 12: FIXED by withdrawing the unsupported guarantee.** [citizens.md:464](/home/nalyk/gits/l2j/docs/spec/citizens.md:464) accurately distinguishes exact postings/custody, clean-restart detection and crash rebases, matching [asset-service.md:174](/home/nalyk/gits/l2j/docs/spec/asset-service.md:174). [Genesis conservation](/home/nalyk/gits/l2j/docs/spec/citizens.md:487) explicitly requires reviewed rebases rather than claiming uninterrupted per-principal conservation.

13. **HIGH — Previous 13: PARTLY.** [RunnerSchemaTest.java:62](/home/nalyk/gits/l2j/test/java/org/l2jmobius/gameserver/livingchronicle/citizens/RunnerSchemaTest.java:62) now creates a separate runner database and tests both directions against the stock game/login schema. Deployment grants remain named obligations; several denial assertions do not establish their claimed cause. See 25.

14. **HIGH — Previous 14: PARTLY.** [citizens.md:400](/home/nalyk/gits/l2j/docs/spec/citizens.md:400) introduces policy-selected acts and fixed slot substitution, and honestly acknowledges paraphrase risk. However, Walker validation precedes substitution, allowing the final text to trigger punishment. See 24.

15. **MEDIUM — Previous 15: PARTLY.** [citizens.md:568](/home/nalyk/gits/l2j/docs/spec/citizens.md:568)–[576](/home/nalyk/gits/l2j/docs/spec/citizens.md:576) add workflow coverage, counter rules, scenario bands, Genesis definitions, human thresholds, punishment compliance and channel bounds. The manifest and runtime tests remain implementation obligations; several new oracles still admit false passes or lack an evidence path. See 25–26.

16. **HIGH — Governor isolation does not enforce “constraints only mask.”** Where: [citizens.md:256](/home/nalyk/gits/l2j/docs/spec/citizens.md:256), [265](/home/nalyk/gits/l2j/docs/spec/citizens.md:265), [460](/home/nalyk/gits/l2j/docs/spec/citizens.md:460), [570](/home/nalyk/gits/l2j/docs/spec/citizens.md:570).

    An opaque `allows(choice)` still supplies information: a policy can query it and use the answers to change utilities or reorder feasible choices. The dependency scan cannot detect that because the predicate is an expressly permitted argument. AT-CIT-03(d) requires only a choice the uncapped ranking “allowed,” which does not require selecting its highest-ranked feasible choice.

    `RETUNE` compounds this by explicitly permitting lower price aggressiveness, a policy change rather than merely an option mask. Compute the observation-only ranking before applying constraints, specify the selection rule over that ranking, and distinguish policy-version changes from feasibility caps.

17. **HIGH — A retained decision cannot replay from its row alone.** Where: [citizens.md:268](/home/nalyk/gits/l2j/docs/spec/citizens.md:268); [V001__cit_runner.sql:153](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:153).

    `decide` receives constraints, but the row stores only cap IDs. Its snapshot contains observations, not cap contents, evaluated feasibility results or the consumption/reservation state needed to apply a daily minute cap. Replaying the listed snapshot, persona state, versions and RNG key therefore does not reconstruct the function’s arguments.

    Keeping cap rows forever helps lookup but does not meet the stated “from its own row” contract or preserve historical budget consumption. AT-CIT-03(c) needs to replay after observation retention and later cap/budget changes.

18. **HIGH — A timed-out credential breaks the login guard’s upper-bound proof.** Where: [citizens.md:132](/home/nalyk/gits/l2j/docs/spec/citizens.md:132), [334](/home/nalyk/gits/l2j/docs/spec/citizens.md:334); [LoginController.java:220](/home/nalyk/gits/l2j/java/org/l2jmobius/loginserver/controller/LoginController.java:220), [237](/home/nalyk/gits/l2j/java/org/l2jmobius/loginserver/controller/LoginController.java:237); [PacketExecutor.java:81](/home/nalyk/gits/l2j/java/org/l2jmobius/commons/network/packet/PacketExecutor.java:81).

    A request can still be executing on the server after its runner deadline. The runner moves it from `A` into `F`, then permits a new apparently solo credential. If that correct credential resets the server count before the older request finally records its failure, resetting runner `F` discards the outstanding uncertainty. The late failure is then absent from both `F` and `A`; four newly admitted failures can reach the server’s fifth failure while the database inequality remains satisfied.

    Closing the client connection supplies no cancellation barrier for the running authentication method. The SQL reset trigger at [line 228](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:228) also recognizes only `in_flight: 1 → 0`, not whether that request was historically solo. AT-CIT-02 omits delayed processing after timeout/restart. Unknown server outcomes must remain covered until a proven barrier, such as the specified login-server restart.

19. **HIGH — “Immutable” referenced evidence can be deleted and replaced.** Where: [citizens.md:100](/home/nalyk/gits/l2j/docs/spec/citizens.md:100); [V001__cit_runner.sql:72](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:72), [118](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:118), [212](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:212).

    The runner has INSERT and DELETE on observations, messages, source links and decisions. Nothing limits deletion to expired rows or prevents explicit reuse of a deleted ID. It can delete a referenced observation or message and insert different content under the same ID; existing dependants are not revalidated. UPDATE refusal therefore does not establish the claimed immutability.

    The source trigger also permits a summary to cite itself, because it accepts equal timestamps and never requires the source to be RAW ([line 214](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:214)). Thus even the promised summary-to-raw mapping is not enforced. Retention needs non-reusable identities and protected deletion semantics; summary sources need their stated type and acyclicity rules.

20. **HIGH — Participation ownership and identity guards can be bypassed through character updates.** Where: [V001__cit_runner.sql:34](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:34), [206](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:206), [230](/home/nalyk/gits/l2j/sql/runner/V001__cit_runner.sql:230); [citizens.md:102](/home/nalyk/gits/l2j/docs/spec/citizens.md:102).

    After reserving a slot for principal 1’s character, an authorized `UPDATE cit_character SET account_id = <principal-2-account>` succeeds when the ordinary foreign/unique keys permit it. The slot now holds another principal’s character without invoking either the account principal-change guard or participation INSERT trigger. Actor Identity explicitly freezes character bindings ([actor-identity.md:56](/home/nalyk/gits/l2j/docs/spec/actor-identity.md:56)); the runner mirror does not.

    Similarly, lowering `cit_participation_limit.max_slots` below an existing slot succeeds, contradicting “a slot number never exceeds” its limit. Freeze character identity/binding columns and define safe limit reductions. Add direct and concurrent cases to AT-CIT-00.

21. **HIGH — Flood pacing still permits unaccounted checks and unsafe receipt spacing.** Where: [citizens.md:360](/home/nalyk/gits/l2j/docs/spec/citizens.md:360).

    A packet does not necessarily map to one category. `UseItem` checks use-item protection ([UseItem.java:83](/home/nalyk/gits/l2j/java/org/l2jmobius/gameserver/network/clientpackets/UseItem.java:83)), then a summon-item handler checks pet-summon protection ([SummonItems.java:47](/home/nalyk/gits/l2j/dist/game/data/scripts/handlers/items/SummonItems.java:47)). Bypasses likewise reach additional auction/subclass checks. The map needs all applicable checks, including argument-dependent handler paths.

    The assertion that every protected operation answers is also false: `RequestInfoItemAuction` consumes its protector and returns silently when no auction exists ([line 47](/home/nalyk/gits/l2j/java/org/l2jmobius/gameserver/network/clientpackets/RequestInfoItemAuction.java:47)); a stale `UseItem` does likewise ([line 95](/home/nalyk/gits/l2j/java/org/l2jmobius/gameserver/network/clientpackets/UseItem.java:95)). The fallback spacing from send time can still deliver two buffered requests in one receipt tick. Server logging is a useful detection oracle, but cannot make that fallback receipt-safe.

22. **HIGH — The governor lacks specified inputs and determinate cap semantics.** Where: [citizens.md:447](/home/nalyk/gits/l2j/docs/spec/citizens.md:447), [457](/home/nalyk/gits/l2j/docs/spec/citizens.md:457), [460](/home/nalyk/gits/l2j/docs/spec/citizens.md:460); [platform.md:148](/home/nalyk/gits/l2j/docs/spec/platform.md:148).

    The governor needs the runner’s principal splits, sales, holdings, heroes and kills. Its declared grants provide neither runner records nor the Actor Identity mapping, and no export interface supplies those inputs.

    Extraction also needs a rule for positive citizen rolls but zero observed acquisitions, and for incomplete runner observations. This is possible because `ar_yield` measures items rolled, not acquired ([aden-remembers.md:233](/home/nalyk/gits/l2j/docs/spec/aden-remembers.md:233)). “Lowered by the excess fraction” specifies neither the reference minute allowance nor rounding. Cap replacement/precedence is undefined although immutable caps accumulate without expiry. Consequently, two implementations can import the same telemetry and enforce different limits while satisfying the prose.

23. **HIGH — The hard farming and scarce-stock bounds lack recovery/enforcement rules.** Where: [citizens.md:456](/home/nalyk/gits/l2j/docs/spec/citizens.md:456), [458](/home/nalyk/gits/l2j/docs/spec/citizens.md:458), [504](/home/nalyk/gits/l2j/docs/spec/citizens.md:504).

    Farming depends on shared 15-minute reservations, but §2 declares no authoritative reservation structure, atomic allocation operation or crash reconciliation. Unlike participation slots, a restart has no specified durable record preventing a principal from reserving its daily budget again.

    The scarce-stock clause promises “at most” a configured number of S-grade-or-higher items, but specifies no acquisition reservation, overflow action or enforcement for farming, gifts and mail. Masking buying/crafting above a value threshold does not enforce this quantity ceiling. The stockpile valuation’s observation-based nature is honestly stated; the hard guarantees must either be implemented with defined accounting/recovery or narrowed accordingly.

24. **HIGH — Slot substitution reintroduces L2Walker commands after validation.** Where: [citizens.md:403](/home/nalyk/gits/l2j/docs/spec/citizens.md:403)–[412](/home/nalyk/gits/l2j/docs/spec/citizens.md:412); [Say2.java:229](/home/nalyk/gits/l2j/java/org/l2jmobius/gameserver/network/clientpackets/Say2.java:229).

    The Walker check runs before substitution; the final check covers only emptiness, length and character set. An approved target named `USESKILL` and LLM text `{who}, hello` pass the pre-substitution command check, then produce a whisper beginning with `USESKILL`, which upstream punishes.

    Apply Walker validation to the final rendered line, including fallback templates. AT-CIT-08 currently tests command-prefixed model outputs, not command-prefixed substituted values. The separately acknowledged paraphrase risk remains a stated limit, not proof that every accepted utterance preserves its act.

25. **MEDIUM — Several schema-test exceptions have the wrong oracle.** Where: [RunnerSchemaTest.java:174](/home/nalyk/gits/l2j/test/java/org/l2jmobius/gameserver/livingchronicle/citizens/RunnerSchemaTest.java:174), [235](/home/nalyk/gits/l2j/test/java/org/l2jmobius/gameserver/livingchronicle/citizens/RunnerSchemaTest.java:235), [241](/home/nalyk/gits/l2j/test/java/org/l2jmobius/gameserver/livingchronicle/citizens/RunnerSchemaTest.java:241).

    The “another principal” participation test uses EVENT without installing its limit; it fails at the limit check even if ownership validation is removed. Append-only UPDATE probes fail through the frozen triggers even if UPDATE is accidentally granted. Empty-column INSERT probes against game tables can fail required-column constraints even if INSERT is granted.

    Assert the relevant SQLState/error code, use otherwise-valid fixtures, and inspect actual grants. The claim that every §2 rule has a case is also too broad: duplicate JSON references, several source-link rules, account/login immutability and one-way retirement are not directly exercised. Snapshot equality is correctly delegated to AT-CIT-03 rather than SQL.

26. **MEDIUM — Remaining acceptance oracles need evidence and non-vacuity rules.** Where: [citizens.md:476](/home/nalyk/gits/l2j/docs/spec/citizens.md:476), [483](/home/nalyk/gits/l2j/docs/spec/citizens.md:483), [573](/home/nalyk/gits/l2j/docs/spec/citizens.md:573)–[576](/home/nalyk/gits/l2j/docs/spec/citizens.md:576).

    Genesis’s audit reads game tables and journal, yet its crafting criterion requires runner craft records. Its per-grade completed-store-sales criterion has no named durable evidence source: hourly store snapshots do not establish completed sales, and the cited `ar_store_units_<family>` telemetry aggregates resource-family units rather than weapon/armour grades.

    AT-CIT-06 permits zero human buffer sales to pass against zero median citizen sales, and gives no minimum comparable-store sample or rule when a store never sells. Its fixed eight-day dominance fixture also needs to bind `DominanceDays`. AT-CIT-09 accepts origin accuracy of 0%, although perfectly inverted guesses distinguish origins completely.

    The remaining workflow manifest, policy/filter/channel implementation, deployment grants and runtime acceptance suites are legitimate implementation obligations under [README.md:60](/home/nalyk/gits/l2j/docs/spec/README.md:60). Their absence alone does not block specification review. The defects above concern what those implementations and tests are required to establish.

Static review only; I did not execute the MariaDB tests. One citation correction also remains: `LoginClientPackets.java:212–215` does not exist in the checked file; the opcode/state declarations are at [lines 40–45](/home/nalyk/gits/l2j/java/org/l2jmobius/loginserver/network/LoginClientPackets.java:40).

**NO SIGN-OFF**
