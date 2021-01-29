package net.cardosi.mojo;

import io.methvin.watcher.hashing.Murmur3F;
import org.apache.commons.codec.binary.Hex;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds a SHA1 composed of several inputs, including string parameters used during transpiling and file contents.
 */
public class Hash {
    // Moved to Murmur3F, which is embedded in DirectoryWatch. It's faster
    private Murmur3F digest;
    private String hash;

    public Hash() {
        digest = new Murmur3F();
    }

    public void append(final String text){
        this.append(text.getBytes(Charset.defaultCharset()));
    }

    public void append(final byte[] content) {
        digest.update(content);
        this.hash = null; // result is now out of sync and needs to be recomputed.
    }

    /**
     * The builder that returns the SHA as hex digits.
     */
    @Override
    public String toString() {
        if (null == hash) {
            hash = digest.getValueHexString();
        }
        return hash;
    }
}
