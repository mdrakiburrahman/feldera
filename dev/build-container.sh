#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  GITHUB_TOKEN="$(gh auth token)"
fi
export GITHUB_TOKEN
export FELDERA_PLATFORM_VERSION_SUFFIX="$(git rev-parse HEAD)"
export FELDERA_BUILD_ORIGIN=source
target="$(rustc -vV | sed -n 's/^host: //p')"

cargo build --release --locked --target "$target" \
  -p pipeline-manager -p fda --features pipeline-manager/fips
(
  cd sql-to-dbsp-compiler
  CALCITE_BUILD_DIR=./calcite bash build.sh -pl SQL-compiler
)

mkdir -p build
cp "target/$target/release/pipeline-manager" "target/$target/release/fda" build/
cp sql-to-dbsp-compiler/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar build/
chmod +x build/pipeline-manager build/fda

docker build -f deploy/Dockerfile -t feldera/pipeline-manager:dev .
