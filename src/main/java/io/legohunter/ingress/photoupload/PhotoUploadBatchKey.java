package io.legohunter.ingress.photoupload;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The batch and file name inside an upload key of the form photos/&lt;batchId&gt;/&lt;fileName&gt; (HOMELAB-135). */
public record PhotoUploadBatchKey(String batchId, String fileName) {

    private static final Pattern UPLOAD_KEY =
            Pattern.compile("^photos/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/([^/]+)$");

    public static Optional<PhotoUploadBatchKey> parse(String objectKey) {
        if (objectKey == null) {
            return Optional.empty();
        }
        Matcher m = UPLOAD_KEY.matcher(objectKey);
        return m.matches() ? Optional.of(new PhotoUploadBatchKey(m.group(1), m.group(2))) : Optional.empty();
    }
}
