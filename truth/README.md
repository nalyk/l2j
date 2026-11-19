# Retail Truth cases

One file per case, `cases/RT-<DOMAIN>-<NNNN>.yaml`, in the format of blueprint 11 §1.3. A case says what retail does,
with evidence of the classes in §1.2, and how the server compares: `EXACT`, `FUNCTIONAL`, `APPROXIMATE`, `BROKEN`,
`MISSING` or `UNKNOWN`. It is not the behaviour lock (`test/lock`), which says only what the server does now.

Cases are seeded where work on the server meets a question it cannot answer from the code. Such a case starts `UNKNOWN`,
with what the server does recorded as `observed` (evidence class 5, current behaviour only) and the evidence that would
settle it under `evidence_wanted`; it is never upgraded because nobody complained. The suites and the Truth Report that
read these files are N11h.
