package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.exception.BricklinkClientException;
import com.bricklink.api.rest.exception.BricklinkServerException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.Set;

/**
 * Says whether a failed BrickLink REST call may be a BrickLink-wide outage rather than a rejection of one request
 * (HOMELAB-81). The REST counterpart of the crawler's {@code BricklinkOutageClassifier} (HOMELAB-78), with the same
 * rules:
 * <ul>
 *   <li>a redirect (BrickLink's November 2026 maintenance window redirected every call);</li>
 *   <li>401, 403 and 429: the whole client is refused, blocked or rate-limited. A 401 also covers the IP-bound
 *       token failing after the home IP changes (TOKEN_IP_MISMATCHED);</li>
 *   <li>any 5xx;</li>
 *   <li>I/O failures: connection refused or reset, timeouts, DNS.</li>
 * </ul>
 * This is only a candidate. The REST client does not follow redirects, and BrickLink also answers a malformed request
 * URL with a redirect, so the sync service confirms an outage with a probe call before it stops counting attempts.
 * Everything else (400, 404, a validation error, a local check) is a failure of that request.
 *
 * <p>The jobs that read from BrickLink without a request queue (the order poll, the lot audit, the reconciliation)
 * use the same rule (HOMELAB-121): on an outage they log a warning and try again on their next run. A rejected token
 * (401) is treated as an outage everywhere, like the queued syncs do; it is meant to be caught by a dedicated alert,
 * not by error-level logging.
 */
public final class BricklinkRestOutageClassifier {
    private static final Set<Integer> CLIENT_WIDE_STATUS_CODES = Set.of(401, 403, 429);
    private static final String MAINTENANCE_HOST = "maintenance.bricklink.com";

    private BricklinkRestOutageClassifier() {
    }

    /** True when the failure may be a BrickLink-wide outage; see the class comment for the rules. */
    public static boolean mayBeOutage(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (mayBeOutageItself(cause)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mayBeOutageItself(Throwable failure) {
        if (failure instanceof BricklinkServerException) {
            return true;
        }
        if (failure instanceof BricklinkClientException client) {
            int code = client.getCode();
            return (code >= 300 && code <= 399) || code >= 500 || CLIENT_WIDE_STATUS_CODES.contains(code)
                    || mentionsMaintenance(client.getMessage());
        }
        if (failure instanceof ResourceAccessException || failure instanceof IOException) {
            return true;
        }
        return mentionsMaintenance(failure.getMessage());
    }

    private static boolean mentionsMaintenance(String message) {
        return message != null && message.contains(MAINTENANCE_HOST);
    }
}
