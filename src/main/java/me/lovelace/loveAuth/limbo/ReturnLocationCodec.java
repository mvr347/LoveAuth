package me.lovelace.loveAuth.limbo;

import java.util.Locale;

/**
 * Pure (Bukkit-free) serialization of a player's pre-limbo return point, stored in the
 * player's PDC as {@code world;x;y;z;yaw;pitch} so it survives disconnects and restarts.
 */
public final class ReturnLocationCodec {
    private ReturnLocationCodec() {}

    public record Point(String world, double x, double y, double z, float yaw, float pitch) {}

    public static String encode(Point p) {
        if (p == null || p.world() == null || p.world().isEmpty() || p.world().contains(";")) return null;
        return String.format(Locale.ROOT, "%s;%s;%s;%s;%s;%s",
                p.world(), Double.toString(p.x()), Double.toString(p.y()), Double.toString(p.z()),
                Float.toString(p.yaw()), Float.toString(p.pitch()));
    }

    /** Returns null for anything malformed - a corrupt entry must never throw on join. */
    public static Point decode(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(";", -1);
        if (parts.length != 6 || parts[0].isEmpty()) return null;
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return null;
            return new Point(parts[0], x, y, z, yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
