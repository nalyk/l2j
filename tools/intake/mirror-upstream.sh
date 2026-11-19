#!/usr/bin/env bash
#
# Mirror one upstream L2J Mobius commit onto the local `upstream` branch.
#
# The `upstream` branch holds the High Five subdirectory of the Mobius monorepo
# and nothing else. Each commit on it is one upstream snapshot whose root tree
# IS the upstream subtree object, so "verbatim" is not a claim but an identity:
#
#     git rev-parse upstream^{tree}  ==  <upstream-commit>:L2J_Mobius_CT_2.6_HighFive
#
# No file ever passes through a working tree, so line endings, modes and
# attributes cannot be altered on the way.
#
# The mirror commit is a pure function of (upstream commit, previous mirror
# commit): identity and dates are taken from upstream, so anyone re-running
# this gets the same commit id.
#
# Usage: tools/intake/mirror-upstream.sh <full-upstream-commit-sha>

set -euo pipefail

readonly UPSTREAM_URL='https://gitlab.com/MobiusDevelopment/L2J_Mobius.git'
readonly UPSTREAM_SUBDIR='L2J_Mobius_CT_2.6_HighFive'
readonly BRANCH='refs/heads/upstream'
readonly EXPORT_REF='refs/lc/export'

die() { echo "mirror-upstream: $*" >&2; exit 1; }

[[ $# -eq 1 ]] || die "usage: $0 <full-upstream-commit-sha>"
readonly sha=$1
[[ $sha =~ ^[0-9a-f]{40}$ ]] || die "need a full 40-hex commit id, got '$sha'"

repo=$(git rev-parse --show-toplevel)
readonly repo
readonly cache=${LC_UPSTREAM_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/l2j/mobius-upstream}

# Moving a branch that is checked out would leave that worktree's index stale.
if git -C "$repo" worktree list --porcelain | grep -qx "branch $BRANCH"; then
	die "branch 'upstream' is checked out in a worktree; switch away from it first"
fi

# The cache is a blobless, sparse clone: only High Five blobs are ever downloaded.
if [[ ! -d $cache/.git ]]; then
	mkdir -p "$cache"
	git -C "$cache" init -q
	git -C "$cache" remote add origin "$UPSTREAM_URL"
	git -C "$cache" config remote.origin.promisor true
	git -C "$cache" config remote.origin.partialclonefilter blob:none
	git -C "$cache" sparse-checkout init --cone
	git -C "$cache" sparse-checkout set "$UPSTREAM_SUBDIR"
fi

git -C "$cache" fetch -q --depth 1 --filter=blob:none origin "$sha"
[[ $(git -C "$cache" rev-parse FETCH_HEAD) == "$sha" ]] || die "upstream did not return $sha"

# Checking out materialises exactly the blobs of the subtree we export.
git -C "$cache" checkout -q --detach "$sha"

subtree=$(git -C "$cache" rev-parse "$sha:$UPSTREAM_SUBDIR")
up_date=$(git -C "$cache" show -s --format=%cI "$sha")
up_subject=$(git -C "$cache" show -s --format=%s "$sha")
readonly subtree up_date up_subject

# Carry the subtree objects across on a throwaway parentless commit.
carrier=$(git -C "$cache" commit-tree "$subtree" -m carrier </dev/null)
git -C "$cache" update-ref "$EXPORT_REF" "$carrier"
git -C "$repo" fetch -q --no-tags "$cache" "$EXPORT_REF"
[[ $(git -C "$repo" rev-parse 'FETCH_HEAD^{tree}') == "$subtree" ]] || die "subtree changed in transit"

parent=$(git -C "$repo" rev-parse -q --verify "$BRANCH" || true)
if [[ -n $parent ]]; then
	if [[ $(git -C "$repo" rev-parse "$parent^{tree}") == "$subtree" ]]; then
		echo "mirror-upstream: upstream already at tree $subtree, nothing to do"
		exit 0
	fi
	# History is fetched shallow, so ancestry cannot be checked; refuse to go back in time.
	prev_date=$(git -C "$repo" show -s --format=%cI "$parent")
	[[ $(date -d "$up_date" +%s) -ge $(date -d "$prev_date" +%s) ]] ||
		die "$sha ($up_date) is older than the current mirror ($prev_date)"
fi

message=$(cat <<EOF
Mobius CT 2.6 High Five @ ${sha:0:12}

Verbatim mirror of $UPSTREAM_SUBDIR/ from
$UPSTREAM_URL

Upstream-Commit: $sha
Upstream-Date: $up_date
Upstream-Subject: $up_subject
Upstream-Tree: $subtree
EOF
)

commit=$(
	GIT_AUTHOR_NAME='L2J Mobius' GIT_AUTHOR_EMAIL='upstream@l2jmobius.invalid' GIT_AUTHOR_DATE=$up_date \
	GIT_COMMITTER_NAME='L2J Mobius' GIT_COMMITTER_EMAIL='upstream@l2jmobius.invalid' GIT_COMMITTER_DATE=$up_date \
		git -C "$repo" commit-tree "$subtree" ${parent:+-p "$parent"} -m "$message" </dev/null
)

# Compare-and-swap: fails if someone else moved the branch meanwhile.
git -C "$repo" update-ref -m "mirror upstream $sha" "$BRANCH" "$commit" "${parent:-}"

echo "mirror-upstream: upstream -> $commit (tree $subtree, upstream ${sha:0:12})"
