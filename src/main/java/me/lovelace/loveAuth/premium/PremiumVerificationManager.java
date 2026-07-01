package me.lovelace.loveAuth.premium;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import me.lovelace.loveAuth.LoveAuth;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hooks into the server's Netty accept pipeline so that {@link LoginInterceptHandler}
 * can run a real Mojang authentication handshake per-connection, even though
 * the server itself runs with online-mode=false. Entirely opt-in and best-effort:
 * any setup failure leaves {@link #isActive()} false and changes nothing about
 * normal (offline) login behaviour.
 */
public final class PremiumVerificationManager {
    private final LoveAuth plugin;
    private static final String ACCEPT_HOOK_NAME = "loveauth_accept_intercept";

    private final MojangSessionClient sessionClient = new MojangSessionClient();
    private final List<Channel> hookedParents = new java.util.concurrent.CopyOnWriteArrayList<>();
    private NmsHandles handles;
    private volatile boolean active;

    public PremiumVerificationManager(LoveAuth plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Whether {@code username} is already registered locally under a genuine
     * licensed (premium, v4-UUID) account. Unregistered names are never
     * blocked - the very first join under a name decides whether that name
     * becomes a premium or a cracked account, and this check is what lets a
     * later cracked client get rejected from squatting an already-claimed
     * premium name. Mojang license status alone (whether the name happens to
     * exist on Mojang) is irrelevant here.
     */
    public java.util.concurrent.CompletableFuture<Boolean> isRegisteredPremiumName(String username) {
        return plugin.getDatabaseManager().findPlayerByName(username)
                .thenApply(record -> record.isPresent() && record.get().uuid().version() == 4);
    }

    public long getTimeoutMs() {
        return plugin.getConfigManager().getPremiumVerificationTimeoutMs();
    }

    LoveAuth getPlugin() {
        return plugin;
    }

    NmsHandles getHandles() {
        return handles;
    }

    MojangSessionClient getSessionClient() {
        return sessionClient;
    }

    public void initialize() {
        if (!plugin.getConfigManager().isPremiumVerificationEnabled()) return;
        try {
            Object craftServer = Bukkit.getServer();
            Object minecraftServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            handles = new NmsHandles(minecraftServer);

            Object connectionListener = minecraftServer.getClass().getMethod("getConnection").invoke(minecraftServer);
            Field channelsField = findField(connectionListener.getClass(), "channels");
            channelsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ChannelFuture> channels = (List<ChannelFuture>) channelsField.get(connectionListener);

            int installed = 0;
            for (ChannelFuture future : channels) {
                installAcceptHook(future.channel());
                installed++;
            }
            if (installed == 0) {
                throw new IllegalStateException("no bound listening channels found");
            }

            active = true;
            plugin.getLogManager().infoKey("log.premium-verify-enabled", Map.of("count", String.valueOf(installed)));
        } catch (Throwable t) {
            active = false;
            handles = null;
            plugin.getLogManager().errorKey("log.premium-verify-setup-failed", Map.of("message", String.valueOf(t.getMessage())), t);
        }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * Removes the Netty accept hooks installed by {@link #initialize()} so that no
     * pipeline keeps a reference into this plugin's classloader after disable. Without
     * this, a new connection arriving after the plugin jar is closed (e.g. on /reload)
     * would fail to load the handler classes with "zip file closed".
     */
    public void shutdown() {
        active = false;
        for (Channel parent : hookedParents) {
            try {
                parent.eventLoop().execute(() -> {
                    if (parent.pipeline().get(ACCEPT_HOOK_NAME) != null) {
                        parent.pipeline().remove(ACCEPT_HOOK_NAME);
                    }
                });
            } catch (Throwable ignored) {
                // Channel already closing/closed - nothing to clean up.
            }
        }
        hookedParents.clear();
        handles = null;
    }

    private void installAcceptHook(Channel parentChannel) {
        hookedParents.add(parentChannel);
        parentChannel.pipeline().addFirst(ACCEPT_HOOK_NAME, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                ctx.fireChannelRead(msg);
                if (msg instanceof Channel child) {
                    // Defer the attach onto the child's own event loop. The server registers
                    // the child (which runs its ChannelInitializer and adds "packet_handler")
                    // as a task on that loop; queueing our attach there guarantees it runs
                    // AFTER the pipeline is built but BEFORE the first client packet is read,
                    // avoiding a race where "packet_handler" isn't present yet.
                    child.eventLoop().execute(() -> attachLoginIntercept(child));
                }
            }
        });
    }

    private void attachLoginIntercept(Channel child) {
        try {
            ChannelPipeline pipeline = child.pipeline();
            String anchor = findConnectionHandlerName(pipeline);
            if (anchor == null) return;
            pipeline.addBefore(anchor, "loveauth_login_intercept", new LoginInterceptHandler(this, anchor));
        } catch (Exception e) {
            plugin.getLogManager().errorKey("log.premium-verify-error",
                    Map.of("player", "?", "message", String.valueOf(e.getMessage())), e);
        }
    }

    private String findConnectionHandlerName(ChannelPipeline pipeline) {
        if (pipeline.get("packet_handler") != null) return "packet_handler";
        for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
            if (handles.connectionClass.isInstance(entry.getValue())) return entry.getKey();
        }
        return null;
    }

    Object buildVerifiedProfile(UUID id, String name, List<MojangSessionClient.TextureProperty> properties) throws ReflectiveOperationException {
        if (properties.isEmpty()) {
            return handles.gameProfileCtor2.newInstance(id, name);
        }
        Object multimap = handles.multimapCreate.invoke(null);
        for (MojangSessionClient.TextureProperty property : properties) {
            Object propertyObj = handles.propertyCtor3.newInstance(property.name(), property.value(), property.signature());
            handles.multimapPut.invoke(multimap, property.name(), propertyObj);
        }
        Object propertyMap = handles.propertyMapCtor.newInstance(multimap);
        return handles.gameProfileCtor3.newInstance(id, name, propertyMap);
    }

    void logHandshakeError(String username, Throwable error) {
        plugin.getLogManager().errorKey("log.premium-verify-error",
                Map.of("player", username, "message", String.valueOf(error.getMessage())), error);
    }

    void logPremiumNameBlocked(String username) {
        plugin.getLogManager().infoKey("log.premium-name-blocked", Map.of("player", username));
    }

    String getPremiumNameTakenMessage() {
        return plugin.getLangManager().plain("block.premium-name-taken");
    }
}
