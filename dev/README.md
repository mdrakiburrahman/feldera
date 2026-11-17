# Windows devbox

Run from an elevated PowerShell 7 prompt with Git, VS Code, and
[Task](https://taskfile.dev/) installed:

```powershell
task --taskfile dev/bootstrap-dev-env.yaml
```

The default task deletes existing WSL distros, installs Ubuntu-24.04, clones
the Windows checkout's current branch into `/workspaces/feldera`, and starts
the existing Compose devcontainer. Push the branch first so WSL can clone it.
Docker Desktop is removed if installed; Docker runs inside WSL instead.

After a restart, preserve the WSL checkout and restart the devcontainer with:

```powershell
task --taskfile dev/bootstrap-dev-env.yaml up
```

## Pipeline-manager image

Inside the devcontainer, run:

```bash
bash dev/build-container.sh
```

Or, from the Windows checkout:

```powershell
task --taskfile dev/bootstrap-dev-env.yaml build-container
```

The script builds the release `pipeline-manager` (with FIPS) and `fda`
binaries, including the web console, and the SQL compiler JAR, then stages
them under `build/` and builds `deploy/Dockerfile` as
`feldera/pipeline-manager:dev`. Its precompile stage builds the pipeline
dependencies too. Subsequent runs reuse Cargo, Maven, Gradle, and Docker caches.
Allow substantial disk space and time for the first build.

Sign in with `gh auth login` first unless repo-bootstrapper already configured
GitHub access. The embedded PostgreSQL dependency needs authenticated GitHub
API access during compilation. Azure CLI is installed, but Azure login is not
required to bootstrap or build.
