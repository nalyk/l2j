#!/usr/bin/env bash
#
# Self-test of check-core-ledger.sh, in a throwaway repository:
#
#   1. a fork branch with one ledgered patch passes;
#   2. it still passes once a newer upstream snapshot has been mirrored but not merged (the check compares with the
#      snapshot the branch is built on, not with the tip of `upstream`);
#   3. it passes after merging that snapshot, and an unledgered core change then fails;
#   4. paths whose order differs between the C locale and the user's (capitals, underscores) compare correctly.
#
# Usage: tools/intake/test-check-core-ledger.sh

set -euo pipefail

script=$(cd "$(dirname "$0")" && pwd)/check-core-ledger.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"

# The check must hold in a locale that orders paths differently from C; where none is installed it runs in the caller's.
collating=$(locale -a 2>/dev/null | grep -im1 '^en_US\.utf-\?8$' || true)
[[ -n $collating ]] || echo "test-check-core-ledger: no en_US.UTF-8 here; the C-locale comparison is not exercised" >&2

g() { git -c user.name=test -c user.email=test@invalid -c commit.gpgsign=false "$@"; }
expect() { # expect pass|fail <what>
	local outcome=pass
	if [[ -n $collating ]]; then
		LC_ALL=$collating "$script" >"$work/out" 2>&1 || outcome=fail
	else
		"$script" >"$work/out" 2>&1 || outcome=fail
	fi
	if [[ $outcome != "$1" ]] || grep -q 'not in sorted order' "$work/out"; then
		echo "test-check-core-ledger: expected $1 ($2), got $outcome:" >&2
		sed 's/^/  /' "$work/out" >&2
		exit 1
	fi
}

g init -q -b upstream
mkdir -p src
for f in Zeta.java alpha.java a_b.java aB.java plain.txt; do echo "$f v1" >"src/$f"; done
g add -A && g commit -q -m "upstream 1"

g switch -q -c fork
echo "patched" >>src/Zeta.java
echo "patched" >>src/a_b.java
printf -- '- Files: `src/Zeta.java`, `src/a_b.java`\n' >CORE_PATCHES.md
g add -A && g commit -q -m "fork patches"
expect pass "ledgered patches, mixed-case paths"

g switch -q upstream
echo "upstream change" >>src/plain.txt
g commit -q -am "upstream 2"
g switch -q fork
expect pass "a newer upstream mirrored but not yet merged"

g merge -q --no-edit upstream
expect pass "after merging the newer upstream"

echo "unledgered" >>src/aB.java
expect fail "an unledgered core change"

echo "test-check-core-ledger: all cases as expected"
