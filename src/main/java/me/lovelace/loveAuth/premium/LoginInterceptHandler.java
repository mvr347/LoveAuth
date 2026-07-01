package me.lovelace.loveAuth.premium;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;

/**
 * Per-connection handler that runs a real Mojang authentication handshake
 * (the same one vanilla performs under online-mode=true) before the login
 * packet ever reaches NMS, so that premium players get their real v4 UUID
 * and texture profile even though the server itself runs offline.
 *
 * Important protocol detail: once a {@code ClientboundHelloPacket}
 * (encryption request) has been sent, the client immediately switches to
 * encrypted communication after replying. From that point on there is no
 * way back to a "transparent passthrough" - the connection MUST continue
 * encrypted regardless of whether the account turns out to be premium.
 * Non-premium accounts are therefore completed with a normal offline
 * profile (same UUID Bukkit would have generated anyway), just over an
 * encrypted connection. Falling back to the original, unintercepted packet
 * is only possible before the encryption request is sent.
 */
final class LoginInterceptHandler extends ChannelInboundHandlerAdapter {

    private enum State { WAITING_HELLO, AWAITING_KEY, VERIFYING, DONE }

    private final PremiumVerificationManager manager;
    private final String connectionHandlerName;
    private final byte[] verifyToken = new byte[4];

    private State state = State.WAITING_HELLO;
    private String username;
    private Object connectionInstance;
    private ScheduledFuture<?> timeoutFuture;

    LoginInterceptHandler(PremiumVerificationManager manager, String connectionHandlerName) {
        this.manager = manager;
        this.connectionHandlerName = connectionHandlerName;
        new SecureRandom().nextBytes(verifyToken);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        NmsHandles handles = manager.getHandles();
        try {
            switch (state) {
                case WAITING_HELLO -> handleHello(ctx, msg, handles);
                case AWAITING_KEY -> handleKey(ctx, msg, handles);
                default -> ctx.fireChannelRead(msg);
            }
        } catch (Exception e) {
            manager.logHandshakeError(username == null ? "?" : username, e);
            finish(ctx);
            ctx.close();
        }
    }

    private void handleHello(ChannelHandlerContext ctx, Object msg, NmsHandles handles) throws Exception {
        if (handles == null || !manager.isActive()) {
            // Premium verification unavailable - degrade to a transparent passthrough.
            finish(ctx);
            ctx.fireChannelRead(msg);
            return;
        }
        if (!handles.serverboundHelloPacketClass.isInstance(msg)) {
            // Not the login-start packet yet: this is the handshake/intention packet
            // (the very first packet on the connection), or a status ping. Forward it
            // untouched and KEEP waiting for the real ServerboundHelloPacket.
            // Removing the handler here would make it self-destruct on the intention
            // packet and never intercept login, so every premium player would end up
            // with an offline (v3) UUID and never be recognised as premium.
            ctx.fireChannelRead(msg);
            return;
        }

        connectionInstance = ctx.pipeline().get(connectionHandlerName);
        if (connectionInstance == null) {
            finish(ctx);
            ctx.fireChannelRead(msg);
            return;
        }

        username = (String) handles.helloPacketName.invoke(msg);

        KeyPair keyPair = handles.serverKeyPair;
        Object encryptionRequest = handles.clientboundHelloPacketCtor.newInstance(
                "", keyPair.getPublic().getEncoded(), verifyToken, true);
        handles.connectionSend.invoke(connectionInstance, encryptionRequest);

        state = State.AWAITING_KEY;
        scheduleTimeout(ctx);
        // Login Start is consumed here on purpose - the client now expects an
        // EncryptionResponse next, not a continuation of the plaintext login flow.
    }

    private void handleKey(ChannelHandlerContext ctx, Object msg, NmsHandles handles) throws Exception {
        cancelTimeout();

        if (!handles.keyPacketClass.isInstance(msg)) {
            // Protocol violation: client didn't answer our encryption request as expected.
            finish(ctx);
            ctx.close();
            return;
        }

        KeyPair keyPair = handles.serverKeyPair;
        boolean valid = (boolean) handles.keyPacketIsChallengeValid.invoke(msg, verifyToken, keyPair.getPrivate());
        if (!valid) {
            finish(ctx);
            ctx.close();
            return;
        }

        SecretKey secretKey = (SecretKey) handles.keyPacketGetSecretKey.invoke(msg, keyPair.getPrivate());
        byte[] digest = (byte[]) handles.cryptDigestData.invoke(null, "", keyPair.getPublic(), secretKey);
        String serverIdHash = new BigInteger(digest).toString(16);

        // Client now expects encrypted traffic from us regardless of the outcome below.
        handles.connectionSetEncryptionKey.invoke(connectionInstance, secretKey);

        state = State.VERIFYING;
        String name = username;
        manager.getSessionClient().hasJoined(name, serverIdHash).whenComplete((profile, error) ->
                ctx.channel().eventLoop().execute(() -> {
                    if (!ctx.channel().isActive() || state != State.VERIFYING) return;
                    try {
                        if (error == null && profile != null) {
                            completePremium(ctx, handles, profile);
                        } else {
                            handleNonPremiumSession(ctx, handles, name);
                        }
                    } catch (Exception e) {
                        manager.logHandshakeError(name, e);
                        try {
                            completeOffline(ctx, handles, name);
                        } catch (Exception inner) {
                            manager.logHandshakeError(name, inner);
                            ctx.close();
                        }
                    }
                }));
    }

