# SecurityCraft trial configuration (D-140)

Original configuration files written for this project. Nothing from SecurityCraft itself is here; the mod is a runtime-only, hash-pinned download
(`content/modpacks/dev-pack.json`, set `securitycraft`) that no default launch includes.

- `config/defaultconfigs/securitycraft-server.toml`: copied into a new world's server config by the development launch. It turns off the codebreaker, the admin
  tool, in-world reinforcing, camera feeds in frames and their chunk force-loading, and every source of damage that configuration can reach.
- `config/config/securitycraft-client.toml`: the chat greeting off.

What configuration cannot turn off (sentries, mines, the I.M.S., tasers, the inventory scanner) Core keeps out at run time: SecurityCraft recipes are removed, a
player cannot place a SecurityCraft block or use a SecurityCraft item other than the camera monitor, the manual and keycards, and SecurityCraft creatures never
enter the world (`SecurityCraftTrial`). See `docs/technical/securitycraft-trial.md`.
