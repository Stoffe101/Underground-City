# Contribution and development workflow

Underground City is a private-play hobby project with original material licensed **All Rights Reserved**, not an open-source project. Contact the repository owner before proposing outside contributions; no general license for contributions or reuse is implied.

## Working rules

- Make one focused change per feature branch, e.g. `feat/registration-screen`, `docs/professions`, `test/mod-compatibility`.
- Open a draft PR early. Do not merge untested mods, copied map content, or breaking save changes.
- For design decisions, update the relevant doc and [decision log](docs/production/decisions.md). For implementation, include test evidence.
- Store configs, recipes, quests, and gameplay values in versioned, human-readable data. Do not hardcode map coordinates into feature logic.
- Add exact dependency versions, source URLs, license information, client/server classifications, and known conflicts to the mod audit.
- Treat all world changes as potentially destructive. Back up and test on a copy; document migration/rollback.
- Test co-op with two independent accounts and a dedicated server. Singleplayer-only success is insufficient.
- Before calling a feature complete, inspect UI screenshots, reproduce user journeys, confirm persistence after restart, and check logs.

## Definition of done

Acceptance criteria fulfilled; no new errors; documentation updated; basic performance and security checks passed; multiplayer confirmed where applicable; rollback/migration documented; no third-party content committed without permissions.

## Automation

Run `python scripts/validate_repo.py` before opening a PR. GitHub Actions runs the same repository check, with read-only access. More build and integration tests will follow once the platform is locked.
