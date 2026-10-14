# UI/UX principles and player onboarding

## Style direction (proposed)

Semi-modern diegetic game interfaces: clean city registration, municipal kiosks, handheld communication devices, profession journals, business dashboards, blueprints, and in-world terminals. Avoid generic cluttered Minecraft mod menus.

## First login flow

Title/introduction → city selection with map, risk and economy summary → career selection with activities and specialization preview → accessibility/difficulty preferences (where practical) → final review → tutorial entry point. Selections persist; reconnect never silently re-registers.

## Major screens

1. City/citizenship profile: home, reputation, tax status, laws, mayor and projects.
2. Career tree: skill branches, level progress, points, prerequisites, actual ability unlocked.
3. General skill tree: separate categories, clear progress sources, few high-impact milestones.
4. Blueprint/research journal: known/unknown, workstation, ingredients, job level and what unlocks it.
5. Contracts and business: job conditions, stock/reserves, payment, deadlines, delivery and history.
6. Police/legal: jurisdiction, licensed/illegal goods, wanted status, evidence, fine and how to resolve.
7. World journal: story quests, location discovery, contacts and faction relations.

## Usability rules

- At 1920x1080 and 2560x1440 with varying GUI scales: no overlapping panels, clipped icons, hidden buttons, or mandatory sideways scrolling.
- Accessible typography, color + icon status indicators, keyboard navigation, hover/focus detail, tooltips and scroll containment.
- Make every unavailable action explain *why*: insufficient career level, license, reputation, materials or funds.
- Strong UI feedback on transaction success/failure and skill unlock; no duplicate sound or delayed phantom reward.
- Major screens must be screenshot-reviewed against an agreed design reference, not marked complete based on compilation alone.
- Localization-friendly data and no hardcoded text in gameplay logic.

## Screen review workflow

Capture initial, populated, empty, locked, overflow, low-resolution, large-GUI-scale, and error states. Include reproduction steps, before/after images, pixel overflow checks and interaction feel review.
