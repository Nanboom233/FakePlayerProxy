# Tool Selection

## Native Tools Are The Baseline

- Use native Codex tools as the default project tools for filesystem reads,
  `rg` searches, Git inspection, shell commands, patches, and normal builds.
- Treat native filesystem and Git results as authoritative for file contents,
  repository state, patch contents, and command exit status.
- Do not route ordinary file operations through IntelliJ IDEA MCP merely because
  the MCP server is available.

## IntelliJ IDEA MCP Is Supplementary

Use IntelliJ IDEA MCP when the question depends on IDEA's own project model or
IDE behavior, including:

- module and dependency recognition;
- PSI symbols, call analysis, refactoring, and IDE inspections;
- run configurations and debugger state;
- editor state and IntelliJ-specific viewers such as Patch Viewer.

Always pass the known project path to IDEA MCP. IDEA MCP evidence describes how
the IDE interprets the project; it does not replace native filesystem, Git, or
build evidence.

### Structural Refactoring Uses IDEA MCP

Use IDEA MCP refactoring operations for structural source changes:

- call `mcp__idea_index__ide_refactor_rename` with `targetType="symbol"`, the
  source `file`, the symbol's one-based `line` and `column`, and `newName` for
  symbol renames so all resolved references are updated.
- call `mcp__idea_index__ide_move_file(file, destination, project_path)` for a
  Java class/file package move so IDEA updates the package declaration, imports,
  and resolved references.
- use `mcp__idea__analyze_calls` before changing a method owner. The enabled
  IDEA MCP tools do not provide a method-owner move refactoring, so move the
  implementation and update its caller with normal code edits instead of
  claiming that an unavailable refactoring operation was used.
- use IDEA symbol or usage search after a rename or move to confirm the old
  symbol, package, owner, and call path are gone.

Do not perform these operations with native text replacement, manual path edits,
or a native patch. Native tools remain the baseline for ordinary reads, Git
inspection, shell commands, documentation edits, and builds.

For IDEA-specific bugs, reproduce or inspect the behavior through IDEA MCP and
identify the installed IDEA build before changing project files. Do not infer
Patch Viewer behavior from patch path layout alone.

## Authorization Boundary

- Opening files and reading IDEA's project or inspection state is read-only
  diagnosis.
- Do not change IDE settings, project configuration, run configurations, or
  tool configuration through IDEA MCP unless the user explicitly authorizes it.
- Do not start or stop a run configuration, debugger session, server, or game
  through IDEA MCP unless the user explicitly authorizes that action.

## Trellis Agent Lifecycle

The main session must not interrupt an active `trellis-research`,
`trellis-implement`, or `trellis-check` agent. A slow response or a missing
progress message is not a reason to bypass the Trellis workflow.

Use agent status queries, messages, and waits to coordinate the active agent.
Let the agent finish or report its own blocker. If the user interrupts the turn,
keep all agent and workspace changes. Resume the same agent and workflow on the
next turn.

Wrong:

```text
The implement agent has not replied yet. Interrupt it and continue implementation
in the main session.
```

Correct:

```text
Check the implement agent status, send a progress request, and wait for its result.
Keep its current workspace edits while it continues.
```

## Project-Owned Build Automation

- Do not add or retain project-owned `.js` or `.mjs` scripts.
- Implement resource generation, verification, and other build automation as
  explicit Gradle tasks using Gradle Kotlin DSL or JVM task classes.
- JavaScript and Node.js must not be prerequisites for building or maintaining
  this project.
- A Gradle task that downloads Minecraft, starts a Minecraft data generator, or
  runs any server must be manually invoked and must never be a dependency of
  ordinary `build`, `check`, `test`, or release tasks.
- Generated resources remain committed inputs to ordinary builds. Their explicit
  regeneration task must validate pinned sources and produce deterministic output.
- Production artifact and server run tasks must not compile or execute test
  source. They must not depend on `Test`, `check`, `patchCheck`, or another
  verification task.
- The standard Gradle `build` lifecycle can continue to depend on `check` and
  `test`. Runtime smoke coverage belongs to a normal test or an explicit
  verification task, not to an artifact or server run task.

## Third-Party Licenses

- When the project uses a licensed open-source project, include the applicable
  upstream `LICENSE` file directly.
- Add separate license documentation only when the license situation is complex.

## Wrong vs Correct

Wrong: rewrite patch headers or reorganize patch files because IDEA is assumed
to resolve their paths against the outer project.

Correct: use native tools to establish the patch and Git facts, then use IDEA
MCP or the installed IDEA implementation to verify how Patch Viewer parses and
presents that patch before proposing a repository change.
