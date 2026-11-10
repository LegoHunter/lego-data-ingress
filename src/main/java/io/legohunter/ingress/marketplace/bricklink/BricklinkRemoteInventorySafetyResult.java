package io.legohunter.ingress.marketplace.bricklink;

record BricklinkRemoteInventorySafetyResult(
        boolean allowed,
        String statusCode,
        String message,
        String desiredRemarks,
        String desiredRemarksHash
) {
    static BricklinkRemoteInventorySafetyResult allowed(String desiredRemarks) {
        return new BricklinkRemoteInventorySafetyResult(
                true,
                "VERIFIED",
                "Remote inventory passed safety checks",
                desiredRemarks,
                BricklinkInventorySystemRemarks.sha256(desiredRemarks)
        );
    }

    static BricklinkRemoteInventorySafetyResult blocked(String statusCode, String message) {
        return new BricklinkRemoteInventorySafetyResult(false, statusCode, message, null, null);
    }
}
