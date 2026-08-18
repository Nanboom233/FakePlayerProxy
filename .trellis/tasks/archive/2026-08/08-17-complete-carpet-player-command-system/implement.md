# Complete Carpet player command implementation plan

## 1. Extend the fixed data contract

1. Patch `BlocksDataGenerator` to emit outline boxes, destroy speed, and the
   correct-tool flag for each block state.
2. Patch `EntitiesDataGenerator` to emit pickability and pick radius.
3. Patch `ItemsDataGenerator` to emit contiguous item IDs, registry keys,
   required features, base-use ownership, and the approved default components.
4. Update `GenResources.kt` to validate every new field.
5. Deduplicate collision and outline shape arrays independently.
6. Write the item table into the existing binary.
7. Keep the existing named movement item IDs in the header.
8. Regenerate `minecraft-data.bin` from the pinned Minecraft 26.2 generator.
9. Do not add another generated resource.

## 2. Decode the new fixed data

1. Add `ItemData.java` with the fields defined in `design.md`.
2. Extend `Block.java` with collision shape, outline shape, destroy speed, and
   correct-tool fields.
3. Extend `EntityTypeData.java` with pickability and pick radius.
4. Extend `Decoder.java` with the new shape and item tables.
5. Validate IDs, enum values, feature keys, shape references, finite numbers,
   and end-of-file in the existing decode pass.
6. Keep one immutable decoder singleton.

## 3. Add inventory ownership

1. Add `PlayerInventory.java` under `world/player`.
2. Store slots `0..40`, cursor, selected slot, state ID, and open container ID.
3. Implement the exact menu-zero mapping from `design.md`.
4. Apply full content, menu slot, direct player inventory, cursor, held slot,
   normal screen, mount screen, and close packets.
5. Resolve approved effective components from fixed defaults and received
   patches.
6. Preserve explicit component removals.
7. Close a nonzero menu before a menu-zero throw click.
8. Send null carried hash and an empty changed-slot map.
9. Apply only the simple known local amount changes.
10. Let later server packets replace local predictions.
11. Apply forwarded `ServerboundSetCarriedItemPacket` selection to the same
    selected-slot field.

## 4. Route authoritative packets

1. Add the eight inventory and menu handlers to
   `FakePlayerProxyPlugin.java`.
2. Add cooldown and enabled-feature handlers.
3. Extend the health handler with food and saturation.
4. Extend the abilities handler with invincibility and infinite materials.
5. Keep every handler on the existing synchronous EventLoop path.
6. Reset the new state on login, respawn, configuration entry, and close.
7. Do not add a block-ack handler because no request mutates local blocks.
8. Route `ServerboundSetCarriedItemPacket` through the same synchronous
   EventLoop path.
9. Add the living-use flags metadata ID to the existing generated entity
   schema and binary decoder.
10. Route living-use metadata through the existing entity data handler.

## 5. Add interaction geometry

1. Add closest-point and segment clipping to `AABB.java`.
2. Return the clip point and face through fastutil `Pair`.
3. Add `InteractionHit.java` with nested block and entity records.
4. Store every received block tag in `World.java`.
5. Resolve direct and tagged tool holder sets from that map.
6. Clip fixed outline boxes for the block hit.
7. Clip pickable entity boxes with pick radius.
8. Apply Vanilla block occlusion and separate interaction ranges.
9. Apply the active item attack-range component when present.
10. Return `Optional.empty()` for a miss.

## 6. Add player interaction state

1. Add `PlayerInventory` ownership to `Player.java`.
2. Add the five interaction and mining attributes.
3. Initialize and reset them from MCProtocolLib defaults.
4. Expose the existing living attribute calculation to the player subclass.
5. Override player attribute updates without moving generic movement fields.
6. Track haste, conduit power, and mining fatigue in the player subclass.
7. Track food, abilities, enabled features, and cooldown groups.
8. Decrement cooldown groups during the existing client tick.
9. Track active use hand and active destruction fields.
10. Apply the authoritative backend use flag to the existing active-use hand.
11. Clear matching main-hand use when the real client changes selected slot.
12. Add one monotonic block interaction sequence.
13. Remove the current `unavailable` helper and inline its two failure values.

## 7. Implement the shared raycast users

1. Make `Player.use` request the shared world hit.
2. Make `Player.attack` request the same hit.
3. Pass the effective active item attack range into the world query.
4. Keep packet-specific fields in the hit alternatives.
5. Do not add a predictor class or a miss type.

## 8. Implement Vanilla local use prediction

