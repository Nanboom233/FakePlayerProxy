package com.fakeplayerproxy.mod.gui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * Shows one FakePlayerProxy consent request with Minecraft's confirmation UI.
 *
 * <p>The login-listener Mixin supplies the choice and Escape actions. This
 * screen keeps only presentation data and the replaced {@link ConnectScreen}.
 * Its tick method delegates to that screen, so the normal login timeout and
 * remote-disconnect handling continue while the consent request is visible.
 */
public final class FakePlayerProxyConsentScreen extends ConfirmScreen {
    private final ConnectScreen connectionScreen;
    private final Runnable onCancel;
    private final Component warning;

    /**
     * Creates the native confirmation layout for one pending Server Hello.
     *
     * @param connectionScreen the replaced screen that continues connection ticks
     * @param connectionAddress the current socket peer shown in the consent body
     * @param resultConsumer the Mixin-owned Allow or Decline action
     * @param onCancel the Mixin-owned Escape action
     */
    public FakePlayerProxyConsentScreen(
            @NotNull ConnectScreen connectionScreen,
            @NotNull String connectionAddress,
            @NotNull BooleanConsumer resultConsumer,
            @NotNull Runnable onCancel) {
        super(
                resultConsumer,
                Component.translatable("fakeplayerproxy.consent.title"),
                Component.translatable("fakeplayerproxy.consent.body", connectionAddress),
                Component.translatable("fakeplayerproxy.consent.allow"),
                Component.translatable("fakeplayerproxy.consent.decline"));
        this.connectionScreen = connectionScreen;
        this.onCancel = onCancel;
        this.warning = Component.translatable("fakeplayerproxy.consent.warning")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    }

    /** Keeps the warning style separate without replacing the native layout. */
    @Override
    protected void addAdditionalText() {
        this.layout.addChild(new MultiLineTextWidget(this.warning, this.font)
                .setMaxWidth(this.width - 50)
                .setCentered(true));
    }

    /** Makes the safer Decline choice the first keyboard action. */
    @Override
    protected void setInitialFocus() {
        if (this.noButton != null) {
            this.setInitialFocus(this.noButton);
        }
    }

    /** Keeps assistive reading in the same semantic order as the visible text. */
    @Override
    public @NotNull Component getNarrationMessage() {
        return CommonComponents.joinForNarration(super.getNarrationMessage(), this.warning);
    }

    /** Keeps the replaced ConnectScreen's connection lifecycle active. */
    @Override
    public void tick() {
        super.tick();
        this.connectionScreen.tick();
    }

    /** Uses the separate cancel action because Escape must not mean Decline. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            this.onCancel.run();
            return true;
        }
        return super.keyPressed(event);
    }
}
