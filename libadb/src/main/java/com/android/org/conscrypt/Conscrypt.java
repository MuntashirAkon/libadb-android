// SPDX-License-Identifier: Apache-2.0

package com.android.org.conscrypt;

import android.os.Build;

import androidx.annotation.RequiresApi;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

// Although support for conscrypt has been added in Android 5.0 (Lollipop),
// TLS1.3 isn't supported until Android 9 (Pie).
@RequiresApi(Build.VERSION_CODES.Q)
public final class Conscrypt {
    /**
     * Exports a value derived from the TLS master secret as described in RFC 5705.
     *
     * @param label   the label to use in calculating the exported value.  This must be
     *                an ASCII-only string.
     * @param context the application-specific context value to use in calculating the
     *                exported value.  This may be {@code null} to use no application context, which is
     *                treated differently than an empty byte array.
     * @param length  the number of bytes of keying material to return.
     * @return a value of the specified length, or {@code null} if the handshake has not yet
     * completed or the connection has been closed.
     * @throws SSLException if the value could not be exported.
     */
    public static byte[] exportKeyingMaterial(SSLSocket socket, String label, byte[] context,
                                              int length) throws SSLException {
        throw new UnsupportedOperationException("STUB");
    }
}
