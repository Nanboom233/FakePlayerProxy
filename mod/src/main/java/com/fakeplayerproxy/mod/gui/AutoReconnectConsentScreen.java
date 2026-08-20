package com.fakeplayerproxy.mod.gui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/** Shows the translated auto-reconnect consent and returns one boolean choice. */
public final class AutoReconnectConsentScreen extends ConfirmScreen {
    public AutoReconnectConsentScreen(@NotNull BooleanConsumer choiceCallback) {
        super(
                choiceCallback,
                Component.translatable("fakeplayerproxy.auto_reconnect.consent.title").withStyle(ChatFormatting.BOLD),
                Component.empty()
                        .append(Component.translatable("fakeplayerproxy.auto_reconnect.consent.body"))
                        .append("\n\n")
                        .append(Component.translatable("fakeplayerproxy.auto_reconnect.consent.warning")
                                .withStyle(ChatFormatting.RED)),
                Component.translatable("fakeplayerproxy.consent.allow"),
                Component.translatable("fakeplayerproxy.consent.decline"));
    }
}
