package io.legohunter.ingress.marketplace.bricklink;

record BricklinkListingCreateSafetyResult(
        boolean allowed,
        String statusCode,
        String message,
        String desiredRemarks,
        String desiredRemarksHash,
        Integer effectiveColorId,
        boolean stockRoom,
        String stockRoomId,
        boolean publiclyAvailable
) {
    static BricklinkListingCreateSafetyResult allowed(
            String desiredRemarks,
            Integer effectiveColorId,
            boolean stockRoom,
            String stockRoomId,
            boolean publiclyAvailable
    ) {
        return new BricklinkListingCreateSafetyResult(
                true,
                "VERIFIED",
                "Local listing passed BrickLink create safety checks",
                desiredRemarks,
                BricklinkInventorySystemRemarks.sha256(desiredRemarks),
                effectiveColorId,
                stockRoom,
                stockRoomId,
                publiclyAvailable
        );
    }

    static BricklinkListingCreateSafetyResult blocked(String statusCode, String message) {
        return new BricklinkListingCreateSafetyResult(false, statusCode, message, null, null, null, false, null, false);
    }
}
