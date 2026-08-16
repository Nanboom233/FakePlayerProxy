# Velocity Patch Build

IntelliJ IDEA exposes only two shared configurations:

- `server/releaseJar` builds patched Velocity and the plugin into
  `plugin/build/server/release/`.
- `server/runServer` runs that release from `plugin/run/`.

Patch application occurs only in the disposable
`plugin/build/server/work/` worktree. The pinned
`plugin/build/server/source/` checkout remains clean.

Production changes are stored in exactly two top-level functional patches:

- `0001-login-relay.patch`
- `0002-automation-extension.patch`

Both are standard Git unified diffs generated with fixed `-U80` context. The build
discovers only these two files, sorts them by filename, and applies them sequentially
with ordinary `git apply`; `0002` is therefore based on the tree produced by `0001`.

Patch headers remain relative to the Velocity root, such as `a/proxy/...` and `b/api/...`.
Patch tests remain outside the functional patches under `plugin/patch/test/` and are
copied only by the patch-check task.
