package io.legohunter.ingress.source.photo.service;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The fingerprint of a photo's picture: the MD5 of the JPEG file without the parts that describe it.
 *
 * <p>A photo is stored exactly as uploaded, and the upload carries its keywords and caption inside the file.
 * A fingerprint of the whole file would change when only a keyword changed, and the photo would then be
 * treated as new pixels: stored again and replaced on the image host. So three kinds of segment are left out:
 * <ul>
 *   <li>APP1: Exif (camera data, dates, an embedded thumbnail) and XMP (keywords, caption, edit settings);</li>
 *   <li>APP13: Photoshop resources, which hold the IPTC keywords and caption;</li>
 *   <li>COM: free-text comments.</li>
 * </ul>
 * Everything that decides how the picture looks stays in: the colour profile (APP2), the Adobe colour marker
 * (APP14), the tables, the frame header and all of the compressed picture data.
 *
 * <p>A file whose segments cannot be read is fingerprinted whole.
 */
@Service
public class PhotoFingerprintService {
    private static final int MARKER_PREFIX = 0xFF;
    private static final int START_OF_SCAN = 0xDA;
    private static final int APP1 = 0xE1;
    private static final int APP13 = 0xED;
    private static final int COMMENT = 0xFE;

    public String fingerprint(byte[] jpeg) {
        MessageDigest digest = md5();
        if (!digestPicture(jpeg, digest)) {
            digest = md5();
            digest.update(jpeg);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Feeds the picture parts of the file to the digest. Returns false when the file does not have the layout
     * of a JPEG (the digest is then half-fed and must be thrown away).
     */
    private boolean digestPicture(byte[] jpeg, MessageDigest digest) {
        if (jpeg.length < 4 || unsigned(jpeg[0]) != MARKER_PREFIX || unsigned(jpeg[1]) != 0xD8) {
            return false;
        }
        digest.update(jpeg, 0, 2);
        int position = 2;
        while (true) {
            if (position + 4 > jpeg.length || unsigned(jpeg[position]) != MARKER_PREFIX) {
                return false;
            }
            int marker = unsigned(jpeg[position + 1]);
            if (marker == START_OF_SCAN) {
                // The compressed picture, and anything after it, to the end of the file.
                digest.update(jpeg, position, jpeg.length - position);
                return true;
            }
            int segmentLength = (unsigned(jpeg[position + 2]) << 8) | unsigned(jpeg[position + 3]);
            int next = position + 2 + segmentLength;
            if (segmentLength < 2 || next > jpeg.length) {
                return false;
            }
            if (marker != APP1 && marker != APP13 && marker != COMMENT) {
                digest.update(jpeg, position, next - position);
            }
            position = next;
        }
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }
}
