# AI-assisted development workflow

Use this alongside [`CLAUDE.md`](../../CLAUDE.md), [`AGENTS.md`](../../AGENTS.md), and [`CONTRIBUTING.md`](../../CONTRIBUTING.md). This guide applies to Claude Code and can also inform other code assistants. The project owner makes unresolved design decisions.

## Truth and precedence

- **Accepted product intent:** vision, design pillars, and `accepted (product intent)` entries in `docs/production/decisions.md`. Product intent is not implementation proof.
- **Proposals and spikes:** `proposed` / `needs spike` decisions and individual mod candidates, pending validation. Do not silently rewrite them into accepted decisions.
- **Verified implementation:** concrete code plus reproducible test output, exact runtime/dependency versions, and recorded logs. A successful compile is not a playable modpack or a GUI test.
- **Prior work:** treat older chat summaries and outdated documents as pointers; verify against the current repository before changing source or documentation.
- **Conflicts:** stop and report material contradictions; ask the owner before adopting a different product direction or breaking an accepted contract.

## Documentation ownership and when to update it

| Change | Update at least | Verify |
| --- | --- | --- |
| New/changed gameplay design, progression, economy, cities, NPCs, story, UI | Appropriate `docs/design/*.md`, relevant milestone/backlog, and `docs/production/decisions.md` **only if** a decision was accepted/changed | Proposed versus accepted clearly labeled; other systems considered |
| Implemented feature, API, persistence, new adapter or data format | `docs/technical/architecture.md`, `docs/technical/data-contracts.md`, module docs or content schema as relevant | Tests, migrations, version compatibility, idempotency |
| New candidate mod, dependency or integration decision | `docs/technical/mod-audit.md` and `docs/technical/reuse-first.md` if policy changes; candidate/spike notes, `docs/legal/` registers if materials are involved | Exact version, MC/Forge loader, source, licence, clients/servers, dependencies, conflicts, runtime evidence |
| Progress or milestone change | `docs/production/roadmap.md`, `docs/production/backlog.md`, `docs/production/mvp-v0.1.md` only if scope/criteria legitimately change | Never label an untested feature complete; preserve deferred work |
| New automated test, CI behavior or runtime result | `docs/technical/testing.md` and relevant CI/test documentation; PR evidence | Command, environment, version, commit/PR, observed result, reproducibility |
| Asset, world source, map generation, structure or resource import | `docs/technical/world-pipeline.md`, applicable asset register, permission/legal documents | Rights, backups, migration/rollback, screenshots/seams/pathing |
| Project-wide developer procedures | `AGENTS.md`, `CONTRIBUTING.md`, `CLAUDE.md` or this document as appropriate | No conflicting rules; no needless duplication |

**Documentation hygiene:**
1. Update docs in the **same PR** as related changes, not in an indefinitely deferred cleanup task.
2. Prefer editing a single canonical topic document and linking to it rather than copying whole specifications across many files.
3. Keep the `docs/README.md` index accurate when adding/removing/renaming documentation.
4. Use existing decision IDs and append the next unused `D-###` only for an actual decision or proposal that warrants tracking; include rationale, status and validation.
5. When new evidence invalidates an old assertion, correct it and mention the change in the PR. Preserve material design decisions in the log rather than silently erasing them.
6. Clearly distinguish **planned**, **implemented**, **unit-tested**, **headless-tested**, **dedicated-server-tested**, **two-client-tested**, and **visually reviewed**. These statuses cannot be substituted for one another.

## Standard pull-request checklist

- [ ] Read the relevant current docs, code and constraints, including the first-playable milestone.
- [ ] Choose one focused `feature/...`, `fix/...`, `docs/...`, `test/...`, `refactor/...`, `chore/...`, or `hotfix/...` branch.
- [ ] Implement only verified/approved scope; avoid parallel competing providers or speculative framework sprawl.
- [ ] Validate repository structure with `python scripts/validate_repo.py`; run applicable unit tests, dependency audits and builds in the **cloud or CI**.
- [ ] Protect private save data, secrets, source rights and unlicensed third-party binaries/assets.
- [ ] Update design/technical/production/legal docs when relevant, with correct status labels and exact test evidence.
- [ ] If runtime/UI/multiplayer tests could not be completed, list them as outstanding, without suggesting they passed.
- [ ] Review `git diff`, staged files, authored commit messages, and whether unexpected bot/AI coauthor footers were introduced.
- [ ] Open or update the task's PR with summary, reasoning, test results, screenshots where actually captured, risks, and manual checks.
- [ ] Leave merging to the owner unless expressly asked otherwise.

## Git authorship and AI attribution

The project prefers clean commit messages without auto-added AI coauthor text. A committed `.claude/settings.json` requests omission of optional Claude Code attribution: empty commit and PR strings and `sessionUrl: false`. This does **not** change actual Git author/committer metadata or guarantee GitHub's contributors view excludes cloud bot authors. Never spoof the owner's name/email to manipulate contributor statistics, delete contributions, or alter history solely to hide tool use. If the cloud provider authors commits as a bot, explain what GitHub will show rather than claiming a settings file can override it.

## Definition of done

The smallest relevant end-to-end player journey works at the appropriate test tier; data remains correct across retries and restarts; compatibility and licences have been checked; the UI is actually reviewed when it exists; required documentation and evidence are updated; and untested claims are transparently labeled.
