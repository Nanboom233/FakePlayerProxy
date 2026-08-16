package com.fakeplayerproxy.mod.gui;

import java.util.function.BiConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.multiplayer.WarningScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

/**
 * Shows one FakePlayerProxy consent request with Minecraft's confirmation UI.
 *
 * <p>The login-listener Mixin supplies the choice and Escape actions. This
 * screen keeps only presentation data and the replaced {@link ConnectScreen}.
 * Its tick method delegates to that screen, so the normal login timeout and
 * remote-disconnect handling continue while the consent request is visible.
 */
public final class FakePlayerProxyConsentScreen extends WarningScreen {
    private static final Component TITLE = Component.translatable("fakeplayerproxy.consent.title")
            .withStyle(ChatFormatting.BOLD);
    private static final Component CHECK = Component.translatable("fakeplayerproxy.consent.check");
    private static final Component WARNING = Component.translatable("fakeplayerproxy.consent.warning")
            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    private final ConnectScreen connectionScreen;
    private final Runnable onCancel;
    private final BiConsumer<Boolean, Boolean> resultConsumer;
    private Button declineButton;


    /**
     * Creates the native confirmation layout for one pending Server Hello.
     *
     * @param connectionScreen the replaced screen that continues connection ticks
     * @param connectionAddress the current socket peer shown in the consent body
     * @param resultConsumer the Mixin-owned Allow or Decline action
     * @param onCancel the Mixin-owned Escape action
     */
    public FakePlayerProxyConsentScreen(
            ConnectScreen connectionScreen,
            String connectionAddress,
            @NotNull BiConsumer<Boolean, Boolean> resultConsumer,
            @NotNull Runnable onCancel) {
        super(
                TITLE,
                Component.translatable(
                        "fakeplayerproxy.consent.body",
                        Component.literal(connectionAddress).withStyle(ChatFormatting.AQUA)
                ).append("\n\n").append(WARNING),
                CHECK,
                CommonComponents.joinForNarration(TITLE, WARNING)
        );
        this.connectionScreen = connectionScreen;
        this.onCancel = onCancel;
        this.resultConsumer = resultConsumer;
    }

    @Override
    protected @NonNull Layout addFooterButtons() {
        LinearLayout footer = LinearLayout.horizontal().spacing(8);
        footer.addChild(Button.builder(Component.translatable("fakeplayerproxy.consent.allow"), ignored -> this.resultConsumer.accept(true, this.rememberDecision())).build());
        this.declineButton = footer.addChild(Button.builder(Component.translatable("fakeplayerproxy.consent.decline"), ignored -> this.resultConsumer.accept(false, this.rememberDecision())).build());
        return footer;
    }

    private boolean rememberDecision() {
        return this.stopShowing != null && this.stopShowing.selected();
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.declineButton);
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
