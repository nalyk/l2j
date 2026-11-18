#!/usr/bin/env bash
#
# Fail unless CORE_PATCHES.md lists exactly the upstream files this fork changed.
#
# "Changed" is measured, not remembered: every path that exists on the
# `upstream` branch and is modified, deleted, renamed or retyped in the working
# tree. A core change without a ledger entry fails; so does a ledger entry for
# a file that no longer differs.
#
# Usage: tools/intake/check-core-ledger.sh

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

git rev-parse -q --verify refs/heads/upstream >/dev/null ||
	{ echo "check-core-ledger: no local 'upstream' branch; fetch it first" >&2; exit 1; }

# Compare against the working tree so the check also covers uncommitted work.
changed=$(git diff --name-only --no-renames --diff-filter=MDT upstream -- | LC_ALL=C sort -u)

[[ -f CORE_PATCHES.md ]] || { echo "check-core-ledger: CORE_PATCHES.md is missing" >&2; exit 1; }

# Paths are the backticked items on each "- Files:" line. A ledger with no entries is valid.
ledgered=$(sed -n 's/^- Files://p' CORE_PATCHES.md | { grep -oE '`[^`]+`' || true; } | tr -d '`' | LC_ALL=C sort -u)

unledgered=$(comm -23 <(printf '%s\n' "$changed") <(printf '%s\n' "$ledgered") | sed '/^$/d')
stale=$(comm -13 <(printf '%s\n' "$changed") <(printf '%s\n' "$ledgered") | sed '/^$/d')

status=0
if [[ -n $unledgered ]]; then
	echo "check-core-ledger: upstream files changed without an entry in CORE_PATCHES.md:" >&2
	printf '%s\n' "$unledgered" | sed 's/^/  /' >&2
	status=1
fi
if [[ -n $stale ]]; then
	echo "check-core-ledger: CORE_PATCHES.md lists files that do not differ from upstream:" >&2
	printf '%s\n' "$stale" | sed 's/^/  /' >&2
	status=1
fi

[[ $status -ne 0 ]] || echo "check-core-ledger: $(printf '%s\n' "$ledgered" | sed '/^$/d' | wc -l) core file(s), all ledgered"
exit $status
