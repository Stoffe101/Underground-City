# Claude Code cloud Minecraft MCP setup

Our root `.mcp.json` pins two third-party research tools. They are optional research aids, **not proof of a tested, playable modpack**:

| MCP | Pinned version | Purpose | Verification (2026-10-15) |
| --- | --- | --- | --- |
| Modrinth | `@justinscott12/modrinth-mcp@0.1.2` | Search public mods, versions and dependencies | **Search worked:** Forge 1.20.1 economy search returned results |
| ModLens | `@creeperhost/modlens-mcp@1.6.29` | Minecraft/mod metadata, JAR introspection and compatibility research | **Partial:** cold `npx` timed out; after npm preinstall it initialized in ~1.8 s and read-only tools worked. Forge version listing failed via Node fetch |

Independent community tools may change or become unavailable. Do not use write/publishing features or assume a successful metadata lookup means mods work together.

## Claude Code cloud environment (no user-PC installation)

1. Go to https://claude.ai/code, use the cloud environment selector and edit or create **Underground City**.
2. Set **Network access: Custom**. Allow `api.modrinth.com` and **also include the default list of common package managers**. If a specific Forge-related download is blocked, diagnose the **exact request host and error** before permitting that host. Do not make the whole internet accessible by default.
3. In the environment's **Setup script** field, paste:

   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   npm install --global --no-audit --no-fund @creeperhost/modlens-mcp@1.6.29
   ```

   This pre-installs the package before Claude Code launches instead of relying on a slow cold MCP `npx` install. Our `.mcp.json` still uses pinned `npx` so local environments retain a fallback and the warm cache may be reused. This setup script runs **in Anthropic's cloud**, not on the player's PC. Confirm actual tool initialization in a new cloud session; package installs and caches can still fail.
4. Keep `MODRINTH_TOKEN` **unset**; public search requires none, and this private project does not authorize publishing. Do not put tokens in the repo or setup script.
5. Save and start a **new cloud session** using the repository branch that includes the latest `.mcp.json` and `.claude/settings.json`, or merge the changes into `main` first. Anthropic caches successful setup scripts and their installed packages in environment snapshots; initial setup may still take tens of seconds.

GitHub access is supplied by Claude Code's normal GitHub integration. The separate GitHub MCP is not required.

## Modrinth write protection

Project-level `.claude/settings.json` denies the following MCP tools:
- `mcp__modrinth__modrinth_create_project`
- `mcp__modrinth__modrinth_create_version`
- `mcp__modrinth__modrinth_modify_version`

Keep this list updated when upgrading the package or if publishing actions change names. These permission denies are a safeguard, not a complete sandbox against arbitrary shell/API requests. Never provide a Modrinth publishing credential without explicit consent to publish.

## ModLens Forge request still needs diagnosis

Claude confirmed that ModLens initializes after installation and exposes tools, but its `mc_versions list_forge` request for 1.20.1 failed with **Node `fetch`**, while `curl` to Forge hosts succeeded. This does **not** establish whether the cause is the cloud proxy, a certificate/Node networking difference, a different request host, or a ModLens implementation issue.

Ask Claude to investigate without altering repository files:
1. Determine the **exact URL and response/error** that the `list_forge` handler requests, examining installed ModLens source as needed.
2. Compare that URL using `curl` and a minimal Node `fetch`, with proxy environment names and values redacted as necessary. Check DNS, TLS, redirect and allowed-host behavior.
3. Identify the narrowest verified workaround, and test the same **read-only** Forge version lookup afterwards. Do not invent working compatibility results or widen outbound network access indiscriminately.
4. If the ModLens lookup remains broken, use verified Forge Maven metadata and an exact-version dependency audit as a fallback. The candidate `Minecraft 1.20.1 / Forge 47.x / Java 17` stack remains a hypothesis until integration evidence exists.

## Read-only verification prompt

> Use the cloud environment only. Read `AGENTS.md`, `CLAUDE.md`, and the current docs. Check Modrinth and ModLens MCP startup, list the available tool names, run a read-only Modrinth Forge 1.20.1 search, and run ModLens read-only metadata and Forge version queries. Confirm the Modrinth publishing tools are denied. Report connection time, exact error classes/hosts, and tests actually performed. Do not modify files, branches, commits, PRs, gameplay, or the user's PC. Do not install/download third-party mod JARs or publish anything.

## Safety and persistence

- ModLens local SQLite/cache data in the cloud is disposable; keep dependency decisions and evidence in Git documentation instead.
- It can require Java 21 for its tooling, but Forge 1.20.1 builds may require Java 17. Verify/set the correct Gradle toolchain.
- Do not store secrets, copyrighted third-party JARs, copied mod assets, or private world saves in the repository.
- A headless build/GameTest is not evidence of actual Minecraft client GUI, cooperative gameplay, or dedicated-server compatibility.
- Unrelated connector 403/authentication errors (Asana, Notion, etc.) have no bearing on the Minecraft research MCPs. Disable unused connectors for less noise.

Useful documentation: https://code.claude.com/docs/en/cloud-environments , https://code.claude.com/docs/en/permissions , https://github.com/CreeperHost/modlens-mcp , https://github.com/justinscott12/modrinth-mcp
