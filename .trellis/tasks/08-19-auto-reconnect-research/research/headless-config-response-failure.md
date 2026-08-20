# Headless CONFIG response failure

## Evidence

`plugin/run/logs/latest.log` records two reconnect failures. Both failures occur
after the backend sends Known Packs during CONFIG.

`AutomationService.offerKnownPacks()` sends `ServerboundSelectKnownPacks` with
`bypass=false`. The patched `MinecraftConnection.sendPacket()` then selects the
MCProtocolLib registry from the retained frontend state.

The retained Shadow frontend is in PLAY. The packet exists only in the CONFIG
registry. MCProtocolLib therefore throws `IllegalArgumentException` for an
unregistered serverbound packet class.

`AutomationService.respond()` submits the write without an owner catch. The
exception leaves the EventLoop task. Netty logs it, and the backend later times
out.

## Root cause

This is a cross-layer contract failure and a test coverage gap.

The design did not state which connection state selects the registry for a
headless CONFIG response. The implementation reused the frontend-routed path,
although the frontend and backend had different protocol states.

The unit test mocked `MinecraftConnection.sendPacket()`. It verified a method
call but did not execute the packet encoder. It could not detect the wrong
registry.

## Required prevention

Headless CONFIG responses must use the backend CONFIG registry. Normal
frontend-routed responses keep their existing registry selection.

Each EventLoop response write must contain its runtime failure. The owner log
must identify the operation without credential data.

A focused test must execute the real encoder with frontend PLAY and backend
CONFIG. A live check must pass Known Packs and reach ready PLAY.

## Resolution

`AutomationService` now sends Known Packs through the direct backend encoding
path. Its EventLoop write catches and logs a runtime failure at the owner
boundary.

The focused encoder test keeps the frontend in PLAY and the backend in CONFIG.
It calls the real `MinecraftConnection.sendPacket()` path and verifies the
CONFIG packet ID after frame decoding.
