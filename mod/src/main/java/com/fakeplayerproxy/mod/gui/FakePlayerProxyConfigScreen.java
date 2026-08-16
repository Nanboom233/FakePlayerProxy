package com.fakeplayerproxy.mod.gui;

import com.fakeplayerproxy.mod.FakePlayerProxyMod;
import com.fakeplayerproxy.mod.config.ConsentStore;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.gui.entries.AbstractListListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

/** Creates the editable Cloth Config view for saved server consent decisions. */
public final class FakePlayerProxyConfigScreen implements ModMenuApi {
    public FakePlayerProxyConfigScreen() {
    }

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return FakePlayerProxyConfigScreen::create;
    }

    /** Reads one fresh store snapshot before it creates a configuration screen. */
    public static Screen create(Screen parent) {
        ConsentStore store = ConsentStore.fromFabricConfig();
        Map<String, Boolean> initialDecisions;
        try {
            initialDecisions = store.read();
        } catch (RuntimeException | IOException exception) {
            FakePlayerProxyMod.LOGGER.error(
                    "Cannot read saved FakePlayerProxy consent decisions for the config screen",
                    exception);
            return new AlertScreen(
                    () -> Minecraft.getInstance().gui.setScreen(parent),
                    Component.translatable("fakeplayerproxy.config.title"),
                    Component.translatable("fakeplayerproxy.config.store_read_failed"));
        }

        var initial = new LinkedHashMap<>(initialDecisions);
        var savedRows = new ArrayList<Pair<String, Boolean>>();
        var builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("fakeplayerproxy.config.title"));
        var entries = initial.entrySet().stream()
                .map(entry -> Pair.of(entry.getKey(), entry.getValue()))
                .toList();
        var listEntry = new DecisionListEntry(entries, rows -> {
            savedRows.clear();
            savedRows.addAll(rows);
        });
        listEntry.setCellErrorSupplier(row -> row.getLeft().isBlank()
                ? Optional.of(Component.translatable("fakeplayerproxy.config.address_blank"))
                : Optional.empty());
        listEntry.setErrorSupplier(() -> hasDuplicateAddress(listEntry.getValue())
                ? Optional.of(Component.translatable("fakeplayerproxy.config.address_duplicate"))
                : Optional.empty());
        builder.getOrCreateCategory(Component.translatable("fakeplayerproxy.config.entries"))
                .addEntry(listEntry);
        builder.setSavingRunnable(() -> saveChanges(store, initial, savedRows));
        return builder.build();
    }

    private static boolean hasDuplicateAddress(List<Pair<String, Boolean>> rows) {
        var addresses = new HashSet<String>();
        return rows.stream().map(Pair::getLeft).anyMatch(address -> !addresses.add(address));
    }

    /** Applies only removed, new, and changed rows after Cloth Config saves the list value. */
    private static void saveChanges(
            ConsentStore store,
            Map<String, Boolean> initial,
            List<Pair<String, Boolean>> savedRows) {
        Map<String, Boolean> updated = new LinkedHashMap<>();
        savedRows.forEach(row -> updated.put(row.getLeft(), row.getRight()));
        try {
            for (String address : initial.keySet()) {
                if (!updated.containsKey(address)) {
                    store.delete(address);
                }
            }
            for (var entry : updated.entrySet()) {
                if (!initial.containsKey(entry.getKey())
                        || !Objects.equals(initial.get(entry.getKey()), entry.getValue())) {
                    store.write(entry.getKey(), entry.getValue());
                }
            }
        } catch (RuntimeException | IOException exception) {
            FakePlayerProxyMod.LOGGER.error(
                    "Cannot save FakePlayerProxy consent decisions from the config screen",
                    exception);
        }
    }

    /** Supplies Cloth Config's inherited add and delete controls for decision rows. */
    @SuppressWarnings("UnstableApiUsage")
    private static final class DecisionListEntry extends AbstractListListEntry<
            Pair<String, Boolean>, DecisionCell, DecisionListEntry> {
        private DecisionListEntry(
                List<Pair<String, Boolean>> value,
                java.util.function.Consumer<List<Pair<String, Boolean>>> saveConsumer) {
            super(
                    Component.translatable("fakeplayerproxy.config.entries"),
                    value,
                    true,
                    null,
                    saveConsumer,
                    List::of,
                    Component.translatable("text.cloth-config.reset_value"),
                    false,
                    true,
                    false,
                    DecisionCell::new);
        }

        @Override
        public DecisionListEntry self() {
            return this;
        }
    }

    /** Edits one exact address and its Allow or Decline boolean on one row. */
    @SuppressWarnings("UnstableApiUsage")
    private static final class DecisionCell extends AbstractListListEntry.AbstractListCell<
            Pair<String, Boolean>, DecisionCell, DecisionListEntry> {
        private static final int BUTTON_WIDTH = 80;
        private final EditBox address;
        private final Button decision;
        private boolean allow;
        private boolean selected;
        private boolean hovered;

        private DecisionCell(Pair<String, Boolean> value, DecisionListEntry listEntry) {
            super(value, listEntry);
            Pair<String, Boolean> initial = value == null ? Pair.of("", false) : value;
            this.allow = initial.getRight();
            this.address = new EditBox(
                    Minecraft.getInstance().font,
                    0,
                    0,
                    100,
                    20,
                    Component.translatable("fakeplayerproxy.config.server_address"));
            this.address.setMaxLength(Integer.MAX_VALUE);
            this.address.setValue(initial.getLeft());
            this.decision = Button.builder(decisionText(), button -> {
                this.allow = !this.allow;
                button.setMessage(decisionText());
            }).bounds(0, 0, BUTTON_WIDTH, 20).build();
        }

        @Override
        public Pair<String, Boolean> getValue() {
            return Pair.of(this.address.getValue(), this.allow);
        }

        @Override
        public Optional<Component> getError() {
            return Optional.empty();
        }

        @Override
        public int getCellHeight() {
            return 24;
        }

        @Override
        public void extractRenderState(
                GuiGraphicsExtractor graphics,
                int index,
                int y,
                int x,
                int entryWidth,
                int entryHeight,
                int mouseX,
                int mouseY,
                boolean isSelected,
                float delta) {
            int addressWidth = Math.max(40, entryWidth - BUTTON_WIDTH - 6);
            this.address.setPosition(x, y + 1);
            this.address.setWidth(addressWidth);
            this.address.setEditable(this.listListEntry.isEditable());
            this.address.extractRenderState(graphics, mouseX, mouseY, delta);
            this.decision.setPosition(x + addressWidth + 6, y + 1);
            this.decision.active = this.listListEntry.isEditable();
            this.decision.extractRenderState(graphics, mouseX, mouseY, delta);
            this.hovered = this.address.isMouseOver(mouseX, mouseY)
                    || this.decision.isMouseOver(mouseX, mouseY);
        }

        @Override
        public @NotNull List<? extends GuiEventListener> children() {
            return List.of(this.address, this.decision);
        }

        @Override
        public void updateSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public @NotNull NarrationPriority narrationPriority() {
            return this.selected
                    ? NarrationPriority.FOCUSED
                    : this.hovered ? NarrationPriority.HOVERED : NarrationPriority.NONE;
        }

        @Override
        public void updateNarration(@NotNull NarrationElementOutput output) {
            this.address.updateNarration(output);
            this.decision.updateNarration(output);
        }

        private Component decisionText() {
            return Component.translatable(this.allow
                    ? "fakeplayerproxy.consent.allow"
                    : "fakeplayerproxy.consent.decline");
        }
    }
}
