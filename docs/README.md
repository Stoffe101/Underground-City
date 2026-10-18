# Documentation index

The docs here distinguish **confirmed product direction**, **design proposals**, **engineering hypotheses**, and **tested implementation**. Everything is pre-production unless proven by tests.

## Design

- [Vision, pillars, scope](design/vision.md)
- [Core gameplay loops](design/core-loops.md)
- [Cities, factions, and world](design/cities-and-world.md)
- [Professions and research](design/professions.md)
- [General skills and progression](design/general-skills.md)
- [Economy and civic systems](design/economy.md)
- [NPC population and simulation](design/npcs.md)
- [Crime, policing, and conflict](design/crime-and-war.md)
- [Exploration and loot](design/exploration-and-loot.md)
- [Burglary and property gameplay](design/burglary-and-property.md)
- [Progressive property restocking](design/gradual-property-restock.md)
- [Dangerous crossings and inter-city progression](design/intercity-progression.md)
- [Repeatable supplies and profession work orders](design/recurring-supply-chains.md)
- [PC terminal, risky deliveries, black-market dropboxes, and raids](design/terminal-and-underworld.md)
- [Narrative and quests](design/story.md)
- [Crews: shared homes and cooperative finances](design/crews.md)
- [UX and accessibility](design/ui-ux.md)

## Engineering

- [High-level architecture](technical/architecture.md)
- [Gameplay data contracts](technical/data-contracts.md)
- [Mod and loader audit](technical/mod-audit.md)
- [Compare candidate modpacks and installed JARs](technical/modpack-comparison.md)
- [Map creation and imports](technical/world-pipeline.md)
- [Experimental three-city generator, generated roads and bridges, seed choice, and loot](technical/three-city-prototype.md)
- [Development pack: TaCZ, TaCZ Blueprints, Modern Life and Immersive Vehicles (hash-pinned, runtime-only)](technical/dev-pack.md)
- [Reuse-first mod and modpack policy](technical/reuse-first.md)
- [First-join class and city selection candidates](technical/onboarding-reuse-spike.md)
- [Pinned first-login test profiles and automation](technical/onboarding-screening-packs.md)
- [Crew accounts: who owns what, how money moves, what is proven](technical/crew-economy.md)
- [Item-source audit: no work order may ask for something a player cannot get](technical/item-source-audit.md)
- [Testing and reliability](technical/testing.md)
- [AI-assisted development, docs and PR workflow](technical/agent-workflow.md)
- [Claude Code cloud MCP setup](technical/claude-cloud-tools.md)

## Production

- [Roadmap](production/roadmap.md)
- [First playable milestone](production/mvp-v0.1.md)
- [Decisions](production/decisions.md)
- [Risks](production/risks.md)
- [Backlog](production/backlog.md)
- [Existing modpack base comparison](production/base-comparison.md)
- [First-join provider test matrix](production/onboarding-tests.md)
- [Expansion plan: the next twelve work packages, with proof levels (a proposal)](production/expansion-plan.md)
- [Playtest guide: two players on Windows 11](production/playtest-guide-windows11.md)
- [Sprint report, October 17, 2026: the living world, profession depth and a production-quality visual overhaul](production/sprint-2026-10-17.md)
- [Sprint report, October 18, 2026: Crews, shared homes and finances, survival, the Gunsmith's component loop](production/sprint-2026-10-18.md)

## Legal and assets

- [Third-party use policy](legal/third-party-policy.md)
- [Asset/source register](legal/asset-register.csv)
- [Project license](../LICENSE)
- [Third-party notices](../THIRD_PARTY_NOTICES.md)

**Update convention:** Proposed mechanics are not described as working. Technical assumptions are not described as verified. Each candidate mod requires its own dedicated-server test and license review.

- [Visual QA and screenshot cleanup](technical/visual-qa.md)

- [Development client and server: `gradlew runClient` with the complete pack](technical/dev-client.md)
- [Legal and underground career paths (skill trees, contracts, city law, evidence)](design/legal-and-underground-careers.md)
- [Sprint report, October 16, 2026: the first playable experience (sections A to O)](production/sprint-2026-10-16.md)
- [Sprint report, October 15-16, 2026 (sections A to O)](production/sprint-2026-10-15.md)

- [October 19 trial-build and two-player test checklist](production/oct19-test-readiness.md)

- [Real economy, work orders, recovery and named supplier](technical/economy-integration.md)
