// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/** {@link AdbConnection.Builder#connect} against a minimal plain-text adbd that accepts at once. */
public class AdbConnectionBuilderTest {
    private static final int API = 28; // plain ADB, protocol with checksums

    @Test(timeout = 10_000)
    public void connectReturnsTheEstablishedConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread adbd = new Thread(() -> serve(server));
            adbd.setDaemon(true);
            adbd.start();

            java.security.KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            AdbConnection connection = new AdbConnection.Builder("127.0.0.1", server.getLocalPort())
                    .setApi(API)
                    .setKeyPair(new KeyPair(keys.getPrivate(), new DummyCertificate(keys.getPublic())))
                    .connect(5, TimeUnit.SECONDS, true);
            assertTrue(connection.isConnectionEstablished());
            connection.close();
        }
    }

    /** Answers the host's CNXN with its own, i.e. accepts the connection without authentication. */
    private static void serve(ServerSocket server) {
        try (Socket socket = server.accept()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            while (true) {
                AdbProtocol.Message msg = AdbProtocol.Message.parse(in, AdbProtocol.A_VERSION_MIN, AdbProtocol.MAX_PAYLOAD_V3);
                if (msg.command == AdbProtocol.A_CNXN) {
                    out.write(AdbProtocol.generateConnect(API));
                    out.flush();
                }
            }
        } catch (IOException ignored) {
            // the test is over
        }
    }

    private static final class DummyCertificate extends Certificate {
        private final PublicKey mPublicKey;

        DummyCertificate(PublicKey publicKey) {
            super("X.509");
            mPublicKey = publicKey;
        }

        @Override public byte[] getEncoded() { return new byte[0]; }
        @Override public void verify(PublicKey key) { }
        @Override public void verify(PublicKey key, String sigProvider) { }
        @Override public String toString() { return "dummy"; }
        @Override public PublicKey getPublicKey() { return mPublicKey; }
    }
}
