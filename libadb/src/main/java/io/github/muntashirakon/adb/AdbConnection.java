// SPDX-License-Identifier: BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)

package io.github.muntashirakon.adb;

import android.os.Build;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.security.auth.DestroyFailedException;

/**
 * This class represents an ADB connection.
 */
// Copyright 2013 Cameron Gutman
public class AdbConnection implements Closeable {
    public static final String TAG = AdbConnection.class.getSimpleName();

    /**
     * The underlying socket that this class uses to communicate with the target device.
     */
    @NonNull
    private final Socket mSocket;

    @NonNull
    private final String mHost;

    private final int mPort;

    private final int mApi;

    /**
     * The last allocated local stream ID. The ID chosen for the next stream will be this value + 1.
     */
    private int mLastLocalId;

    /**
     * The input stream that this class uses to read from the socket.
     */
    @GuardedBy("lock")
    @NonNull
    private final InputStream mPlainInputStream;

    /**
     * The output stream that this class uses to read from the socket.
     */
    @GuardedBy("lock")
    @NonNull
    private final OutputStream mPlainOutputStream;

    /**
     * The input stream that this class uses to read from the TLS socket.
     */
    @GuardedBy("lock")
    @Nullable
    private volatile InputStream mTlsInputStream;

    /**
     * The output stream that this class uses to read from the TLS socket.
     */
    @GuardedBy("lock")
    @Nullable
    private volatile OutputStream mTlsOutputStream;

    /**
     * The backend thread that handles responding to ADB packets.
     */
    @NonNull
    private final Thread mConnectionThread;

    /**
     * Specifies whether a CNXN has been attempted.
     */
    private volatile boolean mConnectAttempted;

    /**
     * Whether the connection thread should give up if the first authentication attempt fails.
     */
    private volatile boolean mAbortOnUnauthorised;

    /**
     * Whether the first authentication attempt failed and {@link #mAbortOnUnauthorised} was {@code true}.
     */
    private volatile boolean mAuthorisationFailed;

    /**
     * Specifies whether a CNXN packet has been received from the peer.
     */
    private volatile boolean mConnectionEstablished;

    /**
     * Exceptions that occur in {@link #createConnectionThread()}.
     */
    @Nullable
    private volatile Exception mConnectionException;

    /**
     * Specifies the maximum amount data that can be sent to the remote peer.
     * This is only valid after connect() returns successfully.
     */
    private volatile int mMaxData;

    private volatile int mProtocolVersion;

    @NonNull
    private final KeyPair mKeyPair;

    @NonNull
    private volatile String mDeviceName = "Unknown Device";

    /**
     * Specifies whether this connection has already sent a signed token.
     */
    private volatile boolean mSentSignature;

    /**
     * A hash map of our opened streams indexed by local ID.
     */
    @NonNull
    private final ConcurrentHashMap<Integer, AdbStream> mOpenedStreams;

    private volatile boolean mIsTls = false;

    @GuardedBy("lock")
    @NonNull
    private final Object mLock = new Object();

    /**
     * Default time to wait for the TCP connection to the ADB daemon to be established.
     */
    public static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 30_000L;

    /**
     * Default read timeout applied to the socket. This does <em>not</em> bound how long a connection may stay idle:
     * an ADB link is legitimately silent for long stretches (a shell session producing no output), so the connection
     * thread simply resumes waiting. Its purpose is to stop the thread from parking forever inside a kernel read, so
     * that {@link #close()} and interruption take effect promptly.
     */
    public static final long DEFAULT_SOCKET_TIMEOUT_MILLIS = 30_000L;

    /**
     * Default time to wait for a response the peer owes us promptly - the OKAY acknowledging an OPEN or a WRTE, and
     * the connection thread winding down in {@link #close()}.
     */
    public static final long DEFAULT_RESPONSE_TIMEOUT_MILLIS = 30_000L;

    /**
     * @see #DEFAULT_RESPONSE_TIMEOUT_MILLIS
     */
    private final long mResponseTimeout;

