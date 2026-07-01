package me.lovelace.loveAuth.input;

import me.lovelace.loveAuth.LoveAuth;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class SignInputHandler implements Listener {
    private final LoveAuth plugin;
    private final Map<UUID, Consumer<String[]>> awaitingInput = new ConcurrentHashMap<>();
    private final Map<UUID, BlockData> blockStates = new ConcurrentHashMap<>();

    public SignInputHandler(LoveAuth plugin) {
        this.plugin = plugin;
    }

    public boolean canUseSignAt(Player player) {
        org.bukkit.Location loc = player.getLocation().clone().add(0, 5, 0);
        return loc.getBlock().getType() == Material.AIR;
    }

    public void awaitInput(Player player, String[] lines, Consumer<String[]> callback) {
        org.bukkit.Location loc = player.getLocation().clone().add(0, 5, 0);
        Block block = loc.getBlock();
        if (block.getType() != Material.AIR) {
            callback.accept(new String[]{"", "", "", ""});
            return;
        }
        blockStates.put(player.getUniqueId(), new BlockData(block, block.getType()));

        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        
        for (int i = 0; i < lines.length && i < 4; i++) {
            sign.setLine(i, lines[i]);
        }
        sign.update();

        player.openSign(sign);
        awaitingInput.put(player.getUniqueId(), callback);
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Consumer<String[]> callback = awaitingInput.remove(player.getUniqueId());
        BlockData data = blockStates.remove(player.getUniqueId());
        if (callback == null) return;

        if (data != null) data.block().setType(data.oldType());

        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) lines[i] = event.getLine(i);
        
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(lines));
    }

    public void cleanup(UUID uuid) {
        Consumer<String[]> callback = awaitingInput.remove(uuid);
        if (callback != null) callback.accept(new String[]{"", "", "", ""});
        
        BlockData data = blockStates.remove(uuid);
        if (data != null) data.block().setType(data.oldType());
    }

    private record BlockData(Block block, Material oldType) {}
}
