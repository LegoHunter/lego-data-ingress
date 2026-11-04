package io.legohunter.ingress.source.bricklink.pricing;

import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Runs each callback directly and counts how many transactions the service under test opened. An
 * exception thrown by the callback propagates, as it does from a real {@code TransactionTemplate}
 * after rolling back.
 */
final class CountingTransactions implements TransactionOperations {
    int executions;

    @Override
    public <T> T execute(TransactionCallback<T> action) {
        executions++;
        return action.doInTransaction(new SimpleTransactionStatus());
    }
}
