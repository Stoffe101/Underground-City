# Onboarding provider acceptance record

Three candidates have been screened on a disposable Forge GameTestServer, but **none has been installed in a real client or accepted as the final provider**. Tests refer to exact versions, not promotional descriptions.

| Test | Class Selection Mod | Class Enhancement | RoleCraft | Origins stack | Other |
| --- | --- | --- | --- | --- | --- |
| Exact MC 1.20.1 Forge file + dependencies | Not run | Not run | Not run | Not run | Not run |
| Headless Forge GameTestServer boot | Passed | Passed | Not run | Not run | Role Selection passed |\n| Client + two-player dedicated server experience | Not run | Not run | Not run | Not run | Not run |
| Config loaded without reverting authored settings | Three city sides passed | Four professions and icons passed | Not run | Not run | Role Selection default-only |
| Two fresh clients, one-time selection | Not run | Not run | Not run | Not run | Not run |
| Different players independent after restart | Not run | Not run | Not run | Not run | Not run |
| No starter kit duplication / exploit | Not run | Not run | Not run | Not run | Not run |
| Modded TaCZ and profession/skill mod integration | Not run | Not run | Not run | Not run | Not run |
| Server can read identity through supported API or commands | Not run | Not run | Not run | Not run | Not run |
| Screenshots across GUI scales reviewed | Not run | Not run | Not run | Not run | Not run |
| Save migration / recovery and mod removal | Not run | Not run | Not run | Not run | Not run |

Write exact SHA-256, mod IDs, system owner, observed screenshot/log link, and pass/fail before selecting.

The canonical provider-neutral design entries are [content/onboarding/catalog.json](../../content/onboarding/catalog.json). UI tests do not establish that the professions or cities themselves exist.

## Headless evidence

- [First three candidate server boots: all passed](https://github.com/Stoffe101/Underground-City/actions/runs/38587078847). This proves initial runtime loading on Forge 47.4.10 with experimental RPG Core, plus one Core persistence GameTest; it does **not** test a real first-login GUI or two clients.
- [Config acceptance follow-up](https://github.com/Stoffe101/Underground-City/actions/runs/38588047141). Class Selection's original three-city config was accepted; Class Enhancement correctly generated extra Forge defaults but the first version of our validator overcompared the whole section. [The corrected four-profession config and icon checks passed](https://github.com/Stoffe101/Underground-City/actions/runs/38588532819).
