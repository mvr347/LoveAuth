package me.lovelace.loveAuth.premium;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Waits for child registration without asking an unregistered channel for its event loop. */
final class AcceptedChannelHook extends ChannelInboundHandlerAdapter {
    private final BooleanSupplier enabled;
    private final Consumer<Channel> attach;

    AcceptedChannelHook(BooleanSupplier enabled, Consumer<Channel> attach) {
        this.enabled = enabled;
        this.attach = attach;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel child = msg instanceof Channel channel ? channel : null;
        boolean registered = child != null && child.isRegistered();
        if (child != null && !registered && child.isOpen() && enabled.getAsBoolean()) {
            // Before forwarding: downstream may register the child asynchronously. An initializer
            // is safe before registration; a direct child.eventLoop() call is not.
            child.pipeline().addLast(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel channel) {
                    // Registration assigns the event loop before running initializers. Queue behind
                    // the server's initializer so its packet_handler exists before we attach.
                    scheduleAttach(channel);
                }
            });
        }
        ctx.fireChannelRead(msg);
        // Already-registered children are unusual, but must still be forwarded before attaching.
        if (registered && child.isRegistered() && child.isOpen() && enabled.getAsBoolean()) {
            scheduleAttach(child);
        }
    }

    private void scheduleAttach(Channel channel) {
        try {
            channel.eventLoop().execute(() -> {
                if (enabled.getAsBoolean() && channel.isRegistered() && channel.isOpen()) {
                    attach.accept(channel);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closingLoop) {
            // Event-loop shutdown can race the open/registered checks. No new hook is needed.
        }
    }
}
