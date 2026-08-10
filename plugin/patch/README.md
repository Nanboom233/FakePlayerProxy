# Velocity Patch Build

IntelliJ IDEA exposes only two shared configurations:

- `server/releaseJar` builds patched Velocity and the plugin into
  `plugin/build/server/release/`.
- `server/runServer` runs that release from `plugin/run/`.

Patch application occurs only in the disposable
`plugin/build/server/source/` checkout. It never modifies a developer Velocity
checkout.
