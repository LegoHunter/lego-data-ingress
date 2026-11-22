package io.legohunter.ingress.photoupload;

import org.springframework.http.HttpStatus;

/** A request that is refused, with the HTTP status to answer it with. The message never contains a token. */
public class PhotoUploadRefusedException extends RuntimeException {
    private final HttpStatus status;

    public PhotoUploadRefusedException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
