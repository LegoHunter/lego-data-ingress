package io.legohunter.ingress.marketplace.bricklink;

/**
 * The BrickLink web gateway: the service that holds the signed-in BrickLink website session and
 * makes the lot changes the official API cannot: the custom image (HOMELAB-92) and the Extended
 * Description (HOMELAB-82).
 */
public interface BricklinkWebGatewayClient {

    /** Sets or replaces the custom image of a lot. Never throws: every outcome is an {@link Answer}. */
    Answer putLotImage(long lotId, String fileName, byte[] image);

    /**
     * Sets, replaces or (with an empty text) clears the Extended Description of a lot. The gateway reads it
     * back before answering. Never throws: every outcome is an {@link Answer}.
     */
    Answer putExtendedDescription(long lotId, String text);

    enum Kind {
        /** The gateway stored the change and read it back on the lot. */
        STORED,
        /** The gateway cannot work now (signed out, recovering, unreachable). Nothing wrong with the request: try later. */
        CANNOT_WORK,
        /** The gateway refused this request for good (wrong stockroom, unknown lot, bad file, bad token). */
        REFUSED,
        /** The attempt failed in a way a retry may fix. */
        FAILED
    }

    /**
     * @param code    the gateway's error code (for example SESSION_SIGNED_OUT), or a local one
     *                (UNREACHABLE, HTTP_500); null when stored
     * @param message safe to log and to store on the sync request
     */
    record Answer(Kind kind, String code, String message) {
        static Answer stored() {
            return new Answer(Kind.STORED, null, null);
        }
    }
}
