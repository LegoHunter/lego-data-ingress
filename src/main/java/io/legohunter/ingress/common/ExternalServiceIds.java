package io.legohunter.ingress.common;

/** {@code external_service.external_service_id} values for the external services this module reads from. */
public final class ExternalServiceIds {
    /** {@code external_service} row with {@code service_code = 'BRICKLINK'}. */
    public static final int BRICKLINK = 2;
    /** {@code external_service} row with {@code service_code = 'REBRICKABLE'}. */
    public static final int REBRICKABLE = 9;

    private ExternalServiceIds() {
    }
}
