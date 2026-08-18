# Local use result prediction

## Question

Carpet uses the result of `use` to decide whether `attack` runs in the same
tick. The proxy must make this decision before the backend reports the final
world state.

## Carpet behavior

The source baseline is Carpet commit
`21993f2585d6714ced9fc86b1f6d3721cf4c60ab`.

`EntityPlayerActionPack.onUpdate()` gives `use` priority over `attack`.

1. Carpet runs a due `use` action.
2. A successful `use` skips a due `attack` action.
3. A failed `use` lets the due `attack` action run.
4. A successful `attack` retries the failed `use` action.

Carpet gets the result from server objects in the same thread. It calls block,
entity, and item behavior directly. It does not infer success from packet
submission.

## Minecraft 26.2 client behavior

The local evidence is the mapped Minecraft 26.2 jar at this path:

```text
.gradle/loom-cache/minecraftMaven/net/minecraft/
minecraft-merged-f8532f8966/26.2/
minecraft-merged-f8532f8966-26.2.jar
```

`Minecraft.startUseItem()` checks the main hand before the offhand. For each
hand, it applies this order:

1. It tries an entity interaction for an entity hit.
2. It tries a block interaction for a block hit.
3. It tries generic item use when the earlier interaction does not consume the
   action.

The internal calls return an `InteractionResult`. A `Success` consumes the
action. `Fail`, `Pass`, and `TryEmptyHandInteraction` do not consume it by
themselves.

The client calculates these results from live Minecraft behavior. Important
calls include `BlockState.useItemOn`, `BlockState.useWithoutItem`,
`ItemStack.useOn`, `ItemStack.use`, and `Player.interactOn`.

## Repository capability

The proxy tracks block state IDs, collision shapes, entity bounds, and a small
set of physics properties. It does not contain the full block, item, and entity
interaction behavior.

Packet submission only confirms that the proxy queued a request. It does not
confirm that the server consumed the action.

No normal protocol response gives the action result soon enough to decide the
second action in the same tick. A one-tick delay changes Carpet behavior and
still does not identify every rejected interaction.

## Approved result model

The local predictor returns a boolean. It adds no unknown state.

- A modeled Vanilla success returns `true`.
- A modeled Vanilla non-success returns `false`.
- An unmodeled Vanilla behavior returns `false`.

Packet output still follows the Vanilla request order. The backend can consume
a request that the incomplete local model reports as `false`.

## Conclusion

The proxy cannot derive the Carpet boolean result from packet submission. It
uses the modeled Vanilla result and treats missing behavior as failure.
