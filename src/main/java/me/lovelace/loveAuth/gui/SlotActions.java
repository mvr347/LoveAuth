package me.lovelace.loveAuth.gui;

import java.util.Map;
import java.util.Optional;

/**
 * Slot -> action map of one menu. The renderer fills it while laying buttons out, and the click
 * listener reads it, so click handling never depends on hardcoded slot numbers (the layout moves
 * with the number of visible buttons).
 */
public final class SlotActions<A> {
    private volatile Map<Integer, A> map = Map.of();

    /** Replaces the whole map at once; a redraw never leaves a half-updated mapping. */
    public void replace(Map<Integer, A> actions) {
        this.map = Map.copyOf(actions);
    }

    public Optional<A> at(int slot) {
        return Optional.ofNullable(map.get(slot));
    }
}
