# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - TBD

### Added
- Initial release.
- `SplitBrainDetector` algorithm with union-find over mutually-visible relation.
- `PeerViewFetcher` HTTP-based out-of-band cluster view collection.
- `HazelcastLifecycleWatcher` for `MERGING`/`MERGED` forensic events.
- Alerters: Log, Webhook (Slack/PagerDuty/OpsGenie), Dynatrace (`events.ingest`).
- Composite alerter with severity-based deduplication.
- `PartitionSimulator` with soft (in-process) and hard (iptables) modes, gated behind `SB_SIMULATION_ENABLED`.
- REST API: `/sb/cluster/view`, `/sb/status`, `/sb/events`, `/sb/health`, simulation controls.
- 4-node docker-compose demo with split/heal/status scripts.
- Comprehensive unit tests covering healthy clusters, transient flux, asymmetric partitions, full splits, recovery.

[Unreleased]: https://github.com/mirzafasi/vertx-split-brain/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/mirzafasi/vertx-split-brain/releases/tag/v0.1.0
