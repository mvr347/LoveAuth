package me.lovelace.loveAuth.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Custom player-head icons for GUI buttons, built from Mojang skin-texture payloads. */
public final class HeadTextures {

    public static final String HEAD_BARRIER = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2VkMWFiYTczZjYzOWY0YmM0MmJkNDgxOTZjNzE1MTk3YmUyNzEyYzNiOTYyYzk3ZWJmOWU5ZWQ4ZWZhMDI1In19fQ==";
    public static final String HEAD_INFO = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjgwZDMyOTVkM2Q5YWJkNjI3NzZhYmNiOGRhNzU2ZjI5OGE1NDVmZWU5NDk4YzRmNjlhMWMyYzc4NTI0YzgyNCJ9fX0=";
    public static final String HEAD_CONFIRM_ACTION = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzA3ZjQ3OTU4NGZjY2FlNjg2MDAzYTYwODAwZGRmZWU3MmFmZmUxMGU0YmIyNmE3ZDRhMDBjY2I5OTc5N2QyIn19fQ==";
    public static final String HEAD_EXIT_ACCOUNT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWFlMGU0ODZkYjRlYzQ5ZmYxYjUyY2ZlY2VkYTRjM2YzNmZkZTIzYzgzNWVhM2NjZmNhYWM5MzVlNDliNWYxMCJ9fX0=";
    public static final String HEAD_PASSWORD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmRkMmViMGM2ZjhhOTU0M2VmNWZkNzI1MjVjYzJmYWIzNTY2M2NkNzA5MTM1ZTQzYjhlMjU3ZGMwYjc1ODk0OCJ9fX0=";
    public static final String HEAD_SESSION = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmUwNmE1Y2E4YTU3ZjdmOTE3MTdhOGU5ODUwOTBlOTlmNWM2NmE0NDU4N2MzOWE5Njc0NzhiNTc1OWExZWFmYiJ9fX0=";
    public static final String HEAD_BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODY1MmUyYjkzNmNhODAyNmJkMjg2NTFkN2M5ZjI4MTlkMmU5MjM2OTc3MzRkMThkZmRiMTM1NTBmOGZkYWQ1ZiJ9fX0=";
    public static final String HEAD_INACTIVE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzYxODczMWUwNjMzNzlhZWJmODJmMWQ2NGM0MTljOTBkN2YwYzE2NDhjNTQ4ZTliNjE1MWIxYmFiYTY2ZDcyMyJ9fX0=";
    public static final String HEAD_DISCORD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTY2ZmRhODFhYTMwY2RkMjA3OWRiN2NjOTBkYWU2ZWUzNDZjZTRhYWJmOWU2YTg3ZjFmNTFhZWIxYTQ0MGQifX19";
    public static final String HEAD_CHANGE_PASS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjAxYTU2OGViYTdlNDUzYjU1ZjE1NTQ1ZjVlMzVmZmFiODc5MWFhY2Y5MDM0YWZiYmJlNGJkZGIyMWZhNTAifX19";
    public static final String HEAD_CHECK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTMwZjQ1MzdkMjE0ZDM4NjY2ZTYzMDRlOWM4NTFjZDZmN2U0MWEwZWI3YzI1MDQ5YzlkMjJjOGM1ZjY1NDVkZiJ9fX0=";
    public static final String HEAD_X = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWE2Nzg3YmEzMjU2NGU3YzJmM2EwY2U2NDQ5OGVjYmIyM2I4OTg0NWU1YTY2YjVjZWM3NzM2ZjcyOWVkMzcifX19";

    private static final Pattern URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"(http[^\"]+)\"");

    private HeadTextures() {
    }

    public static ItemStack createSkull(String base64Texture, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwnerProfile(buildProfile(base64Texture));
        meta.displayName(name);
        if (lore != null && !lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static PlayerProfile buildProfile(String base64Texture) {
        String json = new String(Base64.getDecoder().decode(base64Texture), StandardCharsets.UTF_8);
        Matcher matcher = URL_PATTERN.matcher(json);
        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
        if (matcher.find()) {
            try {
                profile.getTextures().setSkin(URI.create(matcher.group(1)).toURL());
            } catch (Exception ignored) {
                // Malformed texture URL - fall back to the default Steve skin.
            }
        }
        return profile;
    }
}
