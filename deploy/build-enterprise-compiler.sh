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
    crates/sqllib/Cargo.toml crates/sqllib/src/{aggregates,array,casts,decimal,float,lib,operators,spark_json,string,timestamp}.rs \
    crates/sqllib/src/spark_json/path.rs crates/fxp/src/{fixed,u256}.rs \
    "$delta.rs" "$delta"/{deletion_vector,field_id_adapter,input,merge/rewrite}.rs \
    crates/feldera-types/src/transport/delta_table.rs \
    crates/connectors/Cargo.toml crates/connectors/src/controller.rs crates/connectors/src/transport/url.rs \
    crates/connectors/src/transport/url/onelake.rs | tar -x -C "$context"

# Start from the release manifests; import only the added workspace dependencies
# and the locked fancy-regex package (its transitive dependencies already exist).
git show "$revision:Cargo.toml" | grep -E '^(fancy-regex|regex-syntax) = ' > "$context/regex-deps"
git show "$release:Cargo.toml" | sed "/^\[workspace.dependencies\]$/r $context/regex-deps" > "$context/Cargo.toml"
git show "$release:Cargo.lock" | sed '
    /^name = "dbsp_connectors"$/,/^\[\[package\]\]$/s/^ "governor",$/ "glob",\n "governor",/
    /^name = "feldera-sqllib"$/,/^\[\[package\]\]$/s/^ "regex",$/ "fancy-regex",\n "regex",\n "regex-syntax",/
' > "$context/Cargo.lock"
printf '\n[[package]]\n' >> "$context/Cargo.lock"
git show "$revision:Cargo.lock" | sed -n '/^name = "fancy-regex"$/,/^$/p' >> "$context/Cargo.lock"
rm "$context/regex-deps"

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
