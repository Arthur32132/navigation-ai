# Contributing to Navigation AI

Bug reports, forks and pull requests are welcome in English or Russian. This is
an experimental Fabric/Minecraft library; historical test results only describe
their recorded revisions and are not acceptance of the latest source.

## Setup

Install Git and JDK 25, with `JAVA_HOME` pointing to that JDK. Fork this repository,
clone your fork, and create a focused branch with `git switch -c my-change`.
The Gradle wrapper downloads Gradle and dependencies on the first build, so
omit `--offline` on a fresh checkout.

```powershell
./gradlew.bat test build --no-daemon
```

On Linux/macOS use `sh ./gradlew test build --no-daemon`. Outputs are in
`build/libs`; versions are pinned in `gradle.properties`. See
[integration](docs/INTEGRATION.md), [API](docs/API.md),
[architecture](docs/ARCHITECTURE.md) and [regression tools](tools/regression/README.md).

For integration with [Autonomous Villages](https://github.com/Arthur32132/autonomous-villages),
clone this project as `MinecraftmodNavigationAi` beside `AutonomousVillages`.
Follow that project's contributor instructions for the expected dependency
revision, disposable-world runners, exclusive Minecraft process lock and disk
budgets. Never run simultaneous test clients/servers or use original saves as
disposable labs. Follow applicable workspace/AGENTS.md restrictions; documentation
of a command is not permission for an agent to launch a game.

## Pull requests

Search existing issues before reporting bugs. Discuss major API or navigation
changes first, then open a PR targeting `main`. Describe the concrete problem,
resulting behavior, thread ownership, persistence compatibility and affected
consumers. Link dependent PRs and exact revisions. Add meaningful regression
coverage for behavior changes and record exact validation commands and results;
state when builds, tests or game checks have not run. A maintainer reviews and
decides whether to merge.

Run `git diff --check`. With Gitleaks installed, scan history and staged changes:

```sh
gitleaks git . --log-opts="--all" --redact --max-archive-depth 2
gitleaks git . --staged --redact --max-archive-depth 2
```

Only exact SHA-256 entries for Java files are excluded by scanner configuration.
Do not commit secrets, private player data, game binaries, decompiled Minecraft,
copied worlds/profiles, caches or complete private logs. Keep evidence small and
redacted. Report vulnerabilities according to [SECURITY.md](SECURITY.md).

Be respectful, give reproducible feedback and credit others. Contributions use
the existing [MIT license](LICENSE); retain attribution and compatible licenses
for third-party work. Minecraft, Fabric and dependencies retain their licenses.
Disclose generated code/assets and how they were reviewed when relevant.
