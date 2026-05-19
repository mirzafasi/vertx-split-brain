# Contributing to vertx-split-brain

Thanks for your interest in contributing! This project aims to be a focused, well-tested utility for one specific problem (Vert.x/Hazelcast split-brain). Contributions that keep that focus are very welcome.

## Quick start

```bash
git clone https://github.com/<you>/vertx-split-brain
cd vertx-split-brain
mvn verify             # build + run all tests
docker compose build   # build the demo image
docker compose up -d   # 4-node demo cluster
./scripts/partition.sh split   # try a split-brain
./scripts/partition.sh heal
```

You need JDK 17+ and Docker for the integration demo.

## How to contribute

### Bugs

Open an issue with the **Bug** template. Include:

- Vert.x version and cluster manager (Hazelcast version)
- A minimal reproduction (the test harness in `docker-compose.yml` is a good starting point)
- Logs from at least two nodes
- What you expected vs. what happened

### Features

Open an issue with the **Feature** template *first* so we can discuss scope before you write code. Things likely to be accepted:

- Additional alerter implementations (Slack-formatted, OpsGenie, etc.)
- Support for other cluster managers (Infinispan, Apache Ignite, Zookeeper)
- Better metrics (Prometheus, OpenTelemetry)
- Helm chart improvements

Things less likely to be accepted:

- Anything that resolves split-brain — that's the cluster manager's job (configure `MergePolicy`)
- General-purpose cluster management features outside the split-brain scope

### Pull requests

1. Fork and branch from `main` (`feat/<short>` or `fix/<short>`).
2. Add tests. The core algorithm in `SplitBrainDetector` is heavily tested; please keep it that way.
3. Run `mvn verify` locally before pushing.
4. Open a PR using the template. Link the issue.

## Code style

- Java 17 features welcome (records, switch expressions, pattern matching).
- Keep packages aligned with responsibility (`detect`, `alert`, `simulate`, `model`, `api`, `core`).
- Public APIs get Javadoc; private methods get a one-line comment if the *why* isn't obvious.
- Don't depend on the cluster manager outside the `detect/` package — keep the algorithm pure.

## Tests

```bash
mvn test                              # unit tests
mvn verify                            # unit + any integration tests
docker compose up -d                  # manual end-to-end via demo cluster
./scripts/partition.sh split          # cause a split-brain
docker compose logs | grep ALERT      # observe alerts
```

When fixing a detector bug, add a test in `SplitBrainDetectorTest` that fails before your fix and passes after.

## Releases

Maintainers tag releases as `vX.Y.Z` on `main`. The CI workflow builds and pushes the container image to `ghcr.io/<repo>:vX.Y.Z` on tag push.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). Be respectful, assume good faith, and remember everyone is here voluntarily.
