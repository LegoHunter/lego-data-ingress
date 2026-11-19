package io.legohunter.ingress.source.photo.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoFingerprintServiceTest {
    private static final int APP1 = 0xE1;
    private static final int APP2 = 0xE2;
    private static final int APP13 = 0xED;
    private static final int APP14 = 0xEE;
    private static final int COMMENT = 0xFE;
    private static final int QUANTIZATION_TABLE = 0xDB;

    private final PhotoFingerprintService service = new PhotoFingerprintService();

    @Test
    void keywordsCaptionExifAndCommentsDoNotChangeTheFingerprint() {
        byte[] first = jpeg("picture",
                segment(APP1, "Exif exported at noon"),
                segment(APP13, "Photoshop keywords uuid:1 bl:3001"),
                segment(APP1, "http://ns.adobe.com/xap/1.0/ caption one"),
                segment(APP2, "ICC_PROFILE sRGB"),
                segment(QUANTIZATION_TABLE, "tables"));
        byte[] second = jpeg("picture",
                segment(APP1, "Exif exported in the evening, a longer text"),
                segment(APP13, "Photoshop keywords uuid:1 bl:3001 primary sealed"),
                segment(APP2, "ICC_PROFILE sRGB"),
                segment(COMMENT, "a comment"),
                segment(QUANTIZATION_TABLE, "tables"));

        assertThat(service.fingerprint(first)).isEqualTo(service.fingerprint(second));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void changedPictureDataChangesTheFingerprint() {
        byte[] original = jpeg("picture", segment(APP13, "keywords"), segment(QUANTIZATION_TABLE, "tables"));
        byte[] edited = jpeg("picture with a red brick", segment(APP13, "keywords"), segment(QUANTIZATION_TABLE, "tables"));

        assertThat(service.fingerprint(original)).isNotEqualTo(service.fingerprint(edited));
    }

    @Test
    void theColourProfileTheAdobeMarkerAndTheTablesArePartOfThePicture() {
        byte[] base = jpeg("picture", segment(APP2, "ICC_PROFILE sRGB"), segment(APP14, "Adobe"), segment(QUANTIZATION_TABLE, "tables"));

        assertThat(service.fingerprint(base)).isNotEqualTo(service.fingerprint(
                jpeg("picture", segment(APP2, "ICC_PROFILE Display P3"), segment(APP14, "Adobe"), segment(QUANTIZATION_TABLE, "tables"))));
        assertThat(service.fingerprint(base)).isNotEqualTo(service.fingerprint(
                jpeg("picture", segment(APP2, "ICC_PROFILE sRGB"), segment(QUANTIZATION_TABLE, "tables"))));
        assertThat(service.fingerprint(base)).isNotEqualTo(service.fingerprint(
                jpeg("picture", segment(APP2, "ICC_PROFILE sRGB"), segment(APP14, "Adobe"), segment(QUANTIZATION_TABLE, "other tables"))));
    }

    @Test
    void theFingerprintIsThirtyTwoLowerCaseHexCharacters() {
        assertThat(service.fingerprint(jpeg("picture", segment(QUANTIZATION_TABLE, "tables")))).matches("[0-9a-f]{32}");
    }

    @Test
    void aFileThatIsNotLaidOutAsAJpegIsFingerprintedWhole() throws Exception {
        byte[] notAJpeg = "plain text, not a picture".getBytes(StandardCharsets.US_ASCII);
        byte[] cutShort = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F, 0x00, 1, 2, 3};

        assertThat(service.fingerprint(notAJpeg)).isEqualTo(md5(notAJpeg));
        assertThat(service.fingerprint(cutShort)).isEqualTo(md5(cutShort));
        assertThat(service.fingerprint(new byte[0])).isEqualTo(md5(new byte[0]));
    }

    /** A file with the layout of a JPEG: start marker, the given segments, then the scan with the picture data. */
    private static byte[] jpeg(String pictureData, byte[]... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        for (byte[] segment : segments) {
            out.writeBytes(segment);
        }
        out.write(0xFF);
        out.write(0xDA);
        out.writeBytes(pictureData.getBytes(StandardCharsets.US_ASCII));
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    private static byte[] segment(int marker, String content) {
        byte[] body = content.getBytes(StandardCharsets.US_ASCII);
        int length = body.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(marker);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }
}
