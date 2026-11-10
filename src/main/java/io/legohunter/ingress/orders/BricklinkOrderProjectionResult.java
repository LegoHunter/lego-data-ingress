package io.legohunter.ingress.orders;

public record BricklinkOrderProjectionResult(Long transactionId, boolean invoiced, int projectedItems, int projectedCosts) { }