1. Change player use to `Result<Boolean, String>`.
2. Block use during active block destruction.
3. Return success during existing active item use.
4. Check main hand before offhand.
5. Check required features and cooldown group.
6. Send entity interaction before generic item use for an entity hit.
7. Reject block use outside the current world border.
8. Send block use with a new sequence before generic item use for a block hit.
9. Send generic item use for the selected hand when earlier modeled behavior
   did not succeed.
10. Model only inherited base item use.
11. Return success for allowed consumable, block-attack, and kinetic-weapon
    components.
12. Record positive-duration active use.
13. Return false for entity, block, and subclass behavior.
14. Keep packet failures as `Result.Failure`.
15. Add no unknown state and no behavior approximation.

## 9. Implement Vanilla local attack prediction

1. Change player attack to `Result<Boolean, String>`.
2. Send `ServerboundAttackPacket` and a swing for one-shot and interval actions.
3. Send one entity attack when continuous mode first acquires a target.
4. Retain that entity ID without repeating its attack packet.
5. Permit one attack after the continuous target changes.
6. Clear the retained entity ID on miss, block hit, inactive cleanup, and stop.
7. Reject block destruction outside the current world border.
8. Reject spectator and adventure block destruction locally.
9. Implement creative start destroy and five-tick delay.
10. Implement survival start, continue, abort, and stop packets.
11. Compare the active target and selected item before continuation.
12. Resolve tool speed and correct-tool rules through effective components and
    block tags.
13. Apply the researched mining formula in its exact order.
14. Consume the shared sequence for block requests.
15. Keep server block updates authoritative.
16. Abort active destruction during inactive attack and stop.

## 10. Complete inventory actions

1. Update selected slot after a successful hotbar packet.
2. Use direct player actions for scheduled and main-hand drops.
3. Use menu-zero throw clicks for offhand, numeric, and all drops.
4. Visit all-item slots from `40` through `0`.
5. Use one-item or stack throw parameters from the command form.
6. Swap predicted hands after a successful direct swap packet.
7. Close a nonzero menu during shadow preparation.
8. Remove superseded packet prototypes and unused suppressions.

## 11. Rebuild the Carpet scheduler

1. Reorder `ScheduledAction` to Carpet order.
2. Keep `Pair<Integer, Integer>` with the approved signed encoding.
3. Replace a same-type schedule after its inactive cleanup.
4. Stop immediate execution from `schedule`.
5. Run one shot on the next action tick.
6. Run the first interval after its full interval.
7. Run inactive cleanup on non-due ticks.
8. Run inactive cleanup before one-shot and interval-one execution.
9. Preserve a completed one shot until same-tick retry ends.
10. Run use before attack.
11. Skip attack after successful use.
12. Retry failed use after successful attack.
13. Keep Carpet's three-tick scheduled-use cooldown.
14. Run player movement before one-shot input cleanup.
15. Clear every schedule and active action during stop.
16. Remove the off-loop `runAction` queue branch.
17. Require ordinary command actions to run on the owner EventLoop.
18. Advance passive world state before scheduled actions for every in-game
    connection.
19. Keep local movement and `ClientTickEnd` output shadow-only.
20. Keep backend-confirmed active continuous use without repeated use packets.
21. Resume continuous use after backend metadata clears active use.

## 12. Complete direct actions

1. Convert directional input to a Vanilla local input vector.
2. Pass the input vector into the existing travel path without adding raw
   horizontal velocity in `Player`.
3. Apply friction-influenced acceleration in the air branch.
4. Apply water and lava base acceleration in their fluid branches.
5. Apply water sprint slowdown and water movement efficiency before drag.
6. Apply water jump or descend input in the water branch.
7. Track effective jump strength and jump boost for ground jump velocity.
8. Implement ground jump velocity and airborne fall-flying request.
9. Hold dismount shift across a server tick before restoring current input.
10. Preserve unrelated input for directional move, sneak, and sprint intent.
11. Derive actual sprint from forward input, food or flight state, and collision.
12. Send sprint packets only on actual state edges.
13. Make bare move clear movement, sneak, sprint intent, and actual sprint.
14. Reject jump, move, sprint, and unsprint when the service is not shadow.
15. Implement look literals, numeric look, look at, turn literals, and numeric
   turn from tracked target state.
16. Clamp applied pitch.
17. Reject `kill` before removal when the selected target is not shadow.
18. Remove and close the selected shadow service on its EventLoop.
19. Preserve existing shadow semantics.

## 13. Implement mount and position parsing

