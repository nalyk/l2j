#!/usr/bin/env bash
#
# Fail if the current branch changes any behaviour-lock golden relative to a base.
#
# An intake is judged by the behaviour lock; it may not bring the goldens it is judged by (blueprint 03 §3,
# 11 §2). A lock it fails is answered with an evidence-backed Retail Truth correction or a scoped DESIGN_OVERRIDE,
# each its own reviewed change on the base, never by recording the new behaviour on the intake branch.
#
# Usage: tools/intake/check-intake-goldens.sh <base>

set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 <base>" >&2; exit 1; }
readonly base=$1

cd "$(git rev-parse --show-toplevel)"

# Committed changes since the branch left the base, and anything uncommitted on top.
changed=$( { git diff --name-only "$base"...HEAD -- test/lock; git diff --name-only HEAD -- test/lock; } | LC_ALL=C sort -u)

if [[ -n $changed ]]; then
	echo "check-intake-goldens: this branch changes goldens it is judged by:" >&2
	printf '%s\n' "$changed" | sed 's/^/  /' >&2
	exit 1
fi
echo "check-intake-goldens: no golden changed against $base"
