1. **PARTLY — previous HIGH, evidence oracle.** [§2.1](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:67) adds property matching, derivations, scope and an explicitly human support judgment. E1–E6 address the original laundering examples, but source identity and client-build scope remain incomplete; see finding 23.

2. **PARTLY — previous HIGH, status oracles.** [§3.1](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:114) now specifies recorded and executed outcomes for all judged statuses. The `MISSING` probe’s inclusion of the inventory’s `HAND` row contradicts how those rows work; see finding 22.

3. **FIXED — previous HIGH, ordinary repairs.** [§3.2](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:134) explicitly permits the missing repair transitions, including `BROKEN → FUNCTIONAL/EXACT`, `MISSING → FUNCTIONAL` and `APPROXIMATE → EXACT`.

4. **PARTLY — previous HIGH, refutation and history.** [§3.2–3.3](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:151) adds deletion protection, append-only evidence, base-only comparison and a persistent `fixed` marker. Refutation can still discard unrefuted support and clear that marker; see finding 16.

5. **PARTLY — previous HIGH, correction authorization.** [§4.3](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:213) excludes `UNKNOWN/BROKEN/MISSING` judgments, but a `FUNCTIONAL` flow check still need not establish the changed golden value; see finding 17.

6. **PARTLY — previous HIGH, golden identity and scope.** [§4.3](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:204) defines complete keys, duplicate rejection, before/after overrides, one-use authorization and file retirement. Its comparison nevertheless loses ordering changes; see finding 18.

7. **PARTLY — previous HIGH, probabilistic equivalence.** [§4.1](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:176) fixes the original `0/2000` example through interval containment and minimum sample size. Unrestricted confidence levels still permit a materially wrong rate to pass readily; see finding 19.

8. **PARTLY — previous HIGH, fabricated inventory judgments.** [Inventory.merge](/home/nalyk/gits/l2j/test/java/org/l2jmobius/testing/truth/Inventory.java:435) now replaces CSV judgments with case-derived values. However, its case reader silently ignores a valid YAML inventory-list form; see finding 20.

9. **PARTLY — previous HIGH, preservation across CSV edits.** [Inventory.merge](/home/nalyk/gits/l2j/test/java/org/l2jmobius/testing/truth/Inventory.java:447) rejects missing rows named by parsed cases. The parsing bypass can remove that anchor without changing the case’s YAML meaning; see finding 20.

10. **PARTLY — previous MEDIUM, instance matching.** [Inventory’s patterns](/home/nalyk/gits/l2j/test/java/org/l2jmobius/testing/truth/Inventory.java:46) fix the reported comments, price-map, numeric-prefix and `new int[]` examples. Matching still treats code-shaped string contents as declarations; see finding 21.

11. **PARTLY — previous MEDIUM, boss placement.** [Inventory](/home/nalyk/gits/l2j/test/java/org/l2jmobius/testing/truth/Inventory.java:50) fixes the reported negative/simple positive coordinates, comments and inline SQL tuples. Nested location arguments still violate its stated exclusion; see finding 21.

12. **PARTLY — previous MEDIUM, independent validation.** [InventoryCurrentTest](/home/nalyk/gits/l2j/test/java/org/l2jmobius/testing/truth/InventoryCurrentTest.java:128) pins required systems and independently checks table/XML placements. Instance validation checks only numeric-token presence and accepts the false reference in finding 21. The explicitly stated limitation that boss script references are leads is otherwise honest.

13. **FIXED — previous MEDIUM, Truth Report contract.** [§4.6](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:308) and AT-RT-07 agree on deviations, coverage counts, inventory status counts and every inventory finding. Report generation remains an explicitly named implementation obligation.

14. **PARTLY — previous MEDIUM, execution boundary and existing enforcement.** §1 now distinguishes proposed enforcement, and AT-RT-05 specifies the missing boundary checks. Those are legitimate implementation obligations. However, [§4.3’s “Intake (existing)”](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:229) also claims override/key restrictions that [check-intake-goldens.sh](/home/nalyk/gits/l2j/tools/intake/check-intake-goldens.sh:23) does not enforce: it checks only `test/lock`; `intake.sh` supplies no additional override/key check.

15. **FIXED — previous LOW, textual ordering.** [Inventory.merge](/home/nalyk/gits/l2j/test/java/org/l2jmobius/testing/truth/Inventory.java:454) now sorts textual keys, and `textualKeysAreKeptInKeyOrderToo` exercises the previously missing case.

16. **HIGH — Refutation can erase protection for unrefuted properties.** [§3.3](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:162) allows R when items resting on unrefuted entries are removed. Consider an `EXACT` case with independently supported damage and text properties. Append a genuine refutation of the text evidence, remove both expectations, and move to `UNKNOWN`: the damage evidence was never refuted, yet R permits clearing `fixed`. A later `BROKEN` assignment can then describe a reintroduced damage defect without the former protection. The literal “or” also leaves an all-expectations-deleted route without requiring any refutation. Require actual refutation of the protected claim, rather than treating removal of its expectations as refutation.

    There is also no persistent location for the new refutation reviewer: R requires the change’s `approved_by`, while its transition effect clears that field. Store approval on the refutation record.

