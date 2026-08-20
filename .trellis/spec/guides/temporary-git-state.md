# Temporary Git State

Use this guide when implementation or checks need a temporary clone, worktree,
ref, or index. Project commits follow Phase 3.4 in `.trellis/workflow.md`.

## Commit boundary

- [ ] A `trellis-research`, `trellis-implement`, or `trellis-check` agent does
      not run `git commit`.
- [ ] This restriction also applies inside a temporary repository.
- [ ] Patch generation uses an index baseline, `git diff --cached`,
      `git diff --no-index`, or another comparison without a commit.
- [ ] Only the main session creates a project commit through Phase 3.4.

## Temporary state

- [ ] Resolve and record each temporary path before creation.
- [ ] Keep each path inside a project build directory or an OS temporary
      directory.
- [ ] Do not create temporary Git state inside a source directory.
- [ ] Verify the owner and contents before reusing an existing path.
- [ ] Keep temporary Git state only while an active command needs it.

## Cleanup

- [ ] Remove a temporary worktree with Git before deleting its directory.
- [ ] Remove temporary refs and prune stale worktree metadata.
- [ ] Delete each temporary repository and directory before completion.
- [ ] On Windows, clear read-only attributes only inside the resolved temporary
      path.
- [ ] Run cleanup after success and failure.
- [ ] Preserve the original validation failure if cleanup also fails.
- [ ] Report a cleanup failure as a blocker.

## Process ownership

- [ ] Treat `gradlew --stop` as a process termination command.
- [ ] Check whether Gradle owns an active server before any daemon stop command.
- [ ] Do not run a daemon stop command without explicit approval to stop its child processes.
- [ ] Do not use a daemon stop command to release a temporary Git file lock.
- [ ] If an owned process blocks cleanup, keep the process and report the cleanup blocker.

## Completion evidence

- [ ] `Test-Path <temporary-path>` returns false.
- [ ] `git worktree list --porcelain` does not contain a temporary path.
- [ ] Temporary branches and refs do not remain.
- [ ] `git status --short` contains only recognized user and task changes.
- [ ] The main project history contains no sub-agent commit.

## Example

Wrong:

```text
git commit --no-verify -m baseline-patches
Run tests and leave the temporary repository for later inspection.
```

Correct:

```text
Stage the baseline in the temporary index.
Apply the task changes and generate the patch from the unstaged diff.
Run tests, remove the temporary repository, and verify that its path is absent.
```

Wrong:

```text
Run gradlew --stop to release a JGit pack lock while runServer is active.
```

This command can stop the server because Gradle owns the server process.

Correct:

```text
Keep the active server. Report the lock and wait for explicit process-control approval.
```
