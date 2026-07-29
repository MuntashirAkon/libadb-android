// SPDX-License-Identifier: BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)

package io.github.muntashirakon.adb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AdbConnectionTransportTest {
    @Test
    public void builderAcceptsAndOwnsInjectedTransport() throws Exception {
        PassiveTransport transport = new PassiveTransport();

        AdbConnection connection = builder(transport).build();

        assertTrue(connection.isConnected());
        connection.close();
        assertFalse(connection.isConnected());
        assertEquals(1, transport.closeCount);
    }

    @Test
    public void builderClosesTransportWhenStreamInitializationFails() throws Exception {
        FailingInitializationTransport transport = new FailingInitializationTransport();
        try {
            builder(transport).build();
        } catch (IOException expected) {
            assertEquals(1, transport.closeCount);
            return;
        }
        throw new AssertionError("Expected transport initialization to fail");
    }

    @Test
    public void builderConnectReturnsEstablishedConnection() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Script.CONNECT);
        AdbConnection connection = null;
        try {
            connection = builder(transport).connect(1, TimeUnit.SECONDS, false);

            assertTrue(connection.isConnectionEstablished());
        } finally {
            if (connection != null) {
                connection.close();
            } else {
                transport.close();
            }
        }
    }

    @Test
    public void builderDefaultConnectKeepsItsUnboundedCompatibilityPath() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Script.CONNECT);
        AdbConnection connection = builder(transport).connect();
        try {
            assertTrue(connection.isConnectionEstablished());
        } finally {
            connection.close();
        }
    }

    @Test
    public void builderConnectClosesTransportAfterTimeout() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Script.SILENT);
        try {
            builder(transport).connect(20, TimeUnit.MILLISECONDS, false);
        } catch (IOException expected) {
            assertFalse(transport.isOpen());
            return;
        }
        throw new AssertionError("Expected connection timeout");
    }

    @Test
    public void boundedOpenReturnsAcknowledgedStream() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Script.CONNECT_AND_ACK_OPEN);
        AdbConnection connection = builder(transport).connect(1, TimeUnit.SECONDS, false);
        try {
            AdbStream stream = connection.open("tcp:8899", 1, TimeUnit.SECONDS);
            assertFalse(stream.isClosed());
            stream.close();
        } finally {
            connection.close();
        }
    }

    @Test
    public void openTimesOutWhenRemoteNeverAcknowledgesStream() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Script.CONNECT_WITHOUT_OPEN_ACK);
        AdbConnection connection = builder(transport).connect(1, TimeUnit.SECONDS, false);
        long startedNanos = System.nanoTime();
        try {
            connection.open("tcp:8899", 50, TimeUnit.MILLISECONDS);
        } catch (SocketTimeoutException expected) {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            assertTrue("open exceeded its timeout budget", elapsedMillis < 1_000);
            return;
        } finally {
            connection.close();
        }
        throw new AssertionError("Expected stream open to time out");
    }

    @Test
    public void injectedTransportRejectsTlsUpgradeWithoutWritingStlsResponse() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Script.REQUEST_TLS);
        try {
            builder(transport).connect(1, TimeUnit.SECONDS, false);
        } catch (IOException expected) {
            assertEquals(
                    "ADB TLS upgrade requires a socket transport.",
                    expected.getCause().getMessage()
            );
            assertEquals(1, transport.clientWriteCount);
            assertFalse(transport.isOpen());
            return;
        } finally {
            if (transport.isOpen()) {
                transport.close();
            }
        }
        throw new AssertionError("Expected TLS upgrade to be rejected");
    }

    @Test
    public void interruptingOpenClosesHalfOpenStream() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Script.CONNECT_WITHOUT_OPEN_ACK);
        AdbConnection connection = builder(transport).connect(1, TimeUnit.SECONDS, false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread opener = new Thread(() -> {
            try {
                connection.open("tcp:8899", 30, TimeUnit.SECONDS);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        try {
            opener.start();
            assertTrue(transport.openSeen.await(1, TimeUnit.SECONDS));
            opener.interrupt();
            opener.join(1_000);

            assertFalse(opener.isAlive());
            assertTrue(failure.get() instanceof InterruptedException);
            assertEquals(3, transport.clientWriteCount);
        } finally {
            connection.close();
        }
    }

    private static AdbConnection.Builder builder(AdbTransport transport) throws Exception {
        java.security.KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        return new AdbConnection.Builder()
                .setTransport(transport)
                .setPrivateKey(keyPair.getPrivate())
                .setCertificate(new PublicKeyCertificate(keyPair.getPublic()));
    }

    private static final class PassiveTransport implements AdbTransport {
        private final InputStream inputStream = new ByteArrayInputStream(new byte[0]);
        private final OutputStream outputStream = new ByteArrayOutputStream();
        private boolean open = true;
        private int closeCount;

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            if (open) {
                open = false;
                ++closeCount;
            }
        }
    }

    private static final class FailingInitializationTransport implements AdbTransport {
        private int closeCount;

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw new IOException("output unavailable");
        }

        @Override
        public boolean isOpen() {
            return closeCount == 0;
        }

        @Override
        public void close() {
            ++closeCount;
        }
    }

    private enum Script {
        CONNECT,
        CONNECT_AND_ACK_OPEN,
        CONNECT_WITHOUT_OPEN_ACK,
        REQUEST_TLS,
        SILENT,
    }

    private static final class ScriptedTransport implements AdbTransport {
        private final PipedInputStream clientInput = new PipedInputStream();
        private final PipedOutputStream daemonOutput;
        private final Script script;
        private final OutputStream clientOutput = new OutputStream() {
            @Override
            public void write(int value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                ++clientWriteCount;
                int command = ByteBuffer.wrap(bytes, offset, length)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt();
                if (command == AdbProtocol.A_CNXN
                        && (script == Script.CONNECT
                        || script == Script.CONNECT_AND_ACK_OPEN
                        || script == Script.CONNECT_WITHOUT_OPEN_ACK)) {
                    daemonOutput.write(AdbProtocol.generateConnect(1));
                    daemonOutput.flush();
                } else if (command == AdbProtocol.A_CNXN && script == Script.REQUEST_TLS) {
                    daemonOutput.write(AdbProtocol.generateMessage(
                            AdbProtocol.A_STLS,
                            AdbProtocol.A_STLS_VERSION,
                            0,
                            null
                    ));
                    daemonOutput.flush();
                } else if (command == AdbProtocol.A_OPEN) {
                    openSeen.countDown();
                    if (script == Script.CONNECT_AND_ACK_OPEN) {
                        int localId = ByteBuffer.wrap(bytes, offset + 4, length - 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .getInt();
                        daemonOutput.write(AdbProtocol.generateMessage(
                                AdbProtocol.A_OKAY,
                                42,
                                localId,
                                null
                        ));
                        daemonOutput.flush();
                    }
                }
            }
        };
        private boolean open = true;
        private int clientWriteCount;
        private final CountDownLatch openSeen = new CountDownLatch(1);

        private ScriptedTransport(Script script) throws IOException {
            this.script = script;
            daemonOutput = new PipedOutputStream(clientInput);
        }

        @Override
        public InputStream getInputStream() {
            return clientInput;
        }

        @Override
        public OutputStream getOutputStream() {
            return clientOutput;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            open = false;
            daemonOutput.close();
            clientInput.close();
        }
    }

    private static final class PublicKeyCertificate extends Certificate {
        private final PublicKey publicKey;

        private PublicKeyCertificate(PublicKey publicKey) {
            super("test");
            this.publicKey = publicKey;
        }

        @Override
        public byte[] getEncoded() throws CertificateEncodingException {
            return publicKey.getEncoded();
        }

        @Override
        public void verify(PublicKey key) {
        }

        @Override
        public void verify(PublicKey key, String sigProvider) {
        }

        @Override
        public String toString() {
            return "test";
        }

        @Override
        public PublicKey getPublicKey() {
            return publicKey;
        }
    }
}