    /**
     * The connecting client did not present a valid Mojang session for {@code name}.
     * Block the connection only if that name is already registered locally under a
     * genuine premium account - that's what stops a cracked client from squatting
     * a name an existing premium player owns. A name nobody has registered yet is
     * never blocked just because it happens to exist on Mojang: the first join
     * decides whether the name becomes premium or cracked, and this check is what
     * keeps later logins consistent with that choice. Disabled entirely via config.
     */
    private void handleNonPremiumSession(ChannelHandlerContext ctx, NmsHandles handles, String name) {
        if (!manager.getPlugin().getConfigManager().isPremiumNameProtectionEnabled()) {
            try {
                completeOffline(ctx, handles, name);
            } catch (Exception e) {
                manager.logHandshakeError(name, e);
                ctx.close();
            }
            return;
        }
        manager.isRegisteredPremiumName(name).whenComplete((isRegisteredPremium, error) ->
                ctx.channel().eventLoop().execute(() -> {
                    if (!ctx.channel().isActive() || state != State.VERIFYING) return;
                    try {
                        if (error == null && isRegisteredPremium) {
                            rejectPremiumNameTaken(ctx, name);
                        } else {
                            completeOffline(ctx, handles, name);
                        }
                    } catch (Exception e) {
                        manager.logHandshakeError(name, e);
                        try {
                            completeOffline(ctx, handles, name);
                        } catch (Exception inner) {
                            manager.logHandshakeError(name, inner);
                            ctx.close();
                        }
                    }
                }));
    }

    private void rejectPremiumNameTaken(ChannelHandlerContext ctx, String name) throws Exception {
        NmsHandles handles = manager.getHandles();
        manager.logPremiumNameBlocked(name);
        Object component = handles.componentLiteral.invoke(null, manager.getPremiumNameTakenMessage());
        // Connection#disconnect sends the disconnect packet itself and closes the
        // channel once it's flushed - closing it here too would race the flush.
        handles.connectionDisconnect.invoke(connectionInstance, component);
        finish(ctx);
    }

    private void completePremium(ChannelHandlerContext ctx, NmsHandles handles, MojangSessionClient.MojangProfile profile) throws Exception {
        Object gameProfile = manager.buildVerifiedProfile(profile.id(), profile.name(), profile.properties());
        finishLogin(ctx, handles, gameProfile);
    }

    private void completeOffline(ChannelHandlerContext ctx, NmsHandles handles, String name) throws Exception {
        UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        Object gameProfile = manager.buildVerifiedProfile(offlineId, name, List.of());
        finishLogin(ctx, handles, gameProfile);
    }

    private void finishLogin(ChannelHandlerContext ctx, NmsHandles handles, Object gameProfile) throws Exception {
        Object packetListener = handles.connectionGetPacketListener.invoke(connectionInstance);
        // Our interception job is done; hand the rest off to the server's normal flow.
        finish(ctx);
        if (!handles.serverLoginListenerClass.isInstance(packetListener)) {
            return;
        }

        // Drive the remainder of login exactly like vanilla's offline path does, on the
        // server's authenticatorPool: first fire the pre-login events (so LuckPerms and
        // other plugins pre-load their data), then start client verification with the
        // profile those events may have rewritten. Calling startClientVerification
        // directly here would skip AsyncPlayerPreLoginEvent and make such plugins reject
        // the player ("data not loaded during pre-login").
        handles.authenticatorPool.execute(() -> {
            try {
                Object effectiveProfile = handles.loginListenerCallPlayerPreLoginEvents.invoke(packetListener, gameProfile);
                UUID id = (UUID) handles.gameProfileClass.getMethod("id").invoke(effectiveProfile);
                String name = (String) handles.gameProfileClass.getMethod("name").invoke(effectiveProfile);
                handles.loginListenerRequestedUsername.set(packetListener, name);
                handles.loginListenerRequestedUuid.set(packetListener, id);
                handles.loginListenerStartClientVerification.invoke(packetListener, effectiveProfile);
            } catch (Exception e) {
                manager.logHandshakeError(username == null ? "?" : username, e);
                ctx.close();
            }
        });
    }

    private void scheduleTimeout(ChannelHandlerContext ctx) {
        timeoutFuture = ctx.executor().schedule(() -> {
            if (state == State.AWAITING_KEY) {
                finish(ctx);
                ctx.close();
            }
        }, manager.getTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    private void cancelTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    private void finish(ChannelHandlerContext ctx) {
        cancelTimeout();
        state = State.DONE;
        if (ctx.pipeline().context(this) != null) {
            ctx.pipeline().remove(this);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelTimeout();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof io.netty.handler.codec.DecoderException) {
            // Codec error from NMS/ProtocolLib pipeline - not a LoveAuth handshake failure.
            // Remove ourselves and let the normal Netty error handling close the connection.
            finish(ctx);
            ctx.fireExceptionCaught(cause);
            return;
        }
        manager.logHandshakeError(username == null ? "?" : username, cause);
        finish(ctx);
        ctx.close();
    }
}
