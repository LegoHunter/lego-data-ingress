package io.legohunter.ingress.pricing;

import com.bricklink.api.ajax.exception.BricklinkAjaxClientException;
import com.bricklink.api.ajax.exception.BricklinkAjaxServerException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.Set;

/**
 * Tells a BrickLink-wide outage from a problem with one item (HOMELAB-78).
 *
 * <p>An outage says nothing about the item being crawled: every item would fail the same way. Counting it against the
 * target pushed every target in the queue into a full-cadence backoff during the November 2026 maintenance window,
 * when every AJAX call was redirected to {@code maintenance.bricklink.com}. These are outages:
 * <ul>
 *   <li>a redirect: the AJAX endpoints answer an item directly and never redirect for it, so a 3xx means BrickLink is
 *       sending every caller elsewhere (maintenance, recovery or a login page);</li>
 *   <li>403 and 429: BrickLink is blocking or rate-limiting this client, not refusing one item;</li>
 *   <li>any 5xx;</li>
 *   <li>I/O failures: connection refused or reset, timeouts, DNS.</li>
 * </ul>
 * Everything else (an unknown or ambiguous item, a 404, an unparseable page) stays a failure of that item.
 */
final class BricklinkOutageClassifier {
    private static final Set<Integer> BLOCKING_STATUS_CODES = Set.of(403, 429);
    private static final String MAINTENANCE_HOST = "maintenance.bricklink.com";

    private BricklinkOutageClassifier() {
    }

    static boolean isOutage(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (isOutageItself(cause)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOutageItself(Throwable failure) {
        if (failure instanceof BricklinkAjaxServerException) {
            return true;
        }
        if (failure instanceof BricklinkAjaxClientException client) {
            Integer status = client.getStatusCode();
            if (status != null && (isRedirect(status) || BLOCKING_STATUS_CODES.contains(status))) {
                return true;
            }
            return mentionsMaintenance(client.getMessage());
        }
        if (failure instanceof ResourceAccessException || failure instanceof IOException) {
            return true;
        }
        return mentionsMaintenance(failure.getMessage());
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status <= 399;
    }

    private static boolean mentionsMaintenance(String message) {
        return message != null && message.contains(MAINTENANCE_HOST);
    }
}
