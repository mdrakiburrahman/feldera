# Deploy using Docker Compose

## Requirements

The following are required to build images and deploy them:

* [**docker**](https://docs.docker.com/engine/install/)
  (ideally, configured to run [rootless](https://docs.docker.com/engine/security/rootless/)):
  `docker --version`

* [**docker compose**](https://docs.docker.com/compose/install/):
  `docker compose version`

## Development

First, clone the `feldera` repository and navigate into it.
All commands below should be run from the root directory of the repository.

- **Latest release (same as Quickstart):**
  ```bash
  docker compose -f deploy/docker-compose.yml up
  ```

- **Built using local sources:** this can take a while as it both builds the pipeline
  manager and performs pre-compilation.
  ```bash
  docker compose -f deploy/docker-compose.yml \
                 -f deploy/docker-compose-dev.yml \
                 up --build
  ```
  As this section is for development, below we specify `-dev` configuration file each time.
  To use only the releases, remove the `-f ...-dev.yml` arguments.

- **Change logging level:**
  ```bash
  RUST_LOG=debug docker compose -f deploy/docker-compose.yml \
                                -f deploy/docker-compose-dev.yml \
                                up --build
  ```

- **Monitoring with Prometheus and Grafana:**
  ```bash
  docker compose -f deploy/docker-compose.yml \
                 -f deploy/docker-compose-dev.yml \
                 --profile grafana up --build
  ```
  ... after which you can view Prometheus at http://localhost:9090
  and Grafana at http://localhost:3000

- **Redpanda:**
  ```bash
  docker compose -f deploy/docker-compose.yml \
                 -f deploy/docker-compose-dev.yml \
                 --profile redpanda up --build
  ```

- **Kafka Connect:**
  ```bash
  docker compose -f deploy/docker-compose.yml \
                 -f deploy/docker-compose-dev.yml \
                 -f deploy/docker-compose-extra.yml \
                 --profile kafka-connect up --build
  ```

- **Kafka Connect with MySQL:**
  ```bash
  docker compose -f deploy/docker-compose.yml \
                 -f deploy/docker-compose-dev.yml \
                 -f deploy/docker-compose-extra.yml \
                 --profile mysql --profile kafka-connect up --build
  ```

- **Kafka Connect with Postgres:**
  ```bash
  docker compose -f deploy/docker-compose.yml \
                 -f deploy/docker-compose-dev.yml \
                 -f deploy/docker-compose-extra.yml \
                 --profile postgres --profile kafka-connect up --build
  ```

# How to add a new package to the Docker images

Let's say you discover that a new dependency (that can be installed
using `apt-get`) is needed for Feldera.  Here's what you have to do to
get it included:

1. Edit `build.Dockerfile` and `Dockerfile` in this directory and add
  the dependency to the list of dependencies; add a comment explaining
  why it is needed.

  Note that the these Dockerfiles serve slightly different use-cases
  depending on what you're trying to do: `build.Dockerfile` is used to
  create a uniform CI environment to build the binaries we run (e.g.,
  pipeline manager, fda etc.). `Dockerfile` is used to create the
  _runtime_ image that users of feldera download and execute (it runs
  the pipeline manager and compiles pipelines). Most of the
  dependencies will need to be in both files, given the fact that we
  also run tests in the environment of `build.Dockerfile`, but in rare
  cases it can happen that a dependency or system tool would only need
  to be in one of the files.

2. Create a PR with this change, and submit it to some upstream branch

3. Now you need to build and save a new Docker image to be used in CI.
   This is done using the github UI

   * Navigate to https://github.com/feldera/feldera/actions

   * Click on `Docker CI Image` on the left (you can also get there directly
   with https://github.com/feldera/feldera/actions/workflows/build-docker-dev.yml)

   * Run the workflow (button "Run workflow" on top-right) on the branch you
   used in step 2 above.

   * When the workflow is finished it will publish the last Docker image here:
   https://github.com/feldera/feldera-ci-test/pkgs/container/feldera-dev
   You need to copy the long hash number corresponding to the new image and use
   it in the next step.

4. Change all the CI scripts to use the new Docker image.
   These scripts are in .github/workflows/*.yaml

   * For each script that contains a line similar to:

```
   container:
    image: ghcr.io/feldera/feldera-dev:sha-2151999cad3499a8e87ba804ffa6750f925309d9
```

  you need to replace the hash value after the colon `:` with the new hash value.

  At the time of writing these instructions there were 8 files involved, but this
  may change.  You can get the list of files by `grep`-ing for the old hash value.

  A command like the one below will do the trick if you substitute the
  correct new hash into it:

  ```
  sed -i 's/feldera-dev:sha-[0-9a-f]*/feldera-dev:sha-a6c448f6eaa832d34bd5d2f6b2b4167245a8de36/' .github/workflows/*.yml
  ```

  * `build-java.yml`
  * `build-rust.yml`
  * `ci-post-release.yml`
  * `publish-crates.yml`
  * `test-integration.yml`
  * `test-java-nightly.yml`
  * `test-java.yml`
  * `test-unit.yml`

## Enterprise compiler derivative

The `dev/mdrrahman/onelake` branch owns this build recipe. Its older runtime
remains unchanged: do not overlay the checkout onto the Enterprise image.
`build-enterprise-compiler.sh` builds an isolated context from public source
commit [`1d8dbfbc79559ebee6c2ed2ed7db076eca7aedc9`](https://github.com/mdrakiburrahman/feldera/commit/1d8dbfbc79559ebee6c2ed2ed7db076eca7aedc9),
the tested fork port on upstream 0.389.0
(`551711962cef54e581a98febb129b4c8fd360874`). The full SHA, not a moving
compatibility branch, is the dependency pin. That port contains:

- Delta physical-name reads, including deletion-vector projection, and the
  fork's file-budget and follow/ignore policies.
- Boolean-or-string parsing for the ignore policies.
- The SQL compiler's long-identifier support and Calcite window-expression
  lineage patch, including the existing focused regression tests.

`enterprise-compiler.Dockerfile` derives from the digest-pinned official
`compiler-server:0.389.0`. It checks `Cargo.lock` against the vendor's, copies
the compatible public connector/types sources and rebuilt SQL compiler jar,
and reruns the vendor precompile with cache-preserving timestamps. The vendor
compiler executable, entrypoint, environment, ubuntu user, `/home/ubuntu`
working directory, license checks and proprietary Enterprise crates remain
unchanged. No proprietary source is exported or committed.

Build in the Feldera devcontainer, with Java 21, Maven, Python 3 and Docker
Buildx available. Commit and push the recipe before building. The script can
be called from any directory and fetches the pinned public commit if missing.
It does not use existing jars, modify the checkout or publish automatically.

```bash
bash deploy/build-enterprise-compiler.sh
IMAGE="arcdataanalyticsacr.azurecr.io/feldera/compiler-server:$(git rev-parse --short=12 HEAD)-ent0.389.0"
# Authenticate to this private ACR separately, without putting tokens in commands.
docker push "$IMAGE"
docker buildx imagetools inspect "$IMAGE"
docker image inspect "$IMAGE" --format '{{json .Config.Labels}}'
```

Record both the pushed OCI index and linux/amd64 manifest digests, then pin the
compiler image as `arcdataanalyticsacr.azurecr.io/feldera/compiler-server@sha256:...`.
`org.opencontainers.image.revision` records the **onelake build revision**;
`io.feldera.compiler.public-source-revision` records the **compatible runtime
source revision**. These intentionally differ. Never publish this derivative
to GHCR or another public registry. The existing AKS kubelet has `AcrPull` on
this ACR; no new pull secret or role assignment is needed for this cluster.

Only override the Helm chart's compiler-server image. Keep the official
0.389.0 API server, Kubernetes runner and pipeline executor. Retain the chart
entrypoint, arguments and probes, the 100Gi compiler PVC, and the recommended
compiler requests of 16 CPU and 32000Mi memory.

Stock 0.389.0 components pass `ignore_changes` and `ignore_deletes` as strings:
use `"true"` or `"false"` in connector configurations. The pinned runtime
parses and executes these flags. Multi-host execution with the default
non-distributed inputs needs no `transaction_mode` change; a distributed
`snapshot_and_follow` input requires a mode other than `none`, such as
`snapshot`.
