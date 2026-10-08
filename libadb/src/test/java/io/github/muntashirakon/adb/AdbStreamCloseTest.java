// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * How a reader sees the end of a stream. A CLSE from the peer is the normal end of a
 * {@code shell:} command, so it must read as EOF once the data is consumed, whatever the timing.
 */
public class AdbStreamCloseTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Test
    public void peerCloseAfterDataIsConsumedIsEof() throws Exception {
        AdbStream stream = newStream();
        stream.addPayload("hello".getBytes());
        assertEquals(5, read(stream));
        stream.notifyClose(true);
        assertEquals(-1, read(stream));
    }

    @Test
    public void peerCloseWithDataQueuedDeliversDataThenEof() throws Exception {
        AdbStream stream = newStream();
        stream.addPayload("hi".getBytes());
        stream.notifyClose(true);
        assertEquals(2, read(stream));
        // Used to block forever: the reader waited for data after a pending close
        assertEquals(-1, read(stream));
    }

    @Test
    public void readingAfterLocalCloseIsAnError() throws Exception {
        AdbStream stream = newStream();
        stream.notifyClose(false);
        try {
            stream.read(new byte[8], 0, 8);
            fail("expected IOException");
        } catch (IOException expected) {
            // closed on our side
        }
    }

    private int read(AdbStream stream) throws Exception {
        Future<Integer> result = executor.submit(() -> stream.read(new byte[64], 0, 64));
        return result.get(2, TimeUnit.SECONDS);
    }

    /** An AdbStream without a live connection: only its read side is exercised. */
    private static AdbStream newStream() throws Exception {
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        AdbStream stream = (AdbStream) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, AdbStream.class);
        set(stream, "mReadQueue", new ConcurrentLinkedQueue<byte[]>());
        set(stream, "mReadBuffer", ByteBuffer.allocate(4096).flip());
        set(stream, "mWriteReady", new AtomicBoolean(false));
        return stream;
    }

    private static void set(AdbStream stream, String name, Object value) throws Exception {
        Field field = AdbStream.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(stream, value);
    }
}
