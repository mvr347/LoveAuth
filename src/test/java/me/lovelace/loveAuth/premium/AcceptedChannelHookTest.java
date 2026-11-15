package me.lovelace.loveAuth.premium;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class AcceptedChannelHookTest {
    @Test void unregisteredChildAttachesAfterServerInitializerOnItsOwnLoop() throws Exception {
        var group = new DefaultEventLoopGroup(1);
        var child = new LocalChannel();
        var attached = new CompletableFuture<Boolean>();
        assertFalse(child.isRegistered());
        assertThrows(IllegalStateException.class, child::eventLoop);
        var parent = new EmbeddedChannel(new AcceptedChannelHook(() -> true, ch ->
                attached.complete(ch.eventLoop().inEventLoop()
                        && ch.pipeline().get("packet_handler") != null)),
                new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                        Channel ch = (Channel) message;
                        // Mimic ServerBootstrapAcceptor: add the server initializer, then register.
                        ch.pipeline().addLast(new ChannelInitializer<Channel>() {
                            @Override protected void initChannel(Channel channel) {
                                channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
                            }
                        });
                    }
                });
        try {
            parent.writeInbound(child);
            assertFalse(child.isRegistered());
            assertFalse(attached.isDone());
            parent.checkException();
            group.register(child).syncUninterruptibly();
            assertTrue(attached.get(3, TimeUnit.SECONDS));
            parent.checkException();
        } finally {
            child.close().syncUninterruptibly();
            parent.finishAndReleaseAll();
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test void shutdownBetweenAcceptAndRegistrationPreventsAttach() {
        var enabled = new AtomicBoolean(true);
        var count = new AtomicInteger();
        var child = new EmbeddedChannel(false, false);
        var parent = new EmbeddedChannel(new AcceptedChannelHook(enabled::get, ch -> count.incrementAndGet()));
        try {
            assertFalse(child.isRegistered());
            parent.writeInbound(child);
            assertSame(child, parent.readInbound());
            enabled.set(false);
            child.register();
            child.runPendingTasks();
            assertEquals(0, count.get());
            // The temporary ChannelInitializer removes itself after registration.
            assertFalse(child.pipeline().toMap().values().stream().anyMatch(h -> h instanceof ChannelInitializer));
        } catch (Exception ex) { fail(ex); }
        finally {
            child.finishAndReleaseAll();
            parent.finishAndReleaseAll();
        }
    }

    @Test void childClosedByDownstreamBeforeQueuedAttachIsIgnored() {
        var count = new AtomicInteger();
        var child = new EmbeddedChannel();
        var parent = new EmbeddedChannel(new AcceptedChannelHook(() -> true, ch -> count.incrementAndGet()),
                new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ((Channel) msg).close();
                    }
                });
        try {
            parent.writeInbound(child);
            child.runPendingTasks();
            assertEquals(0, count.get());
        } finally {
            child.finishAndReleaseAll();
            parent.finishAndReleaseAll();
        }
    }

    @Test void disabledHookForwardsMessagesWithoutTouchingAnUnregisteredChild() {
        var child = new LocalChannel();
        var parent = new EmbeddedChannel(new AcceptedChannelHook(() -> false, ch -> fail("disabled attach")));
        try {
            parent.writeInbound(child);
            assertSame(child, parent.readInbound());
            parent.writeInbound("unrelated");
            assertEquals("unrelated", parent.readInbound());
            assertFalse(child.isRegistered());
        } finally {
            child.unsafe().closeForcibly();
            parent.finishAndReleaseAll();
        }
    }
}
