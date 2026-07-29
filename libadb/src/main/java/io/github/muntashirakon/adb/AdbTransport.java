// SPDX-License-Identifier: BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)

package io.github.muntashirakon.adb;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A connected, full-duplex byte transport for ADB packets.
 * <p>
 * Implementations own both streams. {@link #close()} must be idempotent and
 * must unblock pending reads and writes.
 */
public interface AdbTransport extends Closeable {
    @NonNull
    InputStream getInputStream() throws IOException;

    @NonNull
    OutputStream getOutputStream() throws IOException;

    /**
     * Returns whether the transport can still perform I/O.
     */
    boolean isOpen();
}
