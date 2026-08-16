/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.event.connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

final class DisconnectEventTest {
  @Test
  void cancellationIsMonotonic() {
    DisconnectEvent event = new DisconnectEvent(
        player(), DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);

    assertFalse(event.isCancelled());
    event.cancel();
    assertTrue(event.isCancelled());
  }

  private Player player() {
    return (Player) Proxy.newProxyInstance(
        Player.class.getClassLoader(), new Class<?>[] {Player.class},
        (proxy, method, arguments) -> {
          if (method.getName().equals("toString")) {
            return "test-player";
          }
          if (method.getReturnType().isPrimitive()) {
            return method.getReturnType() == boolean.class ? false : 0;
          }
          return null;
        });
  }
}
