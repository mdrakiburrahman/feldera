#!/usr/bin/env bash
set -euo pipefail

: "${IMAGE:?Set IMAGE to a tag in your private registry}"
cd "$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
revision=$(git rev-parse HEAD)
release=bc352d22e0d08f27a174a29e376ea9548fa24113 # v0.391.0
context=$(mktemp -d)
trap 'rm -rf -- "$context"' EXIT

delta=crates/connectors/src/integrated/delta_table
git archive "$revision" -- deploy/enterprise-compiler.Dockerfile sql-to-dbsp-compiler \
    crates/sqllib/src/{array,timestamp}.rs \
    "$delta.rs" "$delta"/{deletion_vector,field_id_adapter,input,merge/rewrite}.rs \
    crates/feldera-types/src/transport/delta_table.rs \
    crates/connectors/Cargo.toml crates/connectors/src/transport/url.rs \
    crates/connectors/src/transport/url/onelake.rs | tar -x -C "$context"

manifest=crates/connectors/Cargo.toml
sed '/^    "dep:glob",$/d; /^glob = { version = "0.3.2", optional = true }$/d' \
    "$context/$manifest" | cmp - <(git show "$release:$manifest")

# Keep the released SQL compiler version; fail if its dependencies have drifted.
pom=sql-to-dbsp-compiler/SQL-compiler/pom.xml
sed -i -E 's|<project.version>[^<]+</project.version>|<project.version>0.391.0</project.version>|' "$context/$pom"
git show "$release:$pom" | cmp - "$context/$pom"
(
    cd "$context/sql-to-dbsp-compiler"
    bash build.sh -pl SQL-compiler
)
mv "$context/sql-to-dbsp-compiler/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar" "$context/compiler.jar"
printf 'sql-to-dbsp-compiler\n' > "$context/.dockerignore"
docker buildx build --platform linux/amd64 --load -t "$IMAGE" \
    --build-arg REVISION="$revision" -f "$context/deploy/enterprise-compiler.Dockerfile" "$context"
