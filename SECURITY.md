# Security Policy

## Reporting a vulnerability

If you believe you have found a security issue in vertx-split-brain, **please do not open a public issue.** Instead, use GitHub's private vulnerability reporting:

1. Go to the repository's **Security** tab
2. Click **Report a vulnerability**
3. Provide a clear description, reproduction steps, and the affected version

You can expect an initial response within 5 business days. If the issue is confirmed, we'll work on a fix and coordinate disclosure with you.

## Scope

In-scope:

- The detection algorithm producing incorrect results that could mask real problems
- Authentication/authorization gaps on the management API
- The simulator being usable in production despite the safety flag
- Dependency vulnerabilities affecting runtime

Out-of-scope:

- Issues in upstream projects (Vert.x, Hazelcast) — report those upstream
- Configuration mistakes by users (e.g. exposing `/sb/simulate/*` to the public internet)

## Hardening recommendations for operators

- Restrict the `/sb/simulate/*` endpoints with a reverse proxy or auth filter in any non-test environment
- Never set `SB_SIMULATION_ENABLED=true` outside dedicated test clusters
- Use a dedicated Dynatrace API token with only `events.ingest` scope
- Run with a non-root user; the published image grants `NET_ADMIN` only for the iptables simulator and you can drop it in production deployments
