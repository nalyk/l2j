#!/usr/bin/env bash
#
# Re-resolve the test library closure and rewrite the `jar` lines of toolchain.lock.
#
# The build never resolves dependencies: it downloads exactly the jars named in
# toolchain.lock and checks their digests (tools/build/Provision.java). This
# script is the only place a resolver runs, and only when someone deliberately
# changes a root version below. Review the resulting diff like any other change.
#
# Usage: tools/build/relock-test-libs.sh

set -euo pipefail

# Roots. Everything else in the lock is their transitive closure.
readonly JUNIT_VERSION=6.1.3
readonly TESTCONTAINERS_VERSION=2.0.5

repo=$(git rev-parse --show-toplevel)
readonly repo
readonly lock=$repo/toolchain.lock

maven_image=$(awk '$1 == "image" && $2 == "maven" { print $4 "@" $3 }' "$lock")
[[ -n $maven_image ]] || { echo "relock: no 'image maven' line in toolchain.lock" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

cat >"$work/pom.xml" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
	<modelVersion>4.0.0</modelVersion>
	<groupId>lc</groupId>
	<artifactId>test-libs</artifactId>
	<version>0</version>
	<dependencies>
		<dependency>
			<groupId>org.junit.jupiter</groupId>
			<artifactId>junit-jupiter</artifactId>
			<version>$JUNIT_VERSION</version>
		</dependency>
		<dependency>
			<groupId>org.junit.platform</groupId>
			<artifactId>junit-platform-launcher</artifactId>
			<version>$JUNIT_VERSION</version>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers</artifactId>
			<version>$TESTCONTAINERS_VERSION</version>
			<exclusions>
				<!-- The server already ships slf4j-api in dist/libs; two copies on one classpath is a bug waiting to happen. -->
				<exclusion>
					<groupId>org.slf4j</groupId>
					<artifactId>slf4j-api</artifactId>
				</exclusion>
			</exclusions>
		</dependency>
	</dependencies>
</project>
EOF

docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -e MAVEN_CONFIG=/tmp/.m2 -v "$work:/w" -w /w "$maven_image" \
	mvn -q -B -Duser.home=/tmp dependency:copy-dependencies \
	-DincludeScope=runtime -Dmdep.useRepositoryLayout=true -DoutputDirectory=/w/out

# Repository layout is group/path/artifact/version/artifact-version.jar, so the coordinate is the path.
jars=$(
	cd "$work/out"
	find . -name '*.jar' | LC_ALL=C sort | while read -r path; do
		path=${path#./}
		version=$(basename "$(dirname "$path")")
		artifact=$(basename "$(dirname "$(dirname "$path")")")
		group=$(dirname "$(dirname "$(dirname "$path")")")
		printf 'jar    %s:%s:%s sha256:%s\n' "${group//\//.}" "$artifact" "$version" "$(sha256sum "$path" | cut -d' ' -f1)"
	done
)
[[ -n $jars ]] || { echo "relock: resolver produced no jars" >&2; exit 1; }

{ grep -v '^jar[[:space:]]' "$lock"; printf '%s\n' "$jars"; } >"$work/toolchain.lock"
cp "$work/toolchain.lock" "$lock"
echo "relock: $(printf '%s\n' "$jars" | wc -l) jars pinned in toolchain.lock"
