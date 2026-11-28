package io.legohunter.ingress.source.lego.woodentoys.capture;

import java.nio.file.Path;

/** lego.com as the capture reads it: overview pages as page data, and picture files. */
public interface LegoWoodenToysSource {

    /** The page data of overview page {@code page}, from 1. */
    String fetchPage(int page);

    /** Downloads the picture at {@code url} into {@code target}, replacing it. */
    void downloadPicture(String url, Path target);

    /** A request that did not succeed. */
    class SourceException extends RuntimeException {
        private final boolean notFound;

        public SourceException(String message, boolean notFound, Throwable cause) {
            super(message, cause);
            this.notFound = notFound;
        }

        /** True when the site answered that the address does not exist (a 4xx answer): trying again will not help. */
        public boolean isNotFound() {
            return notFound;
        }
    }
}