1. Parse one greedy three-part position with `StringReader`.
2. Support absolute, `~`, and `^` forms from the target player.
3. Apply integer X and Z center correction.
4. Apply target feet, yaw, pitch, and float trigonometry to local input.
5. Reject mixed, incomplete, trailing, invalid, and non-finite input.
6. Add one target-aware position suggestion helper.
7. Select bare mount candidates from the inflated Carpet search box.
8. Select coordinate mount candidates from all tracked entities.
9. Filter minecart, boat, horse, and camel kinds.
10. Exclude the player and current vehicle.
11. Break equal distances with entity ID.
12. Apply the coordinate range check to the selected candidate.
13. Send one main-hand interaction with relative closest hit point and current
    sneak state.
14. Do not register `mount anything`.

## 14. Build the shared Brigadier tree

1. Build each action node once in `PlayerCommand.create()`.
2. Attach ordinary action nodes to self and protected target parents.
3. Attach jump, move, sprint, unsprint, and kill only to the target parent.
4. Add one context requirement that resolves the parsed target as shadow.
5. Filter the existing player suggestion helper to current shadow targets.
6. Read live manager state for each suggestion request. Do not cache names.
7. Group node construction with short section comments.
8. Keep short execution lambdas inline.
9. Add one `resolveTarget` helper that returns the existing generic `Result`.
10. Add one ordinary-action `execute` helper that owns EventLoop submission.
11. Reuse target resolution in the asynchronous shadow executor.
12. Keep one player suggestion helper.
13. Keep one position suggestion helper.
14. Keep `.requires()` on the `as` node.
15. Use Brigadier bounds without service revalidation.
16. Render returned failure keys with translatable components.
17. Keep shadow on both supported paths.
18. Do not add action classes, a suggestion provider, or a coordinate type.
19. Do not refresh command trees for shadow-state changes.
20. Do not register `spawn`.
21. Do not add placeholder executors.

## 15. Update visible contracts

1. Add only the four approved failure meanings to
   `plugin/src/main/resources/com/fakeplayerproxy/i18n/messages.properties` and
   `messages_zh_CN.properties`.
2. Update `.trellis/spec/backend/velocity-plugin.md` with the final grammar and
   ownership contract.
3. Update `docs/product/operation-guide.md` with the final operator commands.
4. Do not add command configuration.
5. Do not change the Velocity patch.

## 16. Repair active use and environmental movement

1. Reconcile local active use from living metadata and selected-slot changes.
2. Send one new continuous-use request after the old backend use stops.
3. Keep active backend use from producing repeated use packets.
4. Move input acceleration from `Player` into the existing travel branches.
5. Keep movement execution shadow-only.
6. Apply the approved shadow-only command guards and target suggestions.
7. Add no parallel movement engine, command refresh, or suggestion provider.
8. Expose one EventLoop-owned query for whether `USE` is scheduled as
   continuous.
9. Listen for frontend `ServerboundPlayerActionPacket` and cancel only
   `RELEASE_USE_ITEM` while that query is true.
10. Keep clientbound living metadata unchanged. Keep all other frontend player
    actions and releases forwarded.

## 17. Run focused verification

1. Extend `DecoderTest` with one representative assertion for each new fixed
   data group.
2. Extend `WorldTest` with the four geometry and mount cases in `design.md`.
3. Extend `PlayerTest` with representative packet, inventory, use, and mining
   behavior.
4. Extend `AutomationServiceTest` with the five scheduler behaviors in
   `design.md`.
5. Extend `PlayerCommandTest` with representative self, target, coordinate,
   permission, and tree-boundary behavior.
6. For the approved repairs, extend only `PlayerTest`,
   `AutomationServiceTest`, and `AutomationManagerTest`.
7. Cover current entity attack, held continuous use, movement, jump, sprint
   edges, delayed dismount release, passive tick order, and shadow-only kill.
8. Extend the existing command test with shadow-only target suggestions and
   one non-shadow target guard.
9. Add focused existing-class cases for carried-slot use recovery, backend use
   metadata, land versus water input, and water jump.
10. Extend the existing `AutomationServiceTest` with the continuous-use
    ownership boundary. Check the short plugin cancellation guard directly.
11. Add no new test class and no per-block movement matrix.
12. Run only the affected existing test classes in one Gradle test invocation.
13. Run scoped `git diff --check` on the repair files and task artifacts.

## 18. Resolve language-spec findings

1. Replace the indexed four-value attack range array with one private named
   value type and update its call sites.
2. Inline the one-use `scheduledAttack()` body into the existing scheduler
   flow.
