// SPDX-License-Identifier: BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)

package io.github.muntashirakon.adb;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * This class provides useful functions and fields for ADB protocol details.
 */
// Copyright 2013 Cameron Gutman
final class AdbProtocol {
    /**
     * The length of the ADB message header
     */
    public static final int ADB_HEADER_LENGTH = 24;

    public static final int A_SYNC = 0x434e5953;

    /**
     * CNXN is the connect message. No messages (except AUTH) are valid before this message is received.
     */
    public static final int A_CNXN = 0x4e584e43;

    /**
     * The maximum data payload supported by the ADB implementation.
     */
    public static final int CONNECT_MAXDATA = 4096;

    /**
     * The payload sent with the connect message.
     */
    public static final byte[] CONNECT_PAYLOAD = "host::\0".getBytes(Charset.forName("UTF-8"));

    /**
     * AUTH is the authentication message. It is part of the RSA public key authentication added in Android 4.2.2.
     */
    public static final int A_AUTH = 0x48545541;

    /**
     * OPEN is the open stream message. It is sent to open a new stream on the target device.
     */
    public static final int A_OPEN = 0x4e45504f;

    /**
     * OKAY is a success message. It is sent when a write is processed successfully.
     */
    public static final int A_OKAY = 0x59414b4f;

    /**
     * CLSE is the close stream message. It is sent to close an existing stream on the target device.
     */
    public static final int A_CLSE = 0x45534c43;

    /**
     * WRTE is the write stream message. It is sent with a payload that is the data to write to the stream.
     */
    public static final int A_WRTE = 0x45545257;

    /**
     * STLS is the Stream-based TLS1.3 authentication method, added in Android 9.
     */
    public static final int A_STLS = 0x534c5453;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({A_SYNC, A_CNXN, A_OPEN, A_OKAY, A_CLSE, A_WRTE, A_AUTH, A_STLS})
    private @interface Command {
    }

    /**
     * The current version of the ADB protocol
     */
    public static final int A_VERSION_MIN = 0x01000000;
    public static final int A_VERSION_SKIP_CHECKSUM = 0x01000001;
    public static final int A_VERSION = 0x01000001;

    /**
     * The current version of the Steam-based TLS
     */
    public static final int A_STLS_VERSION_MIN = 0x01000000;
    public static final int A_STLS_VERSION = 0x01000000;

    /**
     * This authentication type represents a SHA1 hash to sign.
     */
    public static final int ADB_AUTH_TOKEN = 1;

    /**
     * This authentication type represents the signed SHA1 hash.
     */
    public static final int ADB_AUTH_SIGNATURE = 2;

    /**
     * This authentication type represents an RSA public key.
     */
    public static final int ADB_AUTH_RSAPUBLICKEY = 3;

    @IntDef({ADB_AUTH_TOKEN, ADB_AUTH_SIGNATURE, ADB_AUTH_RSAPUBLICKEY})
    private @interface AuthType {
    }

    /**
     * This function performs a checksum on the ADB payload data.
     *
     * @param data The data
     * @return The checksum of the data
     */
    private static int getPayloadChecksum(@NonNull byte[] data) {
        return getPayloadChecksum(data, 0, data.length);
    }

    /**
     * This function performs a checksum on the ADB payload data.
     *
     * @param data   The data
     * @param offset The start offset in the data
     * @param length The number of bytes to take from the data
     * @return The checksum of the data
     */
    private static int getPayloadChecksum(@NonNull byte[] data, int offset, int length) {
        int checksum = 0;
        for (int i = offset; i < offset + length; ++i) {
            checksum += data[i] & 0xFF;
        }
        return checksum;
    }

    /**
     * This function validate the ADB message by checking
     * its command, magic, and payload checksum.
     *
     * @param msg ADB message to validate
     * @return True if the message was valid, false otherwise
     */
    public static boolean validateMessage(@NonNull Message msg) {
        if (msg.command != (~msg.magic)) { // magic = cmd ^ 0xFFFFFFFF
            return false;
        }
        if (msg.command != A_CNXN || msg.arg0 >= A_VERSION_SKIP_CHECKSUM) {
            // Checksum verification disabled
            return true;
        }
        if (msg.payloadLength != 0) {
            return getPayloadChecksum(msg.payload) == msg.checksum;
        }
        return true;
    }

