// Pretends to be the phone: sends one CallBell event to the Mac and waits for the ack.
// Uses the same packet format / JCE calls as android/.../Crypto.kt.
// Usage: java TestClient.java <mac-host> <pairing-code> <type> <callId> [name] [number]
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

public class TestClient {
    static byte[] key(String code) throws Exception {
        String n = code.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return MessageDigest.getInstance("SHA-256").digest(("callbell-v1:" + n).getBytes(StandardCharsets.UTF_8));
    }

    static byte[] seal(byte[] key, String magic, byte[] plain) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(magic.getBytes(StandardCharsets.US_ASCII));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[3 + 12 + ct.length];
        System.arraycopy(magic.getBytes(StandardCharsets.US_ASCII), 0, out, 0, 3);
        System.arraycopy(nonce, 0, out, 3, 12);
        System.arraycopy(ct, 0, out, 15, ct.length);
        return out;
    }

    static byte[] open(byte[] key, String magic, byte[] p, int len) {
        try {
            if (len < 31 || !new String(p, 0, 3, StandardCharsets.US_ASCII).equals(magic)) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(p, 3, 15)));
            c.updateAAD(magic.getBytes(StandardCharsets.US_ASCII));
            return c.doFinal(p, 15, len - 15);
        } catch (Exception e) {
            return null;
        }
    }

    static String q(String s) { return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\""; }

    public static void main(String[] a) throws Exception {
        String host = a[0], code = a[1], type = a[2], callId = a[3];
        String name = a.length > 4 && !a[4].isEmpty() ? a[4] : null;
        String number = a.length > 5 ? a[5] : null;
        String id = UUID.randomUUID().toString();
        String json = "{\"v\":1,\"id\":\"" + id + "\",\"call\":\"" + callId + "\",\"type\":\"" + type + "\",\"number\":" + q(number)
            + ",\"name\":" + q(name) + ",\"device\":\"TestClient\",\"ts\":" + System.currentTimeMillis() + "}";
        byte[] k = key(code);
        byte[] pkt = seal(k, "CB1", json.getBytes(StandardCharsets.UTF_8));
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1500);
            s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(host), 47823));
            byte[] buf = new byte[2048];
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            s.receive(dp);
            byte[] plain = open(k, "CBA", buf, dp.getLength());
            if (plain == null) { System.out.println("FAIL: could not decrypt ack"); System.exit(1); }
            String ack = new String(plain, StandardCharsets.UTF_8);
            System.out.println(ack.contains(id) ? "OK ack: " + ack : "FAIL: wrong id in ack " + ack);
        } catch (SocketTimeoutException e) {
            System.out.println("FAIL: no ack");
            System.exit(1);
        }
    }
}
