# Minecraft 26.2 position syntax

## Evidence

The project Loom cache contains the mapped Minecraft 26.2 merged JAR:

```text
.gradle/loom-cache/minecraftMaven/net/minecraft/
minecraft-merged-f8532f8966/26.2/
minecraft-merged-f8532f8966-26.2.jar
```

The analysis inspected these classes with `javap -c -p`:

```text
net.minecraft.commands.arguments.coordinates.Vec3Argument
net.minecraft.commands.arguments.coordinates.WorldCoordinates
net.minecraft.commands.arguments.coordinates.WorldCoordinate
net.minecraft.commands.arguments.coordinates.LocalCoordinates
net.minecraft.world.phys.Vec3
```

## Parser selection

`Vec3Argument.vec3()` enables center correction. Its parser checks the first
input character.

An initial `^` selects `LocalCoordinates.parse`. Every other initial character
selects `WorldCoordinates.parseDouble`.

The parser requires three parts. It reports `argument.pos3d.incomplete` when a
part is missing.

The parser reports `argument.pos.mixed` when local and world coordinate types
appear in one position.

## World coordinates

`WorldCoordinates.parseDouble` enables center correction for X and Z. It
disables center correction for Y.

`WorldCoordinate.parseDouble` removes an initial `~` and marks that part as
relative. An empty suffix has value zero.

An absolute part without a decimal point receives `0.5` when center correction
is active. Relative parts never receive this correction.

The resolved value adds a relative part to the command-source position. An
absolute part replaces the applicable command-source value.

## Local coordinates

`LocalCoordinates` stores three values named `left`, `up`, and `forwards`. Each
part must start with `^`. An empty suffix has value zero.

The resolved position starts at the command-source anchor. It applies the
source pitch and yaw to the local vector.

`Vec3.applyLocalCoordinatesToRotation` creates forward and up vectors. It makes
the left vector from the negative forward and up cross product.

Minecraft calculates its sine and cosine values as floats. It then combines the
three vectors with the local left, up, and forward values.

## Suggestions

`Vec3Argument` exposes these relevant examples:

```text
0 0 0
~ ~ ~
^ ^ ^
^1 ^ ^-5
0.1 -0.5 .9
~0.5 ~1 ~-5
```

Input that starts with `^` receives local coordinate suggestions. Other input
receives absolute and relative suggestions.

## Proxy design effect

The Velocity plugin does not run with Minecraft server command classes. It
cannot use `Vec3Argument` as a runtime argument type.

A custom Brigadier argument type would also need command-graph serialization
support. This task does not patch Velocity for one argument.

The plugin uses a standard greedy string argument. One resolver copies the
verified syntax and resolves it against the target fake player.

This target rule matters for `/player as <player>`. The operator or console
position must not affect `~` or `^`.

The product decision uses the target fake player's feet as the local anchor.
