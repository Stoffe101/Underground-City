# Claude Code instructions: Underground City

This repository is a private-play, semi-modern Minecraft co-op living-world RPG in pre-production. **Never treat the modpack as playable or the candidate platform as finalized without evidence.**

Before editing files, read and follow:
1. `AGENTS.md` (canonical automation and quality rules).
2. `README.md` and `docs/README.md` (project status and documentation map).
3. `docs/production/decisions.md` and `docs/production/mvp-v0.1.md` (design decisions and first playable slice).
4. Relevant current `docs/technical/`, design, and legal documentation for the task.

## Development

- Reuse compatible, appropriately licensed mods before writing replacement mechanics. Verify exact Minecraft version, mod loader, dependencies, and licences.
- Minecraft 1.20.1 / Forge 47.x / Java 17 is a candidate baseline, not an immutable requirement. Preserve this distinction in documentation and code.
- Keep money, taxation, city/faction control, reputation, professions, and progression server-authoritative and persistence-safe. Preserve two-player + NPC progression.
- Keep changes incremental and reviewable. Use short descriptive branch names (for example `feature/mcp` or `fix/build`); do not force-push to or merge directly into `main`.
- Run appropriate lint, unit/integration, build, and headless tests using the **cloud environment and CI**, not the user's PC. Never represent headless server tests as proof of Minecraft client UI or multiplayer quality.
- Update relevant documentation, tests, and third-party notices/registers along with substantive changes. Report what was verified, what failed, and what still requires manual in-game testing.
- Never commit tokens, private keys, proprietary third-party assets, external mod JARs, or other dependencies without explicit rights.

## Minecraft research tools

The project-level `.mcp.json` declares ModLens (Minecraft source/JAR inspection) and a read-only Modrinth research workflow (public searches work without a token). See `docs/technical/claude-cloud-tools.md`.

Treat these as **research aids**, not as proof of mod compatibility. Cross-check metadata against released files, changelogs, official documentation, licences, and actual build/server tests. Do not publish to Modrinth or upload project source/mod files to third-party services without explicit permission.

The cloud image includes Java 21 for ModLens, but a Forge 1.20.1 Gradle build may need Java 17; verify and configure the correct Java toolchain before building.