    /**
     * Creates a AdbConnection object associated with the socket and crypto object specified.
     *
     * @return A new AdbConnection object.
     * @throws IOException If there is a socket error
     */
    @WorkerThread
    @NonNull
    public static AdbConnection create(@NonNull String host, int port, @NonNull PrivateKey privateKey,
                                       @NonNull Certificate certificate)
            throws IOException {
        return create(host, port, privateKey, certificate, Build.VERSION_CODES.BASE);
    }

    /**
     * Creates a AdbConnection object associated with the socket and crypto object specified.
     *
     * @return A new AdbConnection object.
     * @throws IOException If there is a socket error
     */
    @WorkerThread
    @NonNull
    public static AdbConnection create(@NonNull String host, int port, @NonNull PrivateKey privateKey,
                                       @NonNull Certificate certificate, int api)
            throws IOException {
        return create(host, port, new KeyPair(Objects.requireNonNull(privateKey), Objects.requireNonNull(certificate)),
                api);
    }

    /**
     * Creates a AdbConnection object associated with the socket and crypto object specified.
     *
     * @return A new AdbConnection object.
     * @throws IOException If there is a socket error
     */
    @WorkerThread
    @NonNull
    static AdbConnection create(@NonNull String host, int port, @NonNull KeyPair keyPair, int api) throws IOException {
        return new AdbConnection(host, port, keyPair, api, DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_SOCKET_TIMEOUT_MILLIS, DEFAULT_RESPONSE_TIMEOUT_MILLIS);
    }

    /**
     * Internal constructor to initialize some internal state
     */
    @WorkerThread
    private AdbConnection(@NonNull String host, int port, @NonNull KeyPair keyPair, int api, long connectTimeout,
                          long socketTimeout, long responseTimeout) throws IOException {
        this.mHost = Objects.requireNonNull(host);
        this.mPort = port;
        this.mApi = api;
        this.mProtocolVersion = AdbProtocol.getProtocolVersion(mApi);
        this.mMaxData = AdbProtocol.getMaxData(api);
        this.mKeyPair = Objects.requireNonNull(keyPair);
        this.mResponseTimeout = responseTimeout;
        try {
            // Connect explicitly rather than via new Socket(host, port), which offers no way to bound the connect.
            // A peer that has silently gone away - the normal outcome of a Wi-Fi drop, which delivers no FIN - would
            // otherwise block here for the OS default, minutes at a time, with no way to cancel.
            this.mSocket = new Socket();
            mSocket.connect(new InetSocketAddress(host, port), (int) connectTimeout);
            // Not a limit on how long the connection may stay idle; see DEFAULT_SOCKET_TIMEOUT_MILLIS.
            mSocket.setSoTimeout((int) socketTimeout);
            mSocket.setKeepAlive(true);
        } catch (Throwable th) {
            //noinspection UnnecessaryInitCause
            throw (IOException) new IOException().initCause(th);
        }
        this.mPlainInputStream = mSocket.getInputStream();
        this.mPlainOutputStream = mSocket.getOutputStream();

        // Disable Nagle because we're sending tiny packets
        mSocket.setTcpNoDelay(true);

        this.mOpenedStreams = new ConcurrentHashMap<>();
        this.mLastLocalId = 0;
        this.mConnectionThread = createConnectionThread();
    }

    /**
     * Time to wait for a response the peer owes us promptly.
     *
     * @see #DEFAULT_RESPONSE_TIMEOUT_MILLIS
     */
    long getResponseTimeout() {
        return mResponseTimeout;
    }

    @GuardedBy("lock")
    @NonNull
    private InputStream getInputStream() {
        return mIsTls ? Objects.requireNonNull(mTlsInputStream) : mPlainInputStream;
    }

    @GuardedBy("lock")
    @NonNull
    private OutputStream getOutputStream() {
        return mIsTls ? Objects.requireNonNull(mTlsOutputStream) : mPlainOutputStream;
    }

