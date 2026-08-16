# Plan Writing Format

This format applies to every Trellis task plan. It describes how to write a
clear plan. It does not add an implementation gate, a test gate, or a new task.

## Plan Items

Each plan item names the source file or symbol, the target file or owner, the
caller change, and the operation. It also states the new call path or data path.

### Wrong

```text
Move lookAt out of AutomationService.
```

### Correct

```text
Use mcp__idea__analyze_calls on AutomationService.lookAt(...). Move its
calculation to world/player/Player.lookAt(...) with normal code edits because
the enabled IDEA MCP tools do not provide method-owner move refactoring. Change
FakePlayerProxyPlugin.onLookAt(...) to call Player.lookAt(...). Delete the old
AutomationService.lookAt(...) call path.
```

Plan items describe the concrete refactor operation and its resulting caller
or data path. They do not describe a structural move as an unspecified manual
file edit.

For symbol renames, name `mcp__idea_index__ide_refactor_rename`. For Java
class/file package moves, name
`mcp__idea_index__ide_move_file(file, destination, project_path)`. Do not use a
generic phrase such as "IDEA move refactor" when the exact enabled MCP operation
is known, and do not name a method-owner move operation that the enabled tools
do not provide.

## Scope

Keep each item inside the approved task boundary. Do not add features, tests,
services, handlers, managers, or validation gates through plan wording.

When a later decision conflicts with an earlier task artifact, append the new
decision and make the smallest text change needed to remove the conflict. Do
not rewrite the task journal.

## Progressive Task Artifacts

`prd.md` and `implement.md` are progressive task records. Do not replace or
rewrite either complete document unless the user explicitly requests a complete
rewrite.

Add a new decision as a new section after the existing content. Preserve the
original requirements, decisions, completed work, and execution evidence in
their existing order.

When a new decision conflicts with an earlier statement, change only the exact
conflicting sentence or field. Keep the surrounding section and its historical
context unchanged. Then append the new decision and its concrete execution
details at the end of the document.

### Wrong

```text
Replace prd.md or implement.md with a newly summarized plan.
```

This removes earlier decisions and makes the task history impossible to
recover.

### Correct

```text
Change the one obsolete owner name in the earlier section. Append a new section
that records the new owner, affected callers, and resulting call path. Leave all
other original plan content unchanged.
```
