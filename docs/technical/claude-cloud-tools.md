# Claude Code cloud MCP setup

Underground City declares two Minecraft research MCP servers in the root `.mcp.json`:

| Server | Function | Package |
| --- | --- | --- |
| ModLens | Inspect mod metadata, bytecode, mod JARs, dependencies, and source where legally available | `@creeperhost/modlens-mcp` |
| Modrinth | Search public projects, project details, versions and dependencies | `@justinscott12/modrinth-mcp` |

These are independently maintained tools, not official Mojang/Forge compatibility guarantees. Read the source/permissions before enabling them. Both start using `npx` as stdio MCP processes **inside the Claude cloud VM**, not on the player's computer. No Modrinth publishing token is configured; public research requires none.

## Enable in Claude Code cloud

1. Connect GitHub through Claude Code onboarding at https://claude.ai/code and grant the Claude GitHub App access to `Stoffe101/Underground-City`. The built-in GitHub tools suffice: a separate remote GitHub MCP is unnecessary.
2. Merge the PR that introduces this file and `.mcp.json`, or select the PR branch when starting a cloud session. The config must be present in the checkout used by Claude.
3. In https://claude.ai/code, click the **cloud icon / environment name** above the input. Create a dedicated environment (e.g. **Underground City**) or edit the current environment with its settings gear.
4. **Network access:** choose **Custom**, add `api.modrinth.com` to **Allowed domains**, and enable **Also include default list of common package managers**. Keep GitHub/npm/Maven domains in the default list. If Forge dependency downloads fail, allow the specifically blocked Maven host, such as `maven.minecraftforge.net`; do not broaden all outbound access as a default.
5. **Environment variables:** leave `MODRINTH_TOKEN` unset. Do not enter API keys or credentials in `.mcp.json` or Git. No special setup script is needed for these two servers because the Claude cloud image currently includes Node.js 22 and OpenJDK 21.
6. Save the environment, select it for a new cloud session on the branch containing `.mcp.json`, and ask Claude to verify the servers and use their read-only tools.

The initial connection may take time while `npx` downloads the packages. If cloud startup fails, ask Claude to check the MCP connection status, Node version, and outbound domain restrictions. A rejected domain may return HTTP 403 with `x-deny-reason: host_not_allowed`.

## Verification prompt

> Read `AGENTS.md`, `CLAUDE.md` and the relevant technical documentation. Verify that the ModLens and Modrinth MCP servers declared in `.mcp.json` are reachable in this cloud session. Show their available tool names, search Modrinth for one candidate Forge 1.20.1 dependency without publishing, and exercise a read-only ModLens metadata/help query. Report exact tool outcomes, errors and blocked network hosts; do not invent results, modify gameplay, download copyrighted mod JARs without reviewing permissions, or run anything on my PC.

## Boundaries

- The Modrinth MCP includes **publishing tools**, but the private project should **never publish** without the owner's explicit instruction. No publishing token is supplied.
- ModLens can download helper tools and inspect local code or archives. Some features depend on downloaded JARs/extra network hosts. Restrict use to content whose access and licence permit it.
- The cloud VM may be recreated. Its ModLens SQLite cache must be considered disposable, not source-of-truth.
- The Minecraft 1.20.1 / Forge 47.x / Java 17 stack is a candidate baseline. Java 21 is appropriate for ModLens itself; Forge compilation can require JDK 17. Use the correct toolchain rather than blindly building with the cloud default.
- Continue to use GitHub Actions for reproducible builds and treat client gameplay and UI testing as unverified until tested in Minecraft.
- When these MCP dependencies are validated, consider pinning specific npm package versions in `.mcp.json` to limit unexpected upstream changes.

References: https://code.claude.com/docs/en/mcp ; https://code.claude.com/docs/en/cloud-environments ; https://github.com/CreeperHost/modlens-mcp ; https://github.com/justinscott12/modrinth-mcp
