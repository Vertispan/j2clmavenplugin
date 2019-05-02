package net.cardosi.mojo;

import org.apache.commons.codec.binary.Hex;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Hash {
    private final MessageDigest digest;
    private String result;

    public Hash() {
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void append(byte[] content) {
        digest.update(content);

    }
    @Override
    public String toString() {
        if (result == null) {
            result = Hex.encodeHexString(digest.digest());
        }
        return result;
    }
}
