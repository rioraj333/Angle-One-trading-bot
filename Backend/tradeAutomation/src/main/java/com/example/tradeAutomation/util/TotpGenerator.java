package com.example.tradeAutomation.util;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP, 30s step, 6 digits, SHA-1 — matches how authenticator apps and Angel One's QR setup work. */
public final class TotpGenerator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;

    private TotpGenerator() {}

    public static String now(String base32Secret) {
        byte[] key = decodeBase32(base32Secret);
        long counter = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        byte[] counterBytes = ByteBuffer.allocate(8).putLong(counter).array();

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counterBytes);

            int offset = hash[hash.length - 1] & 0xF;
            int binCode = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binCode % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TOTP: " + e.getMessage(), e);
        }
    }

    private static byte[] decodeBase32(String input) {
        String clean = input.trim().toUpperCase().replace("=", "");
        Map<Character, Integer> lookup = new HashMap<>();
        for (int i = 0; i < ALPHABET.length(); i++) lookup.put(ALPHABET.charAt(i), i);

        StringBuilder bits = new StringBuilder();
        for (char c : clean.toCharArray()) {
            Integer val = lookup.get(c);
            if (val == null) continue;
            bits.append(String.format("%5s", Integer.toBinaryString(val)).replace(' ', '0'));
        }

        int byteCount = bits.length() / 8;
        byte[] bytes = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            String byteBits = bits.substring(i * 8, i * 8 + 8);
            bytes[i] = (byte) Integer.parseInt(byteBits, 2);
        }
        return bytes;
    }
}
