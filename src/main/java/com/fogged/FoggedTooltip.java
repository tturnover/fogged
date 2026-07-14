package com.fogged;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;

/**
 * Small reusable helper for Create-style, Shift-expandable item tooltips, so every Fogged addition
 * (our own items and sections appended onto other mods' items) reads the same way: a subtle
 * "Hold [Shift]" prompt while collapsed, and a themed description while Shift is held.
 *
 * <p>The section is inserted <em>above</em> the advanced-tooltip trailer (the registry-id / component
 * lines the game appends last), so it sits with the description rather than dangling under the debug
 * info. Client-only in practice -- only ever called from {@link FoggedTooltips}, so {@link Screen} is
 * never touched on a dedicated server.
 */
public final class FoggedTooltip {

    /** Themed colour of the expanded description body (a soft fog blue). */
    private static final Style BODY = Style.EMPTY.withColor(TextColor.fromRgb(0x9CC4E4));
    /** Colour of words wrapped in {@code _underscores_}, brighter than the body (Create's convention). */
    private static final Style HIGHLIGHT = Style.EMPTY.withColor(TextColor.fromRgb(0xEAF3FC));

    /**
     * Insert a Shift-expandable Fogged section into {@code tooltip}, above the advanced-info trailer.
     *
     * @param tooltip         the item's tooltip lines, edited in place
     * @param itemId          the item's registry id, used to find where the advanced trailer begins
     * @param hasExternalHint {@code true} when the item already shows its own "Hold Shift" prompt (e.g. a
     *                        Create item) -- then we add no duplicate prompt, only the body under Shift
     * @param lineKeys        translation keys for the description lines shown while Shift is held
     */
    public static void appendShiftSection(List<Component> tooltip, ResourceLocation itemId,
            boolean hasExternalHint, String... lineKeys) {
        List<Component> section = new ArrayList<>();
        // Our own items keep the "Hold [Shift] for Summary" prompt visible whether collapsed or expanded
        // (as Create does). Create items already show their own prompt, so we add none.
        if (!hasExternalHint) {
            section.add(holdShiftHint());
        }
        // Expanded: a blank separator line, then the description one line lower (Create's layout).
        if (Screen.hasShiftDown()) {
            section.add(Component.empty());
            for (String key : lineKeys) {
                section.add(highlighted(Component.translatable(key).getString()));
            }
        }
        if (!section.isEmpty()) {
            tooltip.addAll(insertIndex(tooltip, itemId), section);
        }
    }

    /**
     * Build a description line from a translated string, colouring words wrapped in {@code _underscores_}
     * with {@link #HIGHLIGHT} and the rest with {@link #BODY} -- the same markup Create uses in its
     * {@code .tooltip.summary} lang entries. Text outside underscores is body-coloured; every second
     * segment (inside a pair of underscores) is highlighted.
     */
    private static Component highlighted(String raw) {
        MutableComponent line = Component.empty();
        String[] parts = raw.split("_", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            line.append(Component.literal(parts[i]).withStyle(i % 2 == 1 ? HIGHLIGHT : BODY));
        }
        return line;
    }

    /**
     * The exact "Hold [Shift] for Summary" prompt Create uses -- built from Create's own lang keys
     * ({@code create.tooltip.holdForDescription} / {@code create.tooltip.keyShift}) so it reads
     * identically to Create's when Create is present, with English fallbacks when it isn't. Matches
     * Create's styling too: the whole line dark grey, the key light grey.
     */
    public static Component holdShiftHint() {
        Component key = Component.translatableWithFallback("create.tooltip.keyShift", "Shift")
                .withStyle(ChatFormatting.GRAY);
        return Component.translatableWithFallback("create.tooltip.holdForDescription", "Hold [%1$s] for Summary", key)
                .withStyle(ChatFormatting.DARK_GRAY);
    }

    // Where our section slots in: just above the LAST trailing control hint (Create's "Hold [W] to
    // Ponder", which sits after the summary) rather than the first one (the "Hold [Shift] for Summary"
    // header, which stays above the summary). Falls back to the advanced-mode registry-id line, then the
    // end. This keeps our lines with the description, below the summary and above the Ponder/debug lines.
    private static int insertIndex(List<Component> tooltip, ResourceLocation itemId) {
        String id = itemId.toString();
        int lastHint = -1;
        int idLine = -1;
        for (int i = 0; i < tooltip.size(); i++) {
            String line = tooltip.get(i).getString();
            if (i > 0 && isControlHint(line)) {
                lastHint = i;
            }
            if (idLine < 0 && line.equals(id)) {
                idLine = i;
            }
        }
        if (lastHint >= 0) {
            return lastHint;
        }
        return idLine >= 0 ? idLine : tooltip.size();
    }

    // A "Hold [Key] to/for ..." control-hint line (Create's Ponder/Summary prompts and the like).
    private static boolean isControlHint(String line) {
        return line.contains("[") && line.toLowerCase().contains("hold");
    }

    private FoggedTooltip() {}
}
