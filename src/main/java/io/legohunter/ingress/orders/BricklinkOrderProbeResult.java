package io.legohunter.ingress.orders;

public record BricklinkOrderProbeResult(
        String outcome,
        int ordersDiscovered,
        int ordersFetched,
        int ordersFailed,
        int orderItemsFetched,
        long elapsedMillis,
        boolean applied,
        int ordersWritten,
        int orderItemsWritten,
        int payloadsWritten
) {
    public BricklinkOrderProbeResult(
            String outcome,
            int ordersDiscovered,
            int ordersFetched,
            int ordersFailed,
            int orderItemsFetched,
            long elapsedMillis
    ) {
        this(outcome, ordersDiscovered, ordersFetched, ordersFailed, orderItemsFetched, elapsedMillis, false, 0, 0, 0);
    }
}
