# Privately licensed city-building content (D-116)

**Status:** policy and technical boundary; not legal advice. Reported by the owner on October 21, 2026. The repository does not hold the permission itself and cannot verify it.

## What the owner reports

The owner contacted the creator of the custom city-building content used by DeceasedCraft (credited in that pack as **C70**) and his team. They report that C70 and his team **explicitly granted permission to use their content in a private modpack for the owner and one other player**, and confirmed that **private modifications and porting are permitted**, on these conditions:

1. The modpack stays **private**.
2. Their protected assets and world-generation files stay **Git-ignored and outside the public repository**.
3. **No ready-to-play modpack** containing their content is published.
4. **No reproducible server package or public installation instructions** that would in effect distribute the private content are published.
5. If Underground City is ever published, their content is **not included without separate authorization**.

The permission covers **C70's team's content only**. Every other author's mod or asset (the furniture mods their palettes use, Lost Cities, Create-dependent blocks and so on) is judged on its own licence under [third-party-policy.md](third-party-policy.md).

**What the owner should keep:** the written message (or a screenshot of it) in a place of their own, not in this repository. The pack manifest (`uc-private-pack.json`) names who gave the permission and what it covers, so a private pack always carries a reminder of why it may exist.

## The boundary

| Where | Private content may be there? |
| --- | --- |
| This repository: tracked files, commits, history, pull-request patches | **Never.** Not the content, not a converted derivative, not a palette, a part list, a building definition, a block list or a screenshot of one. |
| Public CI: logs, artifacts, caches, summaries | **Never.** CI runs without any private content, on invented fixtures. |
| A world save, the Core jar, a datapack in this repository, a server package | **Never.** The game reads private packs from where they are; nothing is copied into a world or a jar. |
| A folder on the owner's computer outside every checkout (`%USERPROFILE%\UndergroundCityPrivate` on Windows) | **Yes.** This is where the content and its ports live and are backed up. |
| The Claude Code cloud container, in a folder outside `/home/user/Underground-City` | **Yes, for the length of a session.** The container is reclaimed after a period of inactivity; see [the workflow](../technical/private-content-workflow.md). |
| The owner's own private repository or storage | The owner's decision; nothing here depends on it. |
| A screenshot of private content | Only in a private page the owner can open (never committed, never a public artifact). |

## What enforces it

A `.gitignore` is not enough: a forced add, another clone or a renamed folder walks past it. So:

* `content/private-content-policy.json` lists the private namespaces, the private source, forbidden path patterns and the `.gitignore` lines that must exist.
* `scripts/check_private_content.py` fails on a tracked or staged private path, on a private namespace or the private source named in tracked data or scripts, on a workflow that uploads a private folder or names the private source, on a missing `.gitignore` line, and on **any commit in the history** that ever touched a private path. It prints the rule and the path, never a line of content. It runs in CI (`Repository checks`) and as `pre-commit` and `pre-push` hooks (`scripts/install_git_hooks.py`).
* `tests/test_private_content_guard.py` makes real git repositories with each violation and proves the guard sees it.
* **No script here downloads the private content or names its source.** The guard fails a script or a workflow that does.
* `PrivateContent` (Core) loads private packs only from a folder outside every checkout, only with a manifest that says `private_only` and names the permission, and logs counts, never names or contents.

## What has not been done

* **No private content was available to this repository or tested.** Everything above is generic infrastructure proven with invented fixtures. Do not read "integrated" anywhere in the documents as "C70's buildings are in the game".
* The earlier policy line that DeceasedCraft's custom structures were not to be extracted "by default" ([third-party-policy.md](third-party-policy.md), rule 4) stands for the public repository and for every person other than the owner: private use under this permission is the only exception.
