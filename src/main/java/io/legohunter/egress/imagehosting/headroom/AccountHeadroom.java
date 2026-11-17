package io.legohunter.egress.imagehosting.headroom;

/**
 * Where the image host account stands against its item limit.
 *
 * @param known          false when the account could not be read; the other numbers are then null
 * @param itemCount      items on the account, including uploads this service has allowed since the last read
 * @param itemLimit      the account's item limit; null when it has none
 * @param reserveItems   items kept free below the limit
 * @param publishCeiling the count at which publishing new photos stops ({@code itemLimit - reserveItems})
 * @param available      new photos that may still be published; null when the account has no limit
 * @param message        why the account could not be read, or null
 */
public record AccountHeadroom(
        boolean known,
        Integer itemCount,
        Integer itemLimit,
        boolean limited,
        int reserveItems,
        Integer publishCeiling,
        Integer available,
        String message
) {
}
