// SPDX-License-Identifier: BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)

package io.github.muntashirakon.adb;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

final class SocketAdbTransport implements AdbTransport {
    @NonNull
    private final Socket mSocket;

    SocketAdbTransport(@NonNull String host, int port) throws IOException {
        Socket socket = new Socket(host, port);
        try {
            // Disable Nagle because we're sending tiny packets.
            socket.setTcpNoDelay(true);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        mSocket = socket;
    }

    @NonNull
    Socket getSocket() {
        return mSocket;
    }

    @Override
    @NonNull
    public InputStream getInputStream() throws IOException {
        return mSocket.getInputStream();
    }

    @Override
    @NonNull
    public OutputStream getOutputStream() throws IOException {
        return mSocket.getOutputStream();
    }

    @Override
    public boolean isOpen() {
        return mSocket.isConnected() && !mSocket.isClosed();
    }

    @Override
    public void close() throws IOException {
        mSocket.close();
    }
}
