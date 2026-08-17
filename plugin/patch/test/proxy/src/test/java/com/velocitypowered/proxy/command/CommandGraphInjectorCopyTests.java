/*
 * Copyright (C) 2026 FakePlayerProxy Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.velocitypowered.proxy.command;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

public final class CommandGraphInjectorCopyTests {

  @Test
  void deepCopyPreservesRedirectsSharedNodesAndChildCycles() {
    RootCommandNode<Object> origin = new RootCommandNode<>();
    CommandNode<Object> shared = literal("shared").build();
    CommandNode<Object> first = literal("first").build();
    CommandNode<Object> second = literal("second").build();
    first.addChild(shared);
    second.addChild(shared);
    shared.addChild(first);
    origin.addChild(first);
    origin.addChild(second);
    origin.addChild(literal("redirect").redirect(origin).build());

    RootCommandNode<Object> destination = new RootCommandNode<>();
    CommandGraphInjector<Object> injector = new CommandGraphInjector<>(
        new CommandDispatcher<>(), new ReentrantLock());
    injector.copy(origin, destination);

    CommandNode<Object> copiedFirst = destination.getChild("first");
    CommandNode<Object> copiedShared = copiedFirst.getChild("shared");
    assertSame(copiedShared, destination.getChild("second").getChild("shared"));
    assertSame(copiedFirst, copiedShared.getChild("first"));
    assertSame(destination, destination.getChild("redirect").getRedirect());
    assertNotSame(shared, copiedShared);

    copiedShared.addChild(literal("event-only").build());
    assertNull(shared.getChild("event-only"));
  }
}
