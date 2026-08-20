# Backend connection priority list research

Date: 2026-08-19

## Result

Patch `BackendChannelInitializer`. Do not add throttle logic to login callers.

Every backend Login channel uses this initializer. The initializer can add one
outbound gate to each channel. The gate waits before it sends the first backend
Login data.

Add the gate after `MINECRAFT_ENCODER` in the pipeline declaration. Netty sends
outbound data from the pipeline tail toward the encoder. The gate therefore
stores packet objects before encoding.

This patch point covers all applicable paths without a path type:

- normal `VelocityServerConnection.connect()`
- the provisional relay connection
- the raw Transfer connection
- a later Shadow reconnect through `VelocityServerConnection`

Backend status ping uses a different initializer. The priority list does not delay
status ping.

## Verified call path

`VelocityServerConnection.connect()` uses `server.getBackendChannelInitializer()`.
It writes a Login handshake and a Login Start packet after TCP connect.

The raw Transfer connector also uses `server.getBackendChannelInitializer()`.
It writes the same packet order after TCP connect.

A Shadow reconnect will use a new `VelocityServerConnection`. It therefore
enters the same pipeline without reconnect-specific throttle code.

The current four-second delay in `AuthSessionHandler.startTransfer()` is outside
this common path. Remove that delay after the outbound gate owns the interval.

## Priority behavior

The outbound gate registers on its first write. It uses the connected channel's
resolved remote address as the priority-list key.

The first channel for an address writes immediately. Later channels wait in one
of two FIFO lists for that address.

The high-priority list contains real-player Login channels. The low-priority
list contains Shadow auto-reconnect channels.

The priority list selects the high-priority head first. It selects the
low-priority head only when the high-priority list is empty.

Priority does not preempt the active four-second slot. A real-player channel
that arrives during that slot becomes the next waiting channel.

The gate stores all pending writes for its channel. When the channel gets its
turn, the gate writes the stored messages in their original order and flushes
the channel.

The gate does not inspect the message type. It does not distinguish relay,
Transfer, reconnect, or a normal backend connection.

After one channel gets its turn, the priority list waits four seconds before it
releases the next channel for that address.

Continuous real-player traffic can delay auto-reconnect. This result follows
the decision that real-player login has the highest priority.

## Priority detection

Do not add a login-path type or a priority argument to a caller.

`VelocityServerConnection.connect()` adds its `MinecraftConnection` to the
pipeline before it sends Login packets. It also sets the connection association
to that `VelocityServerConnection`.

A Shadow continuation already marks its `VelocityServerConnection` with
`logoutCancelled=true`. The reconnect connection must preserve that existing
continuation state.

On the first outbound write, the gate inspects the `MinecraftConnection`
association. An associated `VelocityServerConnection` with
`isLogoutCancelled()==true` gets low priority. Every other channel gets high
priority.

This default gives normal connections, provisional relay connections, and raw
Transfer connections high priority. The raw Transfer path has no
`VelocityServerConnection` association, so it also uses the high-priority
default.

## Netty support

The pinned Velocity source uses Netty `4.2.15.Final`.

Netty provides `PendingWriteQueue`. It stores each message with its original
`ChannelPromise`. It also tracks pending bytes in channel writability.

Use `PendingWriteQueue.add()` while the channel waits. Use
`removeAndWriteAll()` when the channel gets its turn. Then flush the context.

After the gate writes the pending data, remove that gate from the pipeline.
Later CONFIG and PLAY writes then use the unchanged backend pipeline.

If the channel closes while it waits, use `removeAndFailAll()`. This operation
releases reference-counted messages and fails their promises.

These APIs remove the need for a custom pending-write value or login-request
type.

## Shared state

The initializer owns one priority list. Each remote address owns one high FIFO
list and one low FIFO list. Keep the released channel as the active slot for
four seconds.

The priority list stores the outbound gate for each waiting channel. It does not
store player, token, relay, Transfer, or reconnect state.

The channel EventLoop owns its `PendingWriteQueue`. The shared priority list only
selects the next channel. It schedules the selected gate on that channel's
EventLoop.

Short synchronized sections protect the address lists. No priority-list operation
blocks an EventLoop.

Keep the priority list and per-channel outbound gate as private nested classes
in `BackendChannelInitializer`. Do not add another source file, request type,
login-path enum, reconnect callback, or scheduler abstraction.

## Channel close

A waiting channel can close because its frontend closed or its login timed out.
The outbound gate removes itself from the priority list.

The priority list then selects the next channel. A closed waiting channel does not
consume a four-second slot.

After the gate releases a channel, keep the four-second interval even if that
channel closes. Paper can receive the Login handshake before the close.

This design needs no feature-specific cancellation rules. The existing channel
lifecycle supplies cancellation.

## Timeout boundary

The TCP connection exists while its Login writes wait. The backend
`ReadTimeoutHandler` uses the configured Velocity read timeout. The default is
30 seconds.

The frontend also has a read timeout. A long wait can close either channel.

The outbound gate does not add another rejection. It removes the closed channel
through the normal channel lifecycle.

## Address key

Use the channel's resolved remote socket address. This key includes the target
IP and port.

This key joins different DNS names after they resolve to the same target
socket. It also keeps different Paper processes on different ports separate.

Another proxy behind the same NAT can still affect Paper's source-IP throttle.
Velocity cannot observe that proxy's attempts.

## Files

- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/network/BackendChannelInitializer.java`
  defines the common backend pipeline.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/VelocityServerConnection.java:103`
  installs that initializer for normal backend connections.
- `plugin/patch/0001-login-relay.patch:1946` installs the same initializer for
  raw Transfer.
- `plugin/patch/0001-login-relay.patch:1028` contains the old fixed Transfer
  delay.
- `plugin/build/server/source/gradle/libs.versions.toml:6` pins Netty
  `4.2.15.Final`.
- `netty-transport-4.2.15.Final-sources.jar!/io/netty/channel/PendingWriteQueue.java`
  defines the pending-write APIs.