3. Preserve the planned capability fields. Remove duplicate warning
   suppressions and keep only the smallest necessary `//noinspection` with an
   immediately preceding English false-positive and owner comment.
4. Correct the reviewed suppressions in `AutomationManager`, `PlayerCommand`,
   `FppCommand`, `Decoder`, and `PlayerInventory` without adding broad method or
   class suppressions.
5. Replace reviewed trivial accessors in `Decoder`, `LivingEntity`, and
   `World.MovingPiston` with Lombok getters while preserving method names and
   visibility.
6. Add no new test class or test dedicated to source shape. Run only the
   existing `PlayerTest` and `AutomationServiceTest` behavior tests, compile,
   and scoped diff check.
7. Remove every programmer assertion from production Java, including
   `requireNonNull`, Java `assert`, and `Preconditions.check*`. Add the existing
   JetBrains annotations artifact as `compileOnly` for the plugin.
8. Mark project-owned internally guaranteed parameters, fields, returns, and
   record components with `@NotNull`. Do not add it to foreign declarations or
   inputs whose owner does not guarantee the contract.
9. Use explicit `if` guards at external, protocol, parsed-data, and
   mutable-state boundaries that require runtime handling. Preserve their
   existing failure behavior.
10. Verify zero production matches with `rg`. Do not change test-code uses of
    `requireNonNull` and do not add a source-shape test.
11. Allow `requireNonNullElse` and `requireNonNullElseGet` only when their
    fallback is statically guaranteed non-null.
12. Trace every explicit production exception to its owner catch and handling
    path. Add missing owner handling without removing valid throws.
13. Make `AutomationManager` contain registration, tick-task setup, and
    scheduled tick exceptions. Refuse that automation instance and log the
    concrete exception.
14. Make `PlayerCommand` contain action exceptions inside the owner EventLoop
    and return the existing translated automation failure.
15. Make the synchronous packet owner contain local state-update exceptions so
    they do not escape packet dispatch.
16. Guard the external Velocity player type and protocol-derived nullable
    values before internal `@NotNull` contracts.
17. Extend only existing manager and command tests for the new exception
    containment branches. Do not add a plugin test class or a source-shape
    test.

## 19. Simplify the plugin Gradle build

1. Use the patched `velocityHost` JAR as the sole compile source for Velocity,
   MCProtocolLib, Netty, Guice, and SLF4J runtime APIs. Remove the duplicate
   module dependencies and their one-use version variables.
2. Keep direct JetBrains annotations, the shared Lombok compiler version, and
   the existing test dependencies.
3. Attach `assembleVelocityHost` to the `velocityHost` file dependency with
   Gradle task provenance instead of adding it to every `JavaCompile` task.
4. Keep `assembleVelocityHost` as the owner of `velocity.jar`; make
   `releaseJar` own only the copied plugin JAR.
5. Remove the duplicate test input declaration, default empty JAR classifier,
   and Kotlin types/imports that local inference makes unnecessary.
6. Do not change patches, runtime code, dependency versions that remain, or
   add tests. Verify the compile and test classpaths, plugin compilation, the
   existing four focused test classes, and a scoped diff check.

## 20. Remove remaining Gradle build indirection

1. Inline the Lombok version in its two dependency declarations.
2. Use Grgit core `5.3.3` for repository open, status, local clone, pinned
   detached checkout, and patch apply operations. Do not invoke the Git CLI.
3. Replace the disposable linked worktree with a disposable local Grgit clone;
   retain the clean pinned source check, patch order, Windows writable cleanup,
   and guaranteed checkout removal.
4. Inline path-only `*Directory` and other intermediate values with one or two
   consumers. Keep only values that identify a shared artifact or remove
   meaningful repeated expressions.
5. Update existing build documentation and code-spec terminology from
   disposable worktree to disposable checkout. Do not add an abstraction for
   Grgit or create new source/test files.
6. Verify Gradle configuration, `assembleVelocityHost`, plugin compilation, the
   existing four focused test classes, absence of Git CLI calls, and scoped
   diff checks. Do not add tests.

## Review gates

1. Verify the generated binary and decoder change together before runtime
   state work starts.
2. Verify packet state correction before enabling scheduled use and attack.
3. Verify scheduler order before attaching the full command tree.
4. Stop implementation when a required protocol constructor differs from the
   pinned evidence.
5. Return to planning when a product boundary differs from `prd.md`.

## Rollback points

1. Revert the fixed-data phase as one unit when generator and decoder disagree.
2. Revert interaction prediction without reverting direct commands when its
   focused state tests fail.
3. Revert command registration without retaining unreachable action methods
   when the shared tree fails.
