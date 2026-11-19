#!/usr/bin/env bash
#
# Take one upstream L2J Mobius commit into the fork: blueprint 03 §3's intake, up to the gate.
#
#   1. Mirror the commit onto `upstream` (mirror-upstream.sh).
#   2. List the upstream commits that touched High Five since the previous mirror, for the intake report.
#   3. Branch intake/<sha12> from the base (default `main`) and merge `upstream` into it. A conflict stops the
#      intake: conflicts come from the ledgered core patches, and each is re-applied by hand to the new code.
#   4. Check the core ledger: every changed upstream file is still ledgered, and every ledgered one still differs.
#   5. Check that the intake changes no golden: the behaviour lock is what judges it, so it may not bring its own.
#   6. Run the gate (./build.sh). A lock that fails is the intake's finding; it is answered with an evidence-backed
#      Retail Truth correction or a scoped DESIGN_OVERRIDE (blueprint 11 §2), never by recording the new behaviour.
#
# The intake branch is left for review; merging it into the base is a separate, reviewed step.
#
# Usage: tools/intake/intake.sh <full-upstream-commit-sha> [base-branch]

set -euo pipefail

readonly UPSTREAM_SUBDIR='L2J_Mobius_CT_2.6_HighFive'

die() { echo "intake: $*" >&2; exit 1; }

[[ $# -ge 1 && $# -le 2 ]] || die "usage: $0 <full-upstream-commit-sha> [base-branch]"
readonly sha=$1
readonly base=${2:-main}
[[ $sha =~ ^[0-9a-f]{40}$ ]] || die "need a full 40-hex commit id, got '$sha'"

repo=$(git rev-parse --show-toplevel)
readonly repo
cd "$repo"
readonly cache=${LC_UPSTREAM_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/l2j/mobius-upstream}
readonly branch="intake/${sha:0:12}"

[[ -z $(git status --porcelain) ]] || die "the working tree is not clean"
git rev-parse -q --verify "refs/heads/$base" >/dev/null || die "no branch '$base'"
git rev-parse -q --verify "refs/heads/$branch" >/dev/null && die "branch '$branch' exists already"

previous=$(git log -1 --format=%B upstream | sed -n 's/^Upstream-Commit: //p')
[[ -n $previous ]] || die "the upstream mirror names no Upstream-Commit"

echo "== 1. mirror $sha"
tools/intake/mirror-upstream.sh "$sha"

echo "== 2. upstream commits to $UPSTREAM_SUBDIR since ${previous:0:12}"
since=$(git log -1 --format=%cI upstream^ 2>/dev/null || git log -1 --format=%cI upstream)
git -C "$cache" fetch -q --filter=blob:none --shallow-since="$(date -d "$since -1 day" +%F)" origin "$sha"
git -C "$cache" log --reverse --format='%h %cs %s' "$previous..$sha" -- "$UPSTREAM_SUBDIR"

echo "== 3. merge into $branch"
git switch -q -c "$branch" "$base"
if ! git merge -q --no-ff -m "Intake upstream ${sha:0:12}" upstream; then
	echo "intake: the merge conflicts; re-apply the ledgered core patches to the new code, commit, and rerun the checks:" >&2
	git diff --name-only --diff-filter=U >&2
	exit 1
fi

echo "== 4. core ledger"
tools/intake/check-core-ledger.sh

echo "== 5. no golden changed"
tools/intake/check-intake-goldens.sh "$base"

echo "== 6. gate"
./build.sh

echo "intake: $branch passed the gate; review it, then merge it into $base"