    /**
     * This function generates an ADB message given the fields.
     *
     * @param command Command identifier constant
     * @param arg0    First argument
     * @param arg1    Second argument
     * @param data    The data
     * @return Byte array containing the message
     */
    @NonNull
    public static byte[] generateMessage(@Command int command, int arg0, int arg1, @Nullable byte[] data) {
        return generateMessage(command, arg0, arg1, data, 0, data == null ? 0 : data.length);
    }

    /**
     * This function generates an ADB message given the fields.
     *
     * @param command Command identifier constant
     * @param arg0    First argument
     * @param arg1    Second argument
     * @param data    The data
     * @param offset  The start offset in the data
     * @param length  The number of bytes to take from the data
     * @return Byte array containing the message
     */
    @NonNull
    public static byte[] generateMessage(@Command int command, int arg0, int arg1, @Nullable byte[] data, int offset, int length) {
        /* struct message {
         *     unsigned command;       // command identifier constant
         *     unsigned arg0;          // first argument
         *     unsigned arg1;          // second argument
         *     unsigned data_length;   // length of payload (0 is allowed)
         *     unsigned data_check;    // checksum of data payload
         *     unsigned magic;         // command ^ 0xffffffff
         * };
         */

        ByteBuffer message;

        if (data != null) {
            message = ByteBuffer.allocate(ADB_HEADER_LENGTH + length).order(ByteOrder.LITTLE_ENDIAN);
        } else {
            message = ByteBuffer.allocate(ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        }

        message.putInt(command);
        message.putInt(arg0);
        message.putInt(arg1);

        if (data != null) {
            message.putInt(length);
            message.putInt(getPayloadChecksum(data, offset, length));
        } else {
            message.putInt(0);
            message.putInt(0);
        }

        message.putInt(~command);

        if (data != null) {
            message.put(data, offset, length);
        }

        return message.array();
    }

    /**
     * Generates a connect message with default parameters.
     *
     * @return Byte array containing the message
     */
    @NonNull
    public static byte[] generateConnect() {
        return generateMessage(A_CNXN, A_VERSION, CONNECT_MAXDATA, CONNECT_PAYLOAD);
    }

    /**
     * Generates an auth message with the specified type and payload.
     *
     * @param type Authentication type (see AUTH_TYPE_* constants)
     * @param data The payload for the message
     * @return Byte array containing the message
     */
    @NonNull
    public static byte[] generateAuth(@AuthType int type, byte[] data) {
        return generateMessage(A_AUTH, type, 0, data);
    }

    /**
     * Generates an STLS message with default parameters.
     *
     * @return Byte array containing the message
     */
    @NonNull
    public static byte[] generateStls() {
        return generateMessage(A_STLS, A_STLS_VERSION, 0, null);
    }

    /**
     * Generates an open stream message with the specified local ID and destination.
     *
     * @param localId A unique local ID identifying the stream
     * @param dest    The destination of the stream on the target
     * @return Byte array containing the message
     */
    @NonNull
    public static byte[] generateOpen(int localId, @NonNull String dest) {
        ByteBuffer bbuf = ByteBuffer.allocate(dest.length() + 1);
        bbuf.put(dest.getBytes(Charset.forName("UTF-8")));
        bbuf.put((byte) 0);
        return generateMessage(A_OPEN, localId, 0, bbuf.array());
    }

    /**
     * Generates a write stream message with the specified IDs and payload.
     *
     * @param localId  The unique local ID of the stream
     * @param remoteId The unique remote ID of the stream
     * @param data     The data
     * @param offset   The start offset in the data
     * @param length   The number of bytes to take from the data
     * @return Byte array containing the message
     */
    @NonNull
    public static byte[] generateWrite(int localId, int remoteId, byte[] data, int offset, int length) {
        return generateMessage(A_WRTE, localId, remoteId, data, offset, length);
    }

    /**
     * Generates a close stream message with the specified IDs.
     *
     * @param localId  The unique local ID of the stream
     * @param remoteId The unique remote ID of the stream
     * @return Byte array containing the message
     */
    @NonNull
    public static byte[] generateClose(int localId, int remoteId) {
        return generateMessage(A_CLSE, localId, remoteId, null);
    }

    /**
     * Generates an okay message with the specified IDs.
     *
     * @param localId  The unique local ID of the stream
     * @param remoteId The unique remote ID of the stream
     * @return Byte array containing the message
     */
    @NonNull
    public static byte[] generateReady(int localId, int remoteId) {
        return generateMessage(A_OKAY, localId, remoteId, null);
    }

    /**
     * This class provides an abstraction for the ADB message format.
     */
    static final class Message {
        /**
         * The command field of the message
         */
        @Command
        public final int command;
        /**
         * The arg0 field of the message
         */
        public final int arg0;
        /**
         * The arg1 field of the message
         */
        public final int arg1;
        /**
         * The payload length field of the message
         */
        public final int payloadLength;
        /**
         * The checksum field of the message
         */
        public final int checksum;
        /**
         * The magic field of the message
         */
        public final int magic;
        /**
         * The payload of the message
         */
        public byte[] payload;

        /**
         * Read and parse an ADB message from the supplied input stream.
         * This message is NOT validated.
         *
         * @param in InputStream object to read data from
         * @return An AdbMessage object represented the message read
         * @throws IOException If the stream fails while reading
         */
        @NonNull
        public static Message parseAdbMessage(@NonNull InputStream in) throws IOException {
            ByteBuffer header = ByteBuffer.allocate(ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);

            /* Read the header first */
            {
                int dataRead = 0;
                do {
                    int bytesRead = in.read(header.array(), dataRead, ADB_HEADER_LENGTH - dataRead);
                    if (bytesRead < 0) {
                        throw new IOException("Stream closed");
                    } else dataRead += bytesRead;
                } while (dataRead < ADB_HEADER_LENGTH);
            }

            Message msg = new Message(header);

            /* If there's a payload supplied, read that too */
            if (msg.payloadLength != 0) {
                msg.payload = new byte[msg.payloadLength];
                int dataRead = 0;
                do {
                    int bytesRead = in.read(msg.payload, dataRead, msg.payloadLength - dataRead);
                    if (bytesRead < 0) {
                        throw new IOException("Stream closed");
                    } else dataRead += bytesRead;
                } while (dataRead < msg.payloadLength);
            }

            return msg;
        }

        private Message(@NonNull ByteBuffer header) {
            command = header.getInt();
            arg0 = header.getInt();
            arg1 = header.getInt();
            payloadLength = header.getInt();
            checksum = header.getInt();
            magic = header.getInt();
        }

        @NonNull
        @Override
        public String toString() {
            String tag;
            switch (command) {
                case A_SYNC:
                    tag = "SYNC";
                    break;
                case A_CNXN:
                    tag = "CNXN";
                    break;
                case A_OPEN:
                    tag = "OPEN";
                    break;
                case A_OKAY:
                    tag = "OKAY";
                    break;
                case A_CLSE:
                    tag = "CLSE";
                    break;
                case A_WRTE:
                    tag = "WRTE";
                    break;
                case A_AUTH:
                    tag = "AUTH";
                    break;
                case A_STLS:
                    tag = "STLS";
                    break;
                default:
                    tag = "????";
                    break;
            }
            return "Message{" +
                    "command=" + tag +
                    ", arg0=0x" + Integer.toHexString(arg0) +
                    ", arg1=0x" + Integer.toHexString(arg1) +
                    ", payloadLength=" + payloadLength +
                    ", checksum=" + checksum +
                    ", magic=0x" + Integer.toHexString(magic) +
                    ", payload=" + Arrays.toString(payload) +
                    '}';
        }
    }

}
