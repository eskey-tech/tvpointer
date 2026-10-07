package tech.eskey.tvpointer;

import android.content.Context;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal client for the TV's own ADB daemon (network debugging, 127.0.0.1:5555), used to send
 * real taps with `input tap` on TVs where Android refuses taps from accessibility services.
 * The TV asks the user once to allow this app's key ("Always allow from this computer").
 * Not thread-safe: use from a single background thread.
 */
final class AdbClient {
    static final String HOST = "127.0.0.1";
    static final int PORT = 5555;

    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_PAYLOAD = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int KEY_BITS = 2048;
    /** PKCS#1 v1.5 type-1 padding plus the SHA-1 DigestInfo prefix: adbd verifies the token as a SHA-1 digest. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };

    private final KeyPair keys;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextLocalId = 1;

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    AdbClient(Context context) throws IOException, GeneralSecurityException {
        keys = loadOrCreateKeys(new File(context.getFilesDir(), "adb"));
    }

    boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Connects and authenticates. If the TV doesn't know this app's key yet and allowPrompt is
     * set, it shows an "Allow debugging?" prompt and this waits up to approvalTimeoutMs for the
     * user to accept it.
     * @return false if the key isn't allowed (yet), or the user didn't accept in time.
     */
    boolean connect(boolean allowPrompt, int approvalTimeoutMs) throws IOException {
        close();
        socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, PORT), 3000);
        socket.setSoTimeout(5000);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.US_ASCII));

        boolean signed = false, offeredKey = false;
        while (true) {
            Message m;
            try {
                m = read();
            } catch (IOException e) {
                close();
                return false; // timed out waiting for approval, or the TV closed the connection
            }
            if (m.command == A_CNXN) {
                socket.setSoTimeout(5000);
                return true;
            }
            if (m.command != A_AUTH || m.arg0 != AUTH_TOKEN) continue;
            if (!signed) {
                send(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                signed = true;
            } else if (allowPrompt && !offeredKey) {
                // Key not known yet: offer it, which makes the TV ask the user.
                send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload());
                offeredKey = true;
                socket.setSoTimeout(approvalTimeoutMs);
            } else {
                close();
                return false;
            }
        }
    }

    /** Runs a shell command and waits for it to finish. */
    void shell(String command) throws IOException {
        int localId = nextLocalId++;
        send(A_OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remoteId = 0;
        while (true) {
            Message m = read();
            if (m.arg1 != localId) continue; // stale traffic from an earlier stream
            if (m.command == A_OKAY) {
                remoteId = m.arg0;
            } else if (m.command == A_WRTE) {
                send(A_OKAY, localId, m.arg0, null);
            } else if (m.command == A_CLSE) {
                send(A_CLSE, localId, remoteId != 0 ? remoteId : m.arg0, null);
                return;
            }
        }
    }

    void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }

    // ---------------------------------------------------------------- wire format

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int checksum = 0;
        for (int i = 0; i < len; i++) checksum += data[i] & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(len).putInt(checksum).putInt(~command);
        if (len > 0) b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Message read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > MAX_PAYLOAD) throw new IOException("bad adb packet");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---------------------------------------------------------------- keys

    private byte[] sign(byte[] token) throws IOException {
        try {
            int size = KEY_BITS / 8;
            byte[] block = new byte[size];
            int padEnd = size - SHA1_DIGEST_INFO.length - token.length - 1;
            block[0] = 0x00;
            block[1] = 0x01;
            for (int i = 2; i < padEnd; i++) block[i] = (byte) 0xff;
            block[padEnd] = 0x00;
            System.arraycopy(SHA1_DIGEST_INFO, 0, block, padEnd + 1, SHA1_DIGEST_INFO.length);
            System.arraycopy(token, 0, block, size - token.length, token.length);
            Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
            rsa.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
            return rsa.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** The public key in adbd's format: base64(android RSAPublicKey struct) + " name\0". */
    private byte[] publicKeyPayload() {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        BigInteger n = pub.getModulus();
        int words = KEY_BITS / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + KEY_BITS / 8 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, KEY_BITS / 8));
        b.put(littleEndian(rr, KEY_BITS / 8));
        b.putInt(pub.getPublicExponent().intValue());
        String encoded = Base64.encodeToString(b.array(), Base64.NO_WRAP);
        return (encoded + " TV Pointer\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(BigInteger v, int size) {
        byte[] be = v.toByteArray(); // big-endian, may have a leading sign byte
        byte[] le = new byte[size];
        for (int i = 0; i < size && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }

    private static KeyPair loadOrCreateKeys(File dir) throws IOException, GeneralSecurityException {
        File privFile = new File(dir, "key.pk8"), pubFile = new File(dir, "key.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (privFile.isFile() && pubFile.isFile()) {
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(privFile)));
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(readAll(pubFile)));
            return new KeyPair(pub, priv);
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(KEY_BITS);
        KeyPair pair = gen.generateKeyPair();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        writeAll(privFile, pair.getPrivate().getEncoded());
        writeAll(pubFile, pair.getPublic().getEncoded());
        return pair;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            new DataInputStream(is).readFully(data);
            return data;
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
    }
}