17. **HIGH — A passing flow authorizes unrelated numerical golden changes.** [§3.1](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:117) requires a `FUNCTIONAL` case to complete and satisfy its flow items; [§4.3](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:213) lets that case authorize an entire referenced golden line. A newly judged attack-flow case can therefore authorize arbitrary damage-column changes while proving only that the attack completes. Complete line keys identify the row but do not establish the correctness of its changed values. Require correction authorization to cover the changed properties with measured, evidence-backed expectations.

18. **HIGH — Reordering behavior bypasses golden-change authorization.** [§4.3](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:208) compares `line` files as multisets and keyed files by key-to-line differences. Reordering existing lines produces neither difference. Change server output order and regenerate the golden with the same reordered lines: [Golden.assertMatches](/home/nalyk/gits/l2j/test/java/org/l2jmobius/testing/Golden.java:95) passes, while the proposed authorization check sees no changed line requiring a correction or override. This matters directly to ordered boot logs. Define authorization for order changes, or explicitly change which order the lock promises to preserve.

19. **HIGH — Confidence can be lowered until a wrong mechanic passes readily.** [§4.1 and §8](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:176) permit overriding `ci` without a minimum. For `p0=0.001`, `δ=0.0005`, `ci=0.01`, the stated formula gives `n_min=3`. With `n=1000`, `k=1`, the Wilson interval is approximately `[0.00098755, 0.00101261]`, hence `WITHIN`. A wrong implementation with true rate `0.002` produces exactly that sample with probability about **27.1%**. The seed being fixed after judgment does not repair this weak authoring criterion.

    Specify an admissible confidence range and the required discrimination/error probability. Also qualify the absent-mechanic claim: if the evidence-derived tolerance includes zero, absence is inside the acceptance band and cannot be excluded by increasing sample size.

20. **HIGH — Valid YAML can silently remove the inventory anchor.** [Inventory.judgments](/home/nalyk/gits/l2j/test/java/org/l2jmobius/testing/truth/Inventory.java:324) requires whitespace before a block-list dash. This valid YAML:

    ```yaml
    inventory:
    - quests:1
    ```

    has exactly the same parsed value as the indented form, but `judgments` silently returns no judgment for it. Reformat a judged case this way, remove its content and CSV row, and the inventory gate loses the reference. A history checker using YAML values sees no removed `inventory` entry. The format specification does not prohibit this YAML form. Use a YAML parser or reject unsupported forms explicitly; silently ignoring them defeats RT-INV05.

21. **MEDIUM — Remaining lexical counterexamples exceed the stated scanner limits.** [Inventory.withoutComments](/home/nalyk/gits/l2j/test/java/org/l2jmobius/testing/truth/Inventory.java:463) retains literals, after which declaration patterns scan their contents. Valid Java such as `String example = "final int TEMPLATE_ID = 101;";` falsely names template 101. [The independent instance check](/home/nalyk/gits/l2j/test/java/org/l2jmobius/testing/truth/InventoryCurrentTest.java:180) also accepts it because the numeric token exists outside a comment.

    Separately, `new Location(25073, getY(), 2)` escapes `LOCATION` because its pattern prohibits nested parentheses, so 25073 becomes a script placement lead. This is inside a location constructor, which §4.5 promises to exclude; the honest limitation about coordinates *elsewhere* does not cover it. Add these fixtures and make the lexical treatment match the documented boundary.

22. **MEDIUM — The `HAND` absence probe tests the opposite artifact.** [§3.1](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:120) permits looking up “the inventory’s `HAND` row” and requires finding the entry absent. But [§4.5](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:290) requires that row to exist for missing retail content, and `Inventory.merge` rejects its deletion while a case names it. A correctly represented missing feature therefore cannot satisfy this probe. Probe the runtime/datapack registration; the `HAND` row records the absence.

23. **MEDIUM — Evidence provenance remains mutable and incompletely scoped.** [§2.1](/home/nalyk/gits/l2j/docs/spec/retail-truth.md:52) requires a case’s client hash, but the source schema and E4 do not bind class-1 evidence to that build. An arbitrary pinned hash and evidence from another H5 client build satisfy the stated mechanical checks.

    Furthermore, append-only protection covers case evidence entries, while their referenced source files can change locator, retrieved-copy hash or provenance without changing those entries or triggering the same-status rejudgment guard. Pin immutable source revisions and their applicable client build, or require source changes to rejudge all dependent cases. Human interpretation of source content can remain an explicitly reviewed limit.

24. **LOW — The committed seed case cites a result the golden does not contain.** [RT-AI-0001.yaml:22](/home/nalyk/gits/l2j/truth/cases/RT-AI-0001.yaml:22) says the level-34 Knight misses Breka Warlock Pastu nine times. [The committed row](/home/nalyk/gits/l2j/test/lock/ai/raid-boss-meets-player.tsv:14) records `strikes=1`; the suite says this column counts swings. The code supports the narrower observations about evasion notification and positive-damage gating, but this numerical example is stale.

Independent read-only enumeration confirmed **539 quests, 142 instances and 370 bosses**, with no missing or extra committed IDs, and no discrepancies in current boss table/spawn/Rift placements. All **1,088 inventory rows** remain `UNKNOWN`. I did not run the Java gate in this read-only environment. The absent case/report/history tooling is clearly identified as future implementation; its absence alone is not grounds for rejection. The concrete specification and parser loopholes above are.

**NO SIGN-OFF.**
