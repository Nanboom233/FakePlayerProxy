# Velocity Patch Build

Use the Velocity source from `https://github.com/PaperMC/Velocity.git`.
The fixed commit is stored in `velocity-base.properties`.

IntelliJ IDEA exposes two shared configurations.
Use `server/releaseJar` to build patched Velocity and the plugin into
`plugin/build/server/release/`.
Use `server/runServer` to run that release from `plugin/run/`.

The build applies patches only in a disposable local clone at
`plugin/build/server/work/`. It keeps the pinned
`plugin/build/server/source/` checkout clean.

Store production changes in exactly two top-level functional patches:

- `0001-login-relay.patch`
- `0002-automation-extension.patch`

Both files are standard Git unified diffs with fixed `-U80` context. Their headers
are relative to the Velocity root, such as `a/proxy/...` and `b/api/...`.
The build sorts the files by name and applies them with Grgit. Thus, `0002` is
based on the tree that `0001` produces.
Patch tests stay in `plugin/patch/test/`. The patch-check task copies them.
