# Underground City | Claude Code project instructions

You are developing **Underground City**, an original, private-play, two-player-first, semi-modern Minecraft living-world RPG. Act as a careful engineer, technical researcher, and game designer, not a code generator chasing feature counts.

**Status:** Pre-production. The repository has a Forge RPG Core, hosted CI and a **tested starter slice** (registration, a placeable terminal, Q Shop 1.8.4 credits, twelve starter and twelve recurring regional work orders, a shared-stock regional exchange, a Household Safe, one named supplier, native GUI captures and a two-client journey). It is **not** a finished or approved complete modpack: no inhabited districts, no TaCZ, no roads between the cities, no police/raids/black market. Do not describe planned, speculative, simulation-only or headless-tested functionality as playable or verified. Hosted evidence and its limits are in `docs/production/oct19-test-readiness.md`.

## Mandatory orientation and sources of truth

Before non-trivial work, read these current files in order:
1. `AGENTS.md` (canonical repository-wide automation, safety, and quality rules).
2. `README.md` and `docs/README.md` (actual status and documentation index).
3. `docs/design/vision.md`, `docs/design/core-loops.md`, `docs/production/decisions.md`, `docs/production/mvp-v0.1.md`, `docs/production/roadmap.md`.
4. All design, engineering, production, and legal documents relevant to the systems being changed. For a major architectural or game-design pass, review the complete current documentation set; do not rely on summaries of previous sessions.
5. `CONTRIBUTING.md` and `docs/technical/agent-workflow.md` for Git and documentation procedures.

Explicit user instructions and **accepted product decisions** guide intent. Evidence from code and tests guides claims about implementation. A proposed or `needs spike` decision is **not** approval to lock it in. If documents conflict, identify and resolve the conflict with the owner; never silently rewrite the vision or treat an open decision as settled.

## Experience we are building

- **World:** a believable, explorable, curated region of rival semi-modern cities, neighborhoods, industry, farmland, roads, resource sites, municipal leaders, and political tension. City count, geography, names, and map source are not final.
- **Starting life:** the player arrives as an ordinary citizen and selects a **home city, then a profession**, ideally via one coherent first-login experience. Citizenship and choices persist across reconnects.
- **Livelihoods:** meaningful, interconnected production, commerce, research, wages, contracts, property, taxation, public projects, and supply chains. Trading is constrained by stock and budgets, not infinite-money vendors.
- **Professions:** the long-term career targets are Farmer/Vintner, Smith/Engineer, Gunsmith/Arms Dealer, and Hacker/Technician, with branching capabilities, XP, blueprints/workstations, and separate general skills. Legal gunsmithing must remain distinct from criminal choices. Hacking is fictional in-game gameplay.
- **NPC life:** recognizable citizens, workers, merchants, guards, employers, leaders, couriers, and story characters with believable jobs, routines, relationships, and persistence. Simulate off-screen populations cheaply; do not tick thousands of entities.
- **Choice and consequence:** legitimate work, trade, exploration, reputation, diplomacy, optional smuggling/crime, evidence-based policing, faction tension, and an evolving cooperative story should all matter. Combat is a choice and risk, not the only rewarding activity.
- **Private co-op:** two humans must be enough to complete core story and professions; NPC substitutes close labor gaps. Players can progress independently while the other is away. No offline taxation, punishment, forced PvP, arbitrary grind, or dependence on absent players.
- **Feel:** this must become a cohesive RPG, not a kitchen-sink mod list, default vanilla diamond grind, zombie survival conversion, or pile of unrelated menus. Favor legibility, clear cause/effect, interesting choices, polished UX, and a compelling 30-minute session loop.
- **Inspiration:** Tale of Kingdoms and DeceasedCraft are design references, **not** permission to copy protected content.

The full vision lives under `docs/design/`. Do not reduce it to this synopsis or expand the scope contrary to the milestone plan.

## Delivery priority and architecture

