#!/usr/bin/env bash
set -euo pipefail

# Public 0.389.0 sources with the tested OneLake/Delta and SQL compiler port.
readonly source_revision=1d8dbfbc79559ebee6c2ed2ed7db076eca7aedc9
root=$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)
cd "$root"
revision=$(git rev-parse HEAD)
image="arcdataanalyticsacr.azurecr.io/feldera/compiler-server:${revision:0:12}-ent0.389.0"

if ! git diff --quiet HEAD -- deploy/build-enterprise-compiler.sh deploy/enterprise-compiler.Dockerfile; then
    echo "Commit the Enterprise build files before building a revision-labelled image." >&2
    exit 1
fi
if ! git cat-file -e "${source_revision}^{commit}" 2>/dev/null; then
    git fetch origin "$source_revision"
fi

mkdir -p build
context=$(mktemp -d "$root/build/enterprise-compiler.XXXXXXXX")
trap 'rm -rf -- "$context"' EXIT
git archive "$source_revision" | tar -x -C "$context"
git show HEAD:deploy/enterprise-compiler.Dockerfile > "$context/deploy/enterprise-compiler.Dockerfile"

(
    cd "$context/sql-to-dbsp-compiler"
    CALCITE_BUILD_DIR=./calcite bash build.sh -pl SQL-compiler
)
mkdir -p "$context/build"
cp "$context/sql-to-dbsp-compiler/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar" "$context/build/"

docker buildx build --platform linux/amd64 --load \
    --file "$context/deploy/enterprise-compiler.Dockerfile" \
    --build-arg REVISION="$revision" \
    --build-arg PUBLIC_SOURCE_REVISION="$source_revision" \
    --tag "$image" "$context"
printf 'Built %s\nBuild revision: %s\nPublic source revision: %s\n' "$image" "$revision" "$source_revision"
