#!/usr/bin/env bash
#
# One command from a clean checkout to a verified build (blueprint 03 §2).
#
#   ./build.sh            full gate: package the server, fingerprint it, run every test
#   ./build.sh <target>   any target of build-lc.xml (dist, test, ...)
#
# The only host requirements are bash and docker. Everything else comes from
# the build image, which is assembled from toolchain.lock and nothing else.

set -euo pipefail
cd "$(dirname "$0")"

# "<repository:tag>@<digest>" of an image pinned in toolchain.lock.
pinned_image() {
	local ref
	ref=$(awk -v name="$1" '$1 == "image" && $2 == name { print $4 "@" $3 }' toolchain.lock)
	[[ -n $ref ]] || { echo "build.sh: no 'image $1' line in toolchain.lock" >&2; exit 1; }
	printf '%s' "$ref"
}

# The image is a pure function of these files, so it is named after them.
readonly image_inputs=(toolchain.lock tools/build/Dockerfile tools/build/Provision.java)
image=lc-build:$(cat "${image_inputs[@]}" | sha256sum | cut -c1-16)
readonly image

if ! docker image inspect "$image" >/dev/null 2>&1; then
	echo "build.sh: assembling $image"
	tar -c "${image_inputs[@]}" |
		docker build -q -f tools/build/Dockerfile --build-arg JDK_IMAGE="$(pinned_image jdk)" -t "$image" - >/dev/null
fi

# The fork's delta against upstream must be fully accounted for in CORE_PATCHES.md.
if git rev-parse -q --verify refs/heads/upstream >/dev/null 2>&1; then
	tools/intake/check-core-ledger.sh
else
	echo "build.sh: no local 'upstream' branch here, core ledger not checked" >&2
fi

# All output goes to ./build, which ignores itself so no upstream file has to.
mkdir -p build
[[ -e build/.gitignore ]] || echo '*' >build/.gitignore

# The checkout is mounted read-only: a build that writes outside ./build fails.
# Integration tests start sibling containers (MariaDB) through the host daemon.
exec docker run --rm --init \
	--user "$(id -u):$(id -g)" --group-add "$(stat -c %g /var/run/docker.sock)" \
	-e HOME=/tmp \
	-v "$PWD:/src:ro" -v "$PWD/build:/src/build" -w /src \
	-v /var/run/docker.sock:/var/run/docker.sock \
	--add-host host.docker.internal:host-gateway \
	-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	-e TESTCONTAINERS_RYUK_CONTAINER_IMAGE="$(pinned_image ryuk)" \
	-e LC_MARIADB_IMAGE="$(pinned_image mariadb)" \
	"$image" ant -f build-lc.xml -lib /opt/lc/test-libs "$@"