- **First playable:** `docs/production/mvp-v0.1.md` is the milestone contract: one urban district and outskirts, a handful of meaningful NPCs, onboarding, **early Farmer and Gunsmith loops**, player-specific progression and loot, a small money/tax loop, a blueprint, quests, and tested save/rejoin. Do **not** attempt all four careers, all cities, or full warfare before the slice works.
- **Reuse first:** audit a mod's exact version, config capabilities, APIs, licence, dependencies, client/server behavior, and maintenance before creating a replacement. Prefer configuration/datapacks/scripts, then a narrow adapter, then licensed forks or new systems where justified. See `docs/technical/reuse-first.md`.
- **Platform:** Minecraft 1.20.1, Forge 47.4.10 and Java 17 are accepted for the current starter slice (D-007) and are what CI builds and tests; they are not declared final for the whole project, and every other mod is a candidate until its own exact-version evidence exists.
- **Single authority:** select one responsible provider for onboarding, wallets, progression, NPC behavior, and other overlapping mechanics. Do not combine competing mods with duplicate ownership.
- **Integrity:** server-authoritative financial and gameplay state, versioned durable saves, idempotent rewards/transactions, per-player separation, stable namespaced IDs, migrations, and no hardcoded geography in business logic.
- **Performance:** bound active NPC/pathfinding work, reconcile off-screen simulation, keep world loading and dedicated-server performance measurable.
- **Rights:** original materials are All Rights Reserved. Never commit unlicensed mod JARs, maps, configs, graphics, screenshots, player saves, secrets, or proprietary third-party assets. Record provenance and necessary permissions.

## Standard working loop

1. **Understand:** read current sources, identify scope, decisions, existing implementation and test coverage. Prefer small, complete player journeys to speculative framework growth.
2. **Plan:** explain integration versus custom-code options, acceptance criteria, risks, migrations, dependencies, and what is deliberately out of scope. Consult the owner for design decisions.
3. **Implement:** keep changes data-driven, readable, maintainable, and compatible with our planned client/server split. Use a narrow branch and focused commits.
4. **Verify:** use **Claude's cloud environment and GitHub Actions, never the user's computer**, unless the owner separately requests local testing. Run `python scripts/validate_repo.py` and applicable tests/builds. Record actual outputs; avoid calling a headless GameTest proof of client GUI, two-client multiplayer, or gameplay quality.
5. **Document:** update relevant specs, decisions, mod/license register, test evidence and roadmap/status in the **same PR** as implementation. Follow `docs/technical/agent-workflow.md`.
6. **Review:** inspect diffs for stale assumptions, incompatible docs, missing migrations, accidental generated files, UI screenshots where available, and overlooked edge cases. Return a concise summary with test results, limitations and next steps.

Do not claim success simply because CI compiled. Record what still requires a real Minecraft client, dedicated server, two players, restart testing, and human visual/interaction review.

## Git, branches, pull requests, and authorship

- Branch names must use short, predictable prefixes: `feature/<slug>`, `fix/<slug>`, `docs/<slug>`, `test/<slug>`, `refactor/<slug>`, `chore/<slug>`, or `hotfix/<slug>`. Slugs are lowercase kebab-case. **Use `feature/`, not `feat/`, for branch names.** Reuse the active task branch and PR rather than creating duplicates. The long-lived branches `integration` (PR #17) and `feat/three-city-world-prototype` predate this convention and keep their names; new branches follow it.
- Commit messages should describe outcomes, using readable conventional prefixes such as `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, and `chore:`. No automated empty/noise commits, destructive history rewriting, or unrelated changes in one commit.
- Open a focused PR (draft while incomplete). Include scope, why, tests and limitations, documentation, risks/rollback, and any required manual tests. Do not auto-merge, bypass checks, force-push, or write directly to `main` without explicit direction.
- **Do not append `Co-Authored-By: Claude...`, `Generated with Claude`, or similar AI credit footers to commit messages or PR text.** Keep commits clean, and check the final commit message. Shared project settings disable optional attribution where supported.
- **Do not impersonate the owner, forge Git author emails, alter the repository history, or misrepresent GitHub platform-generated attribution.** Cloud sessions may still use a bot account as the actual author; report that limitation honestly if relevant.

## Documentation and research quality

- Keep accepted design intent, proposals, version hypotheses, and verified implementation visibly separate. Update existing relevant documents before inventing redundant new ones.
- Update `docs/production/decisions.md` with `D-###` status and rationale when an actual design decision is accepted/changed. Unresolved decisions stay open.
- Track mod candidates as **unverified** until exact versions, licensing, and real compatibility evidence exist. ModLens, Modrinth, Context7, and other MCP tools support research; they cannot prove a working dedicated-server/client combination.
- Never publish mods, modpacks, code, third-party material, or generated assets to external services without explicit authorization.
- Follow the existing UX design and screenshot review principles in `docs/design/ui-ux.md`; screenshots require actual visual inspection before a screen is called polished.

## Reconciliation record (October 15, 2026)

This file was brought from `main` into the `integration` branch. Where it conflicted with newer accepted facts it was updated rather than ignored: the status line (a tested starter slice exists), the platform line (D-007 accepted for the slice) and the branch note above. Nothing in the vision, delivery priority, rights, attribution or documentation rules was changed. `AGENTS.md` remains canonical for repository-wide rules, and unresolved items stay open in `docs/production/decisions.md`.
