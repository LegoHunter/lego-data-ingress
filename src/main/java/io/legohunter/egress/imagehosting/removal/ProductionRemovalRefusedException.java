package io.legohunter.egress.imagehosting.removal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A lower-environment removal was asked of the production runtime. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ProductionRemovalRefusedException extends RuntimeException {
    public ProductionRemovalRefusedException(String message) {
        super(message);
    }
}