    /**
     * Creates a new connection thread.
     *
     * @return A new connection thread.
     */
    @NonNull
    private Thread createConnectionThread() {
        return new Thread(() -> {
            loop:
            while (!mConnectionThread.isInterrupted()) {
                try {
                    // Read and parse a message off the socket's input stream
                    AdbProtocol.Message msg = AdbProtocol.Message.parse(getInputStream(), mProtocolVersion, mMaxData);

                    switch (msg.command) {
                        // Stream-oriented commands
                        case AdbProtocol.A_OKAY:
                        case AdbProtocol.A_WRTE:
                        case AdbProtocol.A_CLSE: {
                            // Ignore all packets when not connected
                            if (!mConnectionEstablished) {
                                continue;
                            }

                            // Get the stream object corresponding to the packet
                            AdbStream waitingStream = mOpenedStreams.get(msg.arg1);
                            if (waitingStream == null) {
                                continue;
                            }

                            synchronized (waitingStream) {
                                if (msg.command == AdbProtocol.A_OKAY) {
                                    // We're ready for writes
                                    waitingStream.updateRemoteId(msg.arg0);
                                    waitingStream.readyForWrite();

                                    // Notify an open/write
                                    waitingStream.notify();
                                } else if (msg.command == AdbProtocol.A_WRTE) {
                                    // Got some data from our partner
                                    waitingStream.addPayload(msg.payload);

                                    // Tell it we're ready for more
                                    waitingStream.sendReady();
                                } else { // if (msg.command == AdbProtocol.A_CLSE) {
                                    mOpenedStreams.remove(msg.arg1);
                                    // Notify readers and writers
                                    waitingStream.notifyClose(true);
                                }
                            }
                            break;
                        }
                        case AdbProtocol.A_STLS: {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                                sendPacket(AdbProtocol.generateStls());

                                SSLContext sslContext = SslUtils.getSslContext(mKeyPair);
                                SSLSocket tlsSocket = (SSLSocket) sslContext.getSocketFactory()
                                        .createSocket(mSocket, mHost, mPort, true);
                                tlsSocket.startHandshake();
                                Log.d(TAG, "Handshake succeeded.");

                                synchronized (AdbConnection.this) {
                                    mTlsInputStream = tlsSocket.getInputStream();
                                    mTlsOutputStream = tlsSocket.getOutputStream();
                                    mIsTls = true;
                                }
                            }
                            break;
                        }
                        case AdbProtocol.A_AUTH: {
                            if (mIsTls) {
                                break;
                            }
                            if (msg.arg0 != AdbProtocol.ADB_AUTH_TOKEN) {
                                break;
                            }
                            byte[] packet;
                            // This is an authentication challenge
                            if (mSentSignature) {
                                if (mAbortOnUnauthorised) {
                                    mAuthorisationFailed = true;
                                    break loop;
                                }

                                // We've already tried our signature, so send our public key
                                packet = AdbProtocol.generateAuth(AdbProtocol.ADB_AUTH_RSAPUBLICKEY, AndroidPubkey
                                        .encodeWithName((RSAPublicKey) mKeyPair.getPublicKey(), mDeviceName));
                            } else {
                                // Sign the token
                                packet = AdbProtocol.generateAuth(AdbProtocol.ADB_AUTH_SIGNATURE, AndroidPubkey
                                        .adbAuthSign(mKeyPair.getPrivateKey(), msg.payload));
                                mSentSignature = true;
                            }

                            // Write the AUTH reply
                            sendPacket(packet);
                            break;
                        }
                        case AdbProtocol.A_CNXN: {
                            synchronized (AdbConnection.this) {
                                mProtocolVersion = msg.arg0;
                                mMaxData = msg.arg1;
                                mConnectionEstablished = true;
                                AdbConnection.this.notifyAll();
                            }
                            break;
                        }
                        case AdbProtocol.A_OPEN:
                        case AdbProtocol.A_SYNC:
                        default:
                            Log.e(TAG, String.format("Unrecognized command = 0x%x", msg.command));
                            // Unrecognized packet, just drop it
                            break;
                    }
                } catch (SocketTimeoutException e) {
                    // Nothing arrived within the socket timeout. That is not an error - an ADB link is legitimately
                    // silent for long stretches - and Message.parse() guarantees this can only be thrown at a message
                    // boundary, so resuming cannot desynchronise the stream. The point of the timeout is only that we
                    // return here periodically, so close() and interrupt() take effect instead of being ignored while
                    // the thread sits in a kernel read that a dead peer will never satisfy.
                    continue;
                } catch (Exception e) {
                    mConnectionException = e;
                    e.printStackTrace();
                    // The cleanup is taken care of by a combination of this thread and close()
                    break;
                }
            }

            // This thread takes care of cleaning up pending streams
            synchronized (AdbConnection.this) {
                cleanupStreams();
                AdbConnection.this.notifyAll();
                mConnectionEstablished = false;
                mConnectAttempted = false;
            }
        });
    }

    /**
     * Set a name for the device. Default is “Unknown Device”.
     *
     * @param deviceName Name of the device, could be the app label, hostname or user@hostname.
     */
    public void setDeviceName(@NonNull String deviceName) {
        this.mDeviceName = Objects.requireNonNull(deviceName);
    }

    /**
     * Get the version of the ADB protocol supported by the ADB daemon. The result may depend on the API version
     * specified and whether the connection has been established. In API 29 (Android 9) or later, the daemon returns
     * {@link AdbProtocol#A_VERSION_SKIP_CHECKSUM} regardless of the protocol used to create the connection. So, if
     * {@link #mApi} is set to API 28 or earlier but the OS version is Android 9 or later, before establishing the
     * connection, it returns {@link AdbProtocol#A_VERSION_MIN}, and after establishing the connection, it returns
     * {@link AdbProtocol#A_VERSION_SKIP_CHECKSUM}. In other cases, it always returns {@link AdbProtocol#A_VERSION_MIN}.
     *
     * @see #isConnectionEstablished()
     */
    public int getProtocolVersion() {
        return mProtocolVersion;
    }

    /**
     * Get the max data size supported by the ADB daemon. A connection have to be attempted before calling this method
     * and shall be blocked if the connection is in progress.
     *
     * @return The maximum data size indicated in the CONNECT packet.
     * @throws InterruptedException        If a connection cannot be waited on.
     * @throws IOException                 if the connection fails.
     * @throws AdbPairingRequiredException If ADB lacks pairing
     */
    public int getMaxData() throws InterruptedException, IOException, AdbPairingRequiredException {
        if (!mConnectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }

        waitForConnection(mResponseTimeout, TimeUnit.MILLISECONDS);

        return mMaxData;
    }

    /**
     * Whether a connection has been established. A connection has been established if a CONNECT request has been
     * received from the ADB daemon.
     */
    public boolean isConnectionEstablished() {
        return mConnectionEstablished;
    }

    /**
     * Whether the underlying socket is connected to an ADB daemon and is not in a closed state.
     */
    public boolean isConnected() {
        return !mSocket.isClosed() && mSocket.isConnected();
    }

    /**
     * Same as {@link #connect(long, TimeUnit, boolean)} without throwing anything if the first authentication attempt
     * fails.
     *
     * @return {@code true} if the connection was established, or {@code false} if the connection timed out
     * @throws IOException                 If the socket fails while connecting
     * @throws InterruptedException        If timeout has reached
     * @throws AdbPairingRequiredException If ADB lacks pairing
     */
    public boolean connect() throws IOException, InterruptedException, AdbPairingRequiredException {
        return connect(Long.MAX_VALUE, TimeUnit.MILLISECONDS, false);
    }

    /**
     * Connects to the remote device. This routine will block until the connection completes or the timeout elapses.
     *
     * @param timeout             the time to wait for the lock
     * @param unit                the time unit of the timeout argument
     * @param throwOnUnauthorised Whether to throw an {@link AdbAuthenticationFailedException}
     *                            if the peer rejects out first authentication attempt
     * @return {@code true} if the connection was established, or {@code false} if the connection timed out
     * @throws IOException                      If the socket fails while connecting
     * @throws InterruptedException             If timeout has reached
     * @throws AdbAuthenticationFailedException If {@code throwOnUnauthorised} is {@code true} and the peer rejects the
     *                                          first authentication attempt, which indicates that the peer has not
     *                                          saved the public key from a previous connection
     * @throws AdbPairingRequiredException      If ADB lacks pairing
     */
    public boolean connect(long timeout, @NonNull TimeUnit unit, boolean throwOnUnauthorised)
            throws IOException, InterruptedException, AdbAuthenticationFailedException, AdbPairingRequiredException {
        if (mConnectionEstablished) {
            throw new IllegalStateException("Already connected");
        }

        // Send CONNECT
        sendPacket(AdbProtocol.generateConnect(mApi));

        // Start the connection thread to respond to the peer
        mConnectAttempted = true;
        mAbortOnUnauthorised = throwOnUnauthorised;
        mAuthorisationFailed = false;
        mConnectionThread.start();

        return waitForConnection(timeout, Objects.requireNonNull(unit));
    }

    /**
     * Opens an {@link AdbStream} object corresponding to the specified destination.
     * This routine will block until the connection completes.
     *
     * @param service The service to open. One of the services under {@link LocalServices.Services}.
     * @param args    Additional arguments supported by the service (see the corresponding constant to learn more).
     * @return AdbStream object corresponding to the specified destination
     * @throws UnsupportedEncodingException If the destination cannot be encoded to UTF-8
     * @throws IOException                  If the stream fails while sending the packet
     * @throws InterruptedException         If we are unable to wait for the connection to finish
     * @throws AdbPairingRequiredException  If ADB lacks pairing
     */
    @NonNull
    public AdbStream open(@LocalServices.Services int service, @NonNull String... args)
            throws IOException, InterruptedException, AdbPairingRequiredException {
        if (service < LocalServices.SERVICE_FIRST || service > LocalServices.SERVICE_LAST) {
            throw new IllegalArgumentException("Invalid service: " + service);
        }
        return open(LocalServices.getDestination(service, args));
    }

    /**
     * Opens an AdbStream object corresponding to the specified destination.
     * This routine will block until the connection completes.
     *
     * @param destination The destination to open on the target
     * @return AdbStream object corresponding to the specified destination
     * @throws UnsupportedEncodingException If the destination cannot be encoded to UTF-8
     * @throws IOException                  If the stream fails while sending the packet
     * @throws InterruptedException         If we are unable to wait for the connection to finish
     * @throws AdbPairingRequiredException  If ADB lacks pairing
     */
    @NonNull
    public AdbStream open(@NonNull String destination)
            throws IOException, InterruptedException, AdbPairingRequiredException {
        int localId = ++mLastLocalId;

        if (!mConnectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }

        waitForConnection(mResponseTimeout, TimeUnit.MILLISECONDS);

        // Add this stream to this list of half-open streams
        AdbStream stream = new AdbStream(this, localId);
        mOpenedStreams.put(localId, stream);

        // Send OPEN
        sendPacket(AdbProtocol.generateOpen(localId, Objects.requireNonNull(destination)));

        // Wait for the connection thread to receive the OKAY. Bounded: an OKAY is owed promptly, and waiting for one
        // that never comes used to park here forever while holding the caller's lock, leaving no way to recover.
        synchronized (stream) {
            long deadline = System.currentTimeMillis() + mResponseTimeout;
            long remaining;
            while (!stream.isOpenAcknowledged() && !stream.isClosed()
                    && (remaining = deadline - System.currentTimeMillis()) > 0) {
                stream.wait(remaining);
            }
        }

        // Check if the OPEN request was rejected
        if (stream.isClosed()) {
            mOpenedStreams.remove(localId);
            throw new ConnectException("Stream open actively rejected by remote peer.");
        }

        if (!stream.isOpenAcknowledged()) {
            mOpenedStreams.remove(localId);
            throw new SocketTimeoutException("Timed out waiting for the remote peer to open the stream.");
        }

        return stream;
    }

    private boolean waitForConnection(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException, IOException, AdbPairingRequiredException {
        synchronized (this) {
            // Block if a connection is pending, but not yet complete
            long timeoutEndMillis = System.currentTimeMillis() + Objects.requireNonNull(unit).toMillis(timeout);
            while (!mConnectionEstablished && mConnectAttempted && timeoutEndMillis - System.currentTimeMillis() > 0) {
                wait(timeoutEndMillis - System.currentTimeMillis());
            }

            if (!mConnectionEstablished) {
                if (mConnectAttempted) {
                    return false;
                } else if (mAuthorisationFailed) {
                    // The peer may not have saved the public key in the past connections, or they've been removed.
                    throw new AdbAuthenticationFailedException();
                } else {
                    Exception connectionException = mConnectionException;
                    if (connectionException != null) {
                        if (connectionException instanceof javax.net.ssl.SSLProtocolException) {
                            String message = connectionException.getMessage();
                            if (message != null && message.contains("protocol error")) {
                                throw (AdbPairingRequiredException) (new AdbPairingRequiredException("ADB pairing is required.").initCause(connectionException));
                            }
                        }
                    }
                    throw new IOException("Connection failed");
                }
            }
        }

        return true;
    }

    /**
     * This function terminates all I/O on streams associated with this ADB connection
     */
    private void cleanupStreams() {
        // Close all streams on this connection
        for (AdbStream s : mOpenedStreams.values()) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        mOpenedStreams.clear();
    }

    /**
     * This routine closes the Adb connection and underlying socket
     *
     * @throws IOException if the socket fails to close
     */
    @Override
    public void close() throws IOException {
        // Closing the socket will kick the connection thread
        mSocket.close();

        // Wait for the connection thread to die. Bounded, so that a connection thread which somehow fails to notice
        // the closed socket cannot hold up close() - and therefore disconnect() - indefinitely.
        mConnectionThread.interrupt();
        try {
            mConnectionThread.join(mResponseTimeout);
        } catch (InterruptedException ignored) {
        }

        // Destroy keypair
        try {
            mKeyPair.destroy();
        } catch (DestroyFailedException ignore) {
        }
    }

    void sendPacket(byte[] packet) throws IOException {
        synchronized (mLock) {
            OutputStream os = getOutputStream();
            os.write(packet);
            os.flush();
        }
    }

    void flushPacket() throws IOException {
        synchronized (mLock) {
            getOutputStream().flush();
        }
    }

    public static class Builder {
        private String mHost = "127.0.0.1";
        private int mPort = 5555;
        private int mApi = Build.VERSION_CODES.BASE;
        private PrivateKey mPrivateKey;
        private Certificate mCertificate;
        private KeyPair mKeyPair;
        private String mDeviceName;
        private long mConnectTimeout = DEFAULT_CONNECT_TIMEOUT_MILLIS;
        private long mSocketTimeout = DEFAULT_SOCKET_TIMEOUT_MILLIS;
        private long mResponseTimeout = DEFAULT_RESPONSE_TIMEOUT_MILLIS;

        public Builder() {
        }

        public Builder(String host, int port) {
            mHost = host;
            mPort = port;
        }

        /**
         * Set host address. Default is 127.0.0.1
         */
        public Builder setHost(String host) {
            this.mHost = host;
            return this;
        }

        /**
         * Set port number. Default is 5555.
         */
        public Builder setPort(int port) {
            this.mPort = port;
            return this;
        }

        /**
         * Set a name for the device. Default is “Unknown Device”.
         *
         * @param deviceName Name of the device, could be the app label, hostname or user@hostname.
         */
        public Builder setDeviceName(String deviceName) {
            this.mDeviceName = deviceName;
            return this;
        }

        /**
         * Set Android API (i.e. SDK) version for this connection. If the ADB daemon and the client are located in the
         * same device, the value should be {@link Build.VERSION#SDK_INT} in order to improve performance as well as
         * security.
         *
         * @param api The API version, default is {@link Build.VERSION_CODES#BASE}.
         */
        public Builder setApi(int api) {
            this.mApi = api;
            return this;
        }

        /**
         * Set how long to wait for the TCP connection to the ADB daemon to be established.
         *
         * @param connectTimeoutMillis Timeout in milliseconds, default {@link #DEFAULT_CONNECT_TIMEOUT_MILLIS}.
         */
        public Builder setConnectTimeout(long connectTimeoutMillis) {
            this.mConnectTimeout = connectTimeoutMillis;
            return this;
        }

        /**
         * Set the socket read timeout. This does not limit how long the connection may stay idle.
         *
         * @param socketTimeoutMillis Timeout in milliseconds, default {@link #DEFAULT_SOCKET_TIMEOUT_MILLIS}.
         * @see #DEFAULT_SOCKET_TIMEOUT_MILLIS
         */
        public Builder setSocketTimeout(long socketTimeoutMillis) {
            this.mSocketTimeout = socketTimeoutMillis;
            return this;
        }

        /**
         * Set how long to wait for a response the peer owes us promptly - the OKAY acknowledging an OPEN or a WRTE.
         *
         * @param responseTimeoutMillis Timeout in milliseconds, default {@link #DEFAULT_RESPONSE_TIMEOUT_MILLIS}.
         */
        public Builder setResponseTimeout(long responseTimeoutMillis) {
            this.mResponseTimeout = responseTimeoutMillis;
            return this;
        }

        /**
         * Set generated/stored private key.
         */
        public Builder setPrivateKey(PrivateKey privateKey) {
            this.mPrivateKey = privateKey;
            return this;
        }

        /**
         * Set public key wrapped around a certificate
         */
        public Builder setCertificate(Certificate certificate) {
            this.mCertificate = certificate;
            return this;
        }

        Builder setKeyPair(KeyPair keyPair) {
            this.mKeyPair = keyPair;
            return this;
        }

        /**
         * Creates a new {@link AdbConnection} associated with the socket and crypto object specified.
         *
         * @throws IOException If there was an error while establishing a socket connection
         */
        public AdbConnection build() throws IOException {
            if (mKeyPair == null) {
                if (mPrivateKey == null || mCertificate == null) {
                    throw new UnsupportedOperationException("Private key and certificate must be set.");
                }
                mKeyPair = new KeyPair(mPrivateKey, mCertificate);
            }
            AdbConnection adbConnection = new AdbConnection(mHost, mPort, mKeyPair, mApi, mConnectTimeout,
                    mSocketTimeout, mResponseTimeout);
            if (mDeviceName != null) {
                adbConnection.setDeviceName(mDeviceName);
            }
            return adbConnection;
        }

        /**
         * Same as {@link #connect(long, TimeUnit, boolean)} without throwing anything if the first authentication
         * attempt fails.
         *
         * @return The underlying {@link AdbConnection}
         * @throws IOException                 If the socket fails while connecting
         * @throws InterruptedException        If timeout has reached
         * @throws AdbPairingRequiredException If ADB lacks pairing
         */
        public AdbConnection connect() throws IOException, InterruptedException, AdbPairingRequiredException {
            AdbConnection adbConnection = build();
            if (adbConnection.connect()) {
                throw new IOException("Unable to establish a new connection.");
            }
            return adbConnection;
        }

        /**
         * Connects to the remote device. This routine will block until the connection completes or the timeout elapses.
         *
         * @param timeout             the time to wait for the lock
         * @param unit                the time unit of the timeout argument
         * @param throwOnUnauthorised Whether to throw an {@link AdbAuthenticationFailedException}
         *                            if the peer rejects out first authentication attempt
         * @return {@code true} if the connection was established, or {@code false} if the connection timed out
         * @throws IOException                      If the socket fails while connecting
         * @throws InterruptedException             If timeout has reached
         * @throws AdbAuthenticationFailedException If {@code throwOnUnauthorised} is {@code true} and the peer rejects
         *                                          the first authentication attempt, which indicates that the peer has
         *                                          not saved the public key from a previous connection
         * @throws AdbPairingRequiredException      If ADB lacks pairing
         */
        public AdbConnection connect(long timeout, @NonNull TimeUnit unit, boolean throwOnUnauthorised)
                throws IOException, InterruptedException, AdbPairingRequiredException {
            AdbConnection adbConnection = build();
            if (adbConnection.connect(timeout, unit, throwOnUnauthorised)) {
                throw new IOException("Unable to establish a new connection.");
            }
            return adbConnection;
        }
    }
}
