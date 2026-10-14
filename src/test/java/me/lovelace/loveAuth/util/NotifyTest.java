package me.lovelace.loveAuth.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotifyTest {

    @Test
    void fallsBackWhenLoveCoreClassesAreMissing() {
        AtomicInteger fallback = new AtomicInteger();
        Notify.deliver(() -> { throw new NoClassDefFoundError("dev/lovelace/lovecore/api/LoveCore"); }, fallback::incrementAndGet);
        assertEquals(1, fallback.get());
    }

    @Test
    void fallsBackWhenTheServiceIsNotRegistered() {
        AtomicInteger fallback = new AtomicInteger();
        Notify.deliver(() -> false, fallback::incrementAndGet);
        assertEquals(1, fallback.get());
    }

    @Test
    void doesNotSendTwiceWhenLoveNotifyDelivered() {
        AtomicInteger fallback = new AtomicInteger();
        Notify.deliver(() -> true, fallback::incrementAndGet);
        assertEquals(0, fallback.get());
    }
}
