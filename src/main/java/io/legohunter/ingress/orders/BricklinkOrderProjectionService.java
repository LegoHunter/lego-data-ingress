package io.legohunter.ingress.orders;

import com.bricklink.api.rest.model.v1.Address;
import com.bricklink.api.rest.model.v1.Cost;
import com.bricklink.api.rest.model.v1.Order;
import io.legohunter.data.dao.MarketplaceOrderTransactionLinkDao;
import io.legohunter.data.dao.PartyDao;
import io.legohunter.data.dao.PartyExternalIdentityDao;
import io.legohunter.data.dao.TransactionCostDao;
import io.legohunter.data.dao.TransactionItemDao;
import io.legohunter.data.dao.TransactionItemRevenueDao;
import io.legohunter.data.dao.TransactionPartySnapshotDao;
import io.legohunter.data.dao.TransactionPlatformDao;
import io.legohunter.data.dao.TransactionsDao;
import io.legohunter.data.dto.MarketplaceOrder;
import io.legohunter.data.dto.MarketplaceOrderItem;
import io.legohunter.data.dto.MarketplaceOrderTransactionLink;
import io.legohunter.data.dto.Party;
import io.legohunter.data.dto.PartyExternalIdentity;
import io.legohunter.data.dto.TransactionCost;
import io.legohunter.data.dto.TransactionItem;
import io.legohunter.data.dto.TransactionItemRevenue;
import io.legohunter.data.dto.TransactionPartySnapshot;
import io.legohunter.data.dto.Transactions;
import io.legohunter.data.enums.CurrencyCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Projects a staged inbound BrickLink order into the canonical accounting tables.
 *
 * <p>The projection follows four lifecycle steps: resolve the parties and transaction header,
 * capture immutable party snapshots, reconcile active order lines and their revenue/cost facts,
 * then freeze the projection when BrickLink reports the order invoiced. Later polls of a frozen
 * order intentionally make no accounting changes.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BricklinkOrderProjectionService {
    static final long SELLER_PARTY_ID = 0L;
    static final String BRICKLINK_PLATFORM = "Bricklink";
    static final String SALE = "S";
    static final String ORDER_LINK = "ORDER";
    static final String ORDER_ITEM_LINK = "ORDER_ITEM";
    static final String OPEN = "OPEN";
    static final String INVOICED = "INVOICED";
    static final String ACTIVE = "ACTIVE";
    static final String UNLINKED = "UNLINKED";

    private final TransactionPlatformDao transactionPlatformDao;
    private final PartyDao partyDao;
    private final PartyExternalIdentityDao partyExternalIdentityDao;
    private final TransactionsDao transactionsDao;
    private final TransactionPartySnapshotDao transactionPartySnapshotDao;
    private final TransactionItemDao transactionItemDao;
    private final TransactionItemRevenueDao transactionItemRevenueDao;
    private final TransactionCostDao transactionCostDao;
    private final MarketplaceOrderTransactionLinkDao linkDao;

    @Transactional
    /**
     * Projects one staged BrickLink order and its current staged line items.
     *
     * @param marketplaceOrder the durable staged order header
     * @param marketplaceOrderItems the current durable staged order lines
     * @param bricklinkOrder the current API payload, including buyer and shipping details
     * @return the projected transaction and lifecycle counts
     */
    public BricklinkOrderProjectionResult project(
            MarketplaceOrder marketplaceOrder,
            Collection<MarketplaceOrderItem> marketplaceOrderItems,
            Order bricklinkOrder
    ) {
        int platformId = transactionPlatformDao.findTransactionPlatformByName(BRICKLINK_PLATFORM)
                .orElseThrow(() -> new IllegalStateException("Transaction platform Bricklink is not configured"))
                .getTransactionPlatformId();
        Party seller = partyDao.findPartyById(SELLER_PARTY_ID)
                .orElseThrow(() -> new IllegalStateException("Configured BrickLink seller party 0 was not found"));
        Party buyer = buyer(platformId, bricklinkOrder, marketplaceOrder);
        Transactions transaction = transactionsDao.findByTransactionPlatformIdAndTransactionOrderId(
                        platformId, marketplaceOrder.getExternalOrderId())
                .orElseGet(() -> createTransaction(platformId, seller.getPartyId(), buyer.getPartyId(), marketplaceOrder));

        Set<MarketplaceOrderTransactionLink> links = linkDao.findByMarketplaceOrderId(marketplaceOrder.getMarketplaceOrderId());
        MarketplaceOrderTransactionLink header = links.stream()
                .filter(link -> ORDER_LINK.equals(link.getLinkTypeCode()))
                .min(Comparator.comparing(MarketplaceOrderTransactionLink::getMarketplaceOrderTransactionLinkId))
                .orElseGet(() -> createHeaderLink(marketplaceOrder, transaction));
        if (INVOICED.equals(header.getLinkStatusCode())) {
            return new BricklinkOrderProjectionResult(transaction.getTransactionId(), true, 0, 0);
        }

        snapshot(transaction, seller, "SELLER", sellerName(bricklinkOrder, seller), null, seller.getPartyEmail());
        Address address = bricklinkOrder.getShipping() == null ? null : bricklinkOrder.getShipping().getAddress();
        snapshot(transaction, buyer, "BUYER", buyerName(bricklinkOrder, marketplaceOrder), address, marketplaceOrder.getBuyerEmail());

        Map<Integer, MarketplaceOrderTransactionLink> activeLineLinks = links.stream()
                .filter(link -> ORDER_ITEM_LINK.equals(link.getLinkTypeCode()))
                .filter(link -> link.getMarketplaceOrderItemId() != null)
                .collect(Collectors.toMap(MarketplaceOrderTransactionLink::getMarketplaceOrderItemId, Function.identity(), (first, ignored) -> first));
        int projectedItems = reconcileItems(transaction, marketplaceOrder, marketplaceOrderItems, activeLineLinks);
        unlinkRemovedOpenItems(marketplaceOrderItems, activeLineLinks);
        int projectedCosts = replaceCosts(transaction, bricklinkOrder.getCost(), marketplaceOrder.getCurrencyCode());

        if (Boolean.TRUE.equals(bricklinkOrder.getIs_invoiced())) {
            header.setLinkStatusCode(INVOICED);
            linkDao.update(header);
        }
        return new BricklinkOrderProjectionResult(transaction.getTransactionId(), Boolean.TRUE.equals(bricklinkOrder.getIs_invoiced()), projectedItems, projectedCosts);
    }

    /**
     * Resolves the buyer's reusable local party from the BrickLink username, creating both when absent.
     *
     * @param platformId the BrickLink transaction platform identifier
     * @param order the API order containing the buyer username
     * @param staged the staged order fallback data
     * @return the reusable local buyer party
     */
    private Party buyer(int platformId, Order order, MarketplaceOrder staged) {
        String buyerName = buyerName(order, staged);
        Optional<PartyExternalIdentity> identity = partyExternalIdentityDao.findByPlatformAndExternalPartyId(platformId, buyerName);
        if (identity.isPresent()) {
            return partyDao.findPartyById(identity.get().getPartyId())
                    .orElseThrow(() -> new IllegalStateException("BrickLink buyer identity references a missing party: " + buyerName));
        }
        Party party = Party.builder().partyLastName(buyerName).partyType("MARKETPLACE_BUYER")
                .partyActivationDate(LocalDateTime.now(ZoneOffset.UTC)).build();
        partyDao.insert(party);
        partyExternalIdentityDao.insert(PartyExternalIdentity.builder().partyId(party.getPartyId())
                .transactionPlatformId(platformId).externalPartyId(buyerName).build());
        return party;
    }

    /**
     * Creates the canonical transaction header for a previously unseen BrickLink order.
     *
     * @param platformId the BrickLink transaction platform identifier
     * @param sellerPartyId the store-owner party identifier
     * @param buyerPartyId the resolved buyer party identifier
     * @param order the staged order from which to derive header facts
     * @return the newly persisted transaction
     */
    private Transactions createTransaction(int platformId, Long sellerPartyId, Long buyerPartyId, MarketplaceOrder order) {
        Transactions transaction = Transactions.builder().transactionDate(order.getOrderedAt() == null ? LocalDate.now(ZoneOffset.UTC) : order.getOrderedAt().toLocalDate())
                .notes("BrickLink order " + order.getExternalOrderId()).fromPartyId(sellerPartyId).toPartyId(buyerPartyId)
                .transactionPlatformId(platformId).transactionOrderId(order.getExternalOrderId()).build();
        transactionsDao.insert(transaction);
        return transaction;
    }

    /**
     * Creates the order-level link that records whether the canonical projection remains mutable.
     *
     * @param order the staged marketplace order
     * @param transaction the canonical transaction
     * @return the new OPEN order-level link
     */
    private MarketplaceOrderTransactionLink createHeaderLink(MarketplaceOrder order, Transactions transaction) {
        return linkDao.insert(MarketplaceOrderTransactionLink.builder().marketplaceOrderId(order.getMarketplaceOrderId())
                .transactionId(transaction.getTransactionId()).linkTypeCode(ORDER_LINK).linkStatusCode(OPEN)
                .linkedAt(ZonedDateTime.now(ZoneOffset.UTC)).build());
    }

    /**
     * Writes an idempotent immutable party snapshot for a transaction role.
     *
     * @param transaction the canonical transaction
     * @param party the reusable party supplying fallback contact data
     * @param role the transaction role, such as SELLER or BUYER
     * @param displayName the order-time display name
     * @param address the normalized BrickLink shipping address when available
     * @param email the order-time email address
     */
    private void snapshot(Transactions transaction, Party party, String role, String displayName, Address address, String email) {
        transactionPartySnapshotDao.upsert(TransactionPartySnapshot.builder().transactionId(transaction.getTransactionId())
                .partyId(party.getPartyId()).partyRoleCode(role).displayName(displayName)
                .address1(address == null ? party.getPartyAddress1() : address.getAddress1()).address2(address == null ? party.getPartyAddress2() : address.getAddress2())
                .city(address == null ? party.getPartyCity() : address.getCity()).state(address == null ? party.getPartyState() : address.getState())
                .postalCode(address == null ? party.getPartyPostalCode() : address.getPostal_code()).countryCode(address == null ? party.getPartyCountryCode() : address.getCountry_code())
                .country(address == null ? party.getPartyCountry() : null).phone(party.getPartyPhone()).email(email).capturedAt(ZonedDateTime.now(ZoneOffset.UTC)).build());
    }

    /**
     * Reconciles current staged order lines to canonical sale items and final-price revenue.
     *
     * @param transaction the canonical transaction
     * @param order the staged marketplace order
     * @param items the current staged order lines
     * @param links existing order-item links indexed by staged line identifier
     * @return the number of current lines projected
     */
    private int reconcileItems(Transactions transaction, MarketplaceOrder order, Collection<MarketplaceOrderItem> items, Map<Integer, MarketplaceOrderTransactionLink> links) {
        int count = 0;
        for (MarketplaceOrderItem item : items) {
            if (item.getItemInventoryId() == null) throw new IllegalStateException("BrickLink order item is not linked to item inventory: " + item.getExternalOrderItemId());
            MarketplaceOrderTransactionLink link = links.get(item.getMarketplaceOrderItemId());
            TransactionItem transactionItem;
            if (link == null) {
                transactionItem = TransactionItem.builder().transactionId(transaction.getTransactionId()).transactionTypeCode(SALE)
                        .itemInventoryId(item.getItemInventoryId()).notes("BrickLink order item " + item.getExternalOrderItemId()).build();
                transactionItemDao.insert(transactionItem);
                link = linkDao.insert(MarketplaceOrderTransactionLink.builder().marketplaceOrderId(order.getMarketplaceOrderId()).transactionId(transaction.getTransactionId())
                        .marketplaceOrderItemId(item.getMarketplaceOrderItemId()).transactionItemId(transactionItem.getTransactionItemId())
                        .linkTypeCode(ORDER_ITEM_LINK).linkStatusCode(ACTIVE).linkedAt(ZonedDateTime.now(ZoneOffset.UTC)).build());
            } else {
                transactionItem = transactionItemDao.findById(link.getTransactionItemId()).orElseThrow(() -> new IllegalStateException("Order-item link references a missing transaction item"));
                if (UNLINKED.equals(link.getLinkStatusCode())) { link.setLinkStatusCode(ACTIVE); link.setUnlinkedAt(null); linkDao.update(link); }
            }
            BigDecimal unit = required(item.getFinalUnitPrice(), "final unit price", item);
            int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
            transactionItemRevenueDao.upsert(TransactionItemRevenue.builder().transactionItemId(transactionItem.getTransactionItemId())
                    .currencyCode(required(item.getCurrencyCode(), "currency", item)).unitAmount(unit).quantity(quantity).totalAmount(unit.multiply(BigDecimal.valueOf(quantity))).build());
            count++;
        }
        return count;
    }

    /**
     * Marks formerly active lines absent from the current open order as unlinked without deleting history.
     *
     * @param items the current staged order lines
     * @param links existing order-item links indexed by staged line identifier
     */
    private void unlinkRemovedOpenItems(Collection<MarketplaceOrderItem> items, Map<Integer, MarketplaceOrderTransactionLink> links) {
        Set<Integer> currentIds = items.stream().map(MarketplaceOrderItem::getMarketplaceOrderItemId).collect(Collectors.toSet());
        links.values().stream()
                .filter(link -> ACTIVE.equals(link.getLinkStatusCode()))
                .filter(link -> !currentIds.contains(link.getMarketplaceOrderItemId()))
                .forEach(link -> {
                    link.setLinkStatusCode(UNLINKED);
                    link.setUnlinkedAt(ZonedDateTime.now(ZoneOffset.UTC));
                    linkDao.update(link);
                });
    }

    /**
     * Replaces mutable open-order cost facts with the detailed BrickLink cost components.
     *
     * @param transaction the canonical transaction
     * @param cost the BrickLink order-cost payload
     * @param fallbackCurrency staged order currency used when the cost payload omits one
     * @return the number of non-null cost facts persisted
     */
    private int replaceCosts(Transactions transaction, Cost cost, String fallbackCurrency) {
        transactionCostDao.deleteTransactionCosts(transaction.getTransactionId());
        if (cost == null) return 0;
        CurrencyCode currency = currency(required(cost.getCurrency_code() == null ? fallbackCurrency : cost.getCurrency_code(), "order currency", null));
        List<CostFact> facts = List.of(new CostFact("SHIPPING", cost.getShipping(), "BrickLink shipping"), new CostFact("INSURANCE", cost.getInsurance(), "BrickLink insurance"),
                new CostFact("FEE", cost.getEtc1(), "BrickLink extra charge 1"), new CostFact("FEE", cost.getEtc2(), "BrickLink extra charge 2"),
                new CostFact("DISCOUNT", cost.getCredit(), "BrickLink credit"), new CostFact("DISCOUNT", cost.getCoupon(), "BrickLink coupon"),
                new CostFact("TAX", cost.getVat_amount(), "BrickLink VAT"), new CostFact("TAX", cost.getSalesTax_collected_by_BL(), "BrickLink sales tax"));
        int count = 0;
        for (CostFact fact : facts) if (fact.amount() != null) { transactionCostDao.insert(TransactionCost.builder().transactionId(transaction.getTransactionId()).costTypeCode(fact.type()).currencyCode(currency).amount(fact.amount()).notes(fact.notes()).build()); count++; }
        return count;
    }

    /** Returns the required stable BrickLink buyer username, using staged data only as a fallback. */
    private String buyerName(Order order, MarketplaceOrder staged) { return required(order.getBuyer_name() == null ? staged.getBuyerDisplayName() : order.getBuyer_name(), "buyer username", null); }

    /** Returns the seller display name supplied by BrickLink or the configured seller party fallback. */
    private String sellerName(Order order, Party seller) { return order.getSeller_name() == null ? seller.getPartyLastName() : order.getSeller_name(); }

    /** Converts a BrickLink ISO currency string to the canonical supported currency enum. */
    private CurrencyCode currency(String value) { try { return CurrencyCode.valueOf(value.trim().toUpperCase()); } catch (IllegalArgumentException e) { throw new IllegalStateException("Unsupported BrickLink currency: " + value, e); } }

    /** Validates a required BrickLink fact and identifies the source order line when applicable. */
    private <T> T required(T value, String field, MarketplaceOrderItem item) { if (value == null || value instanceof String text && text.isBlank()) throw new IllegalStateException("Missing BrickLink " + field + (item == null ? "" : " for " + item.getExternalOrderItemId())); return value; }
    private record CostFact(String type, Double amount, String notes) { }
}
