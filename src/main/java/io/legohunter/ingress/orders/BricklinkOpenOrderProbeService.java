package io.legohunter.ingress.orders;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Cost;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Payment;
import com.bricklink.api.rest.model.v1.Shipping;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceOrderDao;
import io.legohunter.data.dao.MarketplaceOrderItemDao;
import io.legohunter.data.dao.MarketplaceOrderPayloadDao;
import io.legohunter.data.dao.MarketplaceOrderSyncRunDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceOrder;
import io.legohunter.data.dto.MarketplaceOrderItem;
import io.legohunter.data.dto.MarketplaceOrderPayload;
import io.legohunter.data.dto.MarketplaceOrderSyncRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.orders.sync.scheduled", name = "enabled", havingValue = "true")
public class BricklinkOpenOrderProbeService {
    private static final String CANCELLED = "CANCELLED";
    private static final String ORDER_RESPONSE_PAYLOAD = "ORDER_RESPONSE";
    private static final String ORDER_ITEMS_RESPONSE_PAYLOAD = "ORDER_ITEMS_RESPONSE";
    private static final String RESERVED_FOR_ORDER = "RESERVED_FOR_ORDER";

    private final BricklinkRestClient bricklinkRestClient;
    private final BricklinkOrderSyncProperties properties;
    private final BricklinkOrderSyncMetricsService metricsService;
    private final MarketplaceOrderSyncRunDao marketplaceOrderSyncRunDao;
    private final MarketplaceOrderDao marketplaceOrderDao;
    private final MarketplaceOrderItemDao marketplaceOrderItemDao;
    private final MarketplaceOrderPayloadDao marketplaceOrderPayloadDao;
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao;
    private final MarketplaceListingDao marketplaceListingDao;
    private final ItemInventoryDao itemInventoryDao;
    private final BricklinkOrderProjectionService projectionService;
    private final ObjectMapper objectMapper;

    public BricklinkOrderProbeResult runOnce() {
        long startedAt = System.currentTimeMillis();
        ZonedDateTime startedAtUtc = ZonedDateTime.now(ZoneOffset.UTC);
        String providerTag = properties.effectiveMetricsTag();
        List<String> statuses = properties.effectiveStatuses();
        boolean apply = properties.getScheduled().isApply();
        MarketplaceOrderSyncRun syncRun = null;

        log.info(
                "bricklink.order_sync.probe.started provider={} direction={} statuses={} includeUnfiledCancelled={} apply={}",
                providerTag,
                properties.effectiveDirection(),
                statuses,
                properties.isIncludeUnfiledCancelled(),
                apply
        );

        try {
            if (apply) {
                syncRun = marketplaceOrderSyncRunDao.insert(MarketplaceOrderSyncRun.builder()
                        .marketplaceCode(properties.effectiveMarketplaceCode())
                        .syncJobName("bricklink-order-sync")
                        .syncDirection(properties.effectiveDirection())
                        .syncStatusCode("STARTED")
                        .startedAt(startedAtUtc)
                        .ordersDiscovered(0)
                        .ordersFetched(0)
                        .ordersFailed(0)
                        .build());
            }

            List<Order> orderSummaries = getOpenOrderSummaries(statuses);
            metricsService.recordOrders(providerTag, "discovered", orderSummaries.size());
            if (syncRun != null) {
                syncRun.setOrdersDiscovered(orderSummaries.size());
                marketplaceOrderSyncRunDao.update(syncRun);
            }

            if (orderSummaries.isEmpty()) {
                long elapsedMillis = System.currentTimeMillis() - startedAt;
                metricsService.recordRun(providerTag, "no_work", elapsedMillis);
                completeSyncRun(syncRun, "NO_WORK", 0, 0, null);
                log.info(
                        "bricklink.order_sync.probe.no_work provider={} elapsedMillis={}",
                        providerTag,
                        elapsedMillis
                );
                return new BricklinkOrderProbeResult("NO_WORK", 0, 0, 0, 0, elapsedMillis, apply, 0, 0, 0);
            }

            int ordersFetched = 0;
            int ordersFailed = 0;
            int orderItemsFetched = 0;
            int ordersWritten = 0;
            int orderItemsWritten = 0;
            int payloadsWritten = 0;
            for (Order orderSummary : orderSummaries) {
                String orderId = orderSummary.getOrder_id();
                try {
                    Order order = data(bricklinkRestClient.getOrder(orderId));
                    if (order == null) {
                        order = orderSummary;
                    }
                    List<OrderItem> orderItems = getOrderItems(orderId);
                    ordersFetched++;
                    orderItemsFetched += orderItems.size();
                    logFetchedOrder(providerTag, order, orderItems.size());
                    logOrderCoverage(providerTag, order, orderItems.size());
                    logOrderItemCoverage(providerTag, order.getOrder_id(), orderItems);
                    if (syncRun != null) {
                        BricklinkOrderWriteResult writeResult = syncOrder(syncRun.getMarketplaceOrderSyncRunId(), order, orderItems);
                        ordersWritten += writeResult.ordersWritten();
                        orderItemsWritten += writeResult.orderItemsWritten();
                        payloadsWritten += writeResult.payloadsWritten();
                    }
                } catch (RuntimeException e) {
                    ordersFailed++;
                    log.warn(
                            "bricklink.order_sync.probe.order_failed provider={} orderId={} summaryStatus={} message={}",
                            providerTag,
                            orderId,
                            orderSummary.getStatus(),
                            e.getMessage(),
                            e
                    );
                }
            }

            long elapsedMillis = System.currentTimeMillis() - startedAt;
            String outcome = outcome(ordersFetched, ordersFailed);
            metricsService.recordRun(providerTag, outcome.toLowerCase(), elapsedMillis);
            metricsService.recordOrders(providerTag, "fetched", ordersFetched);
            metricsService.recordOrders(providerTag, "failed", ordersFailed);
            metricsService.recordOrders(providerTag, "written", ordersWritten);
            completeSyncRun(syncRun, outcome, ordersFetched, ordersFailed, null);
            log.info(
                    "bricklink.order_sync.probe.completed provider={} outcome={} apply={} ordersDiscovered={} ordersFetched={} ordersFailed={} orderItemsFetched={} ordersWritten={} orderItemsWritten={} payloadsWritten={} elapsedMillis={}",
                    providerTag,
                    outcome,
                    apply,
                    orderSummaries.size(),
                    ordersFetched,
                    ordersFailed,
                    orderItemsFetched,
                    ordersWritten,
                    orderItemsWritten,
                    payloadsWritten,
                    elapsedMillis
            );

            return new BricklinkOrderProbeResult(
                    outcome,
                    orderSummaries.size(),
                    ordersFetched,
                    ordersFailed,
                    orderItemsFetched,
                    elapsedMillis,
                    apply,
                    ordersWritten,
                    orderItemsWritten,
                    payloadsWritten
            );
        } catch (RuntimeException e) {
            completeSyncRun(syncRun, "FAILED", 0, 1, e.getMessage());
            throw e;
        }
    }

    List<Order> getOpenOrderSummaries(List<String> statuses) {
        Map<String, Order> ordersById = new LinkedHashMap<>();
        for (String status : statuses) {
            addOrders(ordersById, baseParams(), status);
        }
        if (properties.isIncludeUnfiledCancelled() && !statuses.contains(CANCELLED)) {
            Map<String, Object> cancelledParams = baseParams();
            cancelledParams.put("filed", false);
            addOrders(ordersById, cancelledParams, CANCELLED);
        }
        return new ArrayList<>(ordersById.values());
    }

    List<OrderItem> getOrderItems(String orderId) {
        List<List<OrderItem>> batches = data(bricklinkRestClient.getOrderItems(orderId));
        if (batches == null || batches.isEmpty()) {
            return List.of();
        }
        return batches.stream()
                .filter(batch -> batch != null && !batch.isEmpty())
                .flatMap(Collection::stream)
                .toList();
    }

    private BricklinkOrderWriteResult syncOrder(Integer syncRunId, Order order, List<OrderItem> orderItems) {
        String orderJson = payloadJson(order);
        String orderPayloadHash = payloadHash(orderJson);
        MarketplaceOrder marketplaceOrder = marketplaceOrder(syncRunId, order, orderPayloadHash);
        marketplaceOrderDao.upsert(marketplaceOrder);
        MarketplaceOrder persistedOrder = marketplaceOrderDao.findByMarketplaceCodeAndExternalOrderId(
                marketplaceOrder.getMarketplaceCode(),
                marketplaceOrder.getExternalOrderId()
        ).orElseThrow(() -> new IllegalStateException("Marketplace order was not found after upsert: " + marketplaceOrder.getExternalOrderId()));

        marketplaceOrderPayloadDao.insert(marketplaceOrderPayload(
                persistedOrder.getMarketplaceOrderId(),
                syncRunId,
                ORDER_RESPONSE_PAYLOAD,
                orderPayloadHash,
                orderJson
        ));

        String orderItemsJson = payloadJson(orderItems);
        marketplaceOrderPayloadDao.insert(marketplaceOrderPayload(
                persistedOrder.getMarketplaceOrderId(),
                syncRunId,
                ORDER_ITEMS_RESPONSE_PAYLOAD,
                payloadHash(orderItemsJson),
                orderItemsJson
        ));

        int orderItemsWritten = syncOrderItems(persistedOrder.getMarketplaceOrderId(), order.getOrder_id(), order.getStatus(), orderItems);
        BricklinkOrderProjectionResult projection = projectionService.project(
                persistedOrder,
                marketplaceOrderItemDao.findByMarketplaceOrderId(persistedOrder.getMarketplaceOrderId()),
                order
        );
        log.info(
                "bricklink.order_sync.probe.projected externalOrderId={} transactionId={} invoiced={} projectedItems={} projectedCosts={}",
                persistedOrder.getExternalOrderId(),
                projection.transactionId(),
                projection.invoiced(),
                projection.projectedItems(),
                projection.projectedCosts()
        );
        return new BricklinkOrderWriteResult(1, orderItemsWritten, 2);
    }

    private int syncOrderItems(Integer marketplaceOrderId, String externalOrderId, String orderStatus, List<OrderItem> orderItems) {
        Map<String, MarketplaceOrderItem> existingItemsByLineKey = marketplaceOrderItemDao.findByMarketplaceOrderId(marketplaceOrderId).stream()
                .filter(existingItem -> present(existingItem.getExternalOrderItemId()))
                .collect(Collectors.toMap(
                        MarketplaceOrderItem::getExternalOrderItemId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Set<String> seenLineKeys = new HashSet<>();
        Set<Integer> reservedInventoryIds = new HashSet<>();
        int written = 0;
        for (int index = 0; index < orderItems.size(); index++) {
            OrderItem orderItem = orderItems.get(index);
            String lineKey = externalOrderItemId(externalOrderId, orderItem, index);
            seenLineKeys.add(lineKey);
            MarketplaceOrderItem marketplaceOrderItem = marketplaceOrderItem(marketplaceOrderId, lineKey, orderItem);
            MarketplaceOrderItem existingItem = existingItemsByLineKey.get(lineKey);
            if (existingItem == null) {
                marketplaceOrderItemDao.insert(marketplaceOrderItem);
            } else {
                marketplaceOrderItem.setMarketplaceOrderItemId(existingItem.getMarketplaceOrderItemId());
                marketplaceOrderItemDao.update(marketplaceOrderItem);
            }
            if (!CANCELLED.equalsIgnoreCase(orderStatus) && marketplaceOrderItem.getItemInventoryId() != null) {
                reservedInventoryIds.add(marketplaceOrderItem.getItemInventoryId());
            }
            written++;
        }

        existingItemsByLineKey.values().stream()
                .filter(existingItem -> !seenLineKeys.contains(existingItem.getExternalOrderItemId()))
                .forEach(existingItem -> marketplaceOrderItemDao.delete(existingItem.getMarketplaceOrderItemId()));
        reserveInventory(marketplaceOrderId, externalOrderId, reservedInventoryIds);
        return written;
    }

    private void reserveInventory(Integer marketplaceOrderId, String externalOrderId, Set<Integer> itemInventoryIds) {
        ZonedDateTime reservedAt = ZonedDateTime.now(ZoneOffset.UTC);
        itemInventoryIds.forEach(itemInventoryId -> {
            itemInventoryDao.updateInventoryState(itemInventoryId, RESERVED_FOR_ORDER, reservedAt);
            log.info(
                    "bricklink.order_sync.probe.inventory_reserved marketplaceOrderId={} externalOrderId={} itemInventoryId={} stateChangedAt={}",
                    marketplaceOrderId,
                    externalOrderId,
                    itemInventoryId,
                    reservedAt
            );
        });
    }

    private MarketplaceOrder marketplaceOrder(Integer syncRunId, Order order, String payloadHash) {
        Cost cost = order.getCost();
        Cost displayCost = order.getDisp_cost();
        Payment payment = order.getPayment();
        Shipping shipping = order.getShipping();
        return MarketplaceOrder.builder()
                .lastSyncRunId(syncRunId)
                .marketplaceCode(properties.effectiveMarketplaceCode())
                .externalOrderId(order.getOrder_id())
                .orderDirection(properties.effectiveDirection())
                .externalStatusCode(order.getStatus())
                .orderedAt(zoned(order.getDate_ordered()))
                .statusChangedAt(zoned(order.getDate_status_changed()))
                .buyerDisplayName(order.getBuyer_name())
                .buyerEmail(order.getBuyer_email())
                .paymentStatusCode(payment == null ? null : payment.getStatus())
                .paymentMethod(payment == null ? null : payment.getMethod())
                .paymentCurrencyCode(payment == null ? null : payment.getCurrency_code())
                .paidAt(payment == null ? null : zoned(payment.getDate_paid()))
                .shippingMethod(shipping == null ? null : shipping.getMethod())
                .shippingMethodId(shipping == null ? null : shipping.getMethod_id())
                .shippingAddressPresent(shipping != null && shipping.getAddress() != null)
                .trackingPresent(shipping != null && present(shipping.getTracking_no()))
                .subtotalAmount(amount(cost == null ? null : cost.getSubtotal()))
                .shippingAmount(amount(cost == null ? null : cost.getShipping()))
                .grandTotalAmount(amount(cost == null ? null : cost.getGrand_total()))
                .currencyCode(cost == null ? null : cost.getCurrency_code())
                .displayCurrencyCode(displayCost == null ? null : displayCost.getCurrency_code())
                .totalCount(order.getTotal_count())
                .uniqueCount(order.getUnique_count())
                .totalWeight(amount(order.getTotal_weight()))
                .invoiced(order.getIs_invoiced())
                .filed(order.getIs_filed())
                .sentDriveThru(order.getSent_drive_thru())
                .requireInsurance(order.getRequire_insurance())
                .payloadHash(payloadHash)
                .lastSeenAt(ZonedDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private MarketplaceOrderItem marketplaceOrderItem(Integer marketplaceOrderId, String externalOrderItemId, OrderItem orderItem) {
        com.bricklink.api.rest.model.v1.Item item = orderItem.getItem();
        String orderItemJson = payloadJson(orderItem);
        OrderItemListingLink listingLink = listingLink(orderItem);
        return MarketplaceOrderItem.builder()
                .marketplaceOrderId(marketplaceOrderId)
                .marketplaceListingId(listingLink.marketplaceListingId())
                .itemInventoryId(listingLink.itemInventoryId())
                .externalOrderItemId(externalOrderItemId)
                .externalInventoryId(orderItem.getInventory_id() == null ? null : orderItem.getInventory_id().toString())
                .externalItemNo(item == null ? null : item.getNo())
                .externalItemType(item == null ? null : item.getType())
                .colorId(orderItem.getColor_id())
                .colorName(orderItem.getColor_name())
                .quantity(Objects.requireNonNullElse(orderItem.getQuantity(), 0))
                .conditionCode(orderItem.getNew_or_used())
                .completenessCode(orderItem.getCompleteness())
                .unitPrice(amount(orderItem.getUnit_price()))
                .finalUnitPrice(amount(orderItem.getUnit_price_final()))
                .currencyCode(orderItem.getCurrency_code())
                .itemWeight(amount(orderItem.getWeight()))
                .remarks(orderItem.getRemarks())
                .description(orderItem.getDescription())
                .payloadHash(payloadHash(orderItemJson))
                .build();
    }

    private OrderItemListingLink listingLink(OrderItem orderItem) {
        Long inventoryId = orderItem.getInventory_id();
        if (inventoryId == null || inventoryId > Integer.MAX_VALUE) {
            return OrderItemListingLink.unresolved();
        }
        return bricklinkMarketplaceListingDao.findByBricklinkInventoryId(inventoryId.intValue())
                .map(this::listingLink)
                .orElseGet(OrderItemListingLink::unresolved);
    }

    private OrderItemListingLink listingLink(BricklinkMarketplaceListing bricklinkListing) {
        Integer marketplaceListingId = bricklinkListing.getMarketplaceListingId();
        if (marketplaceListingId == null) {
            return OrderItemListingLink.unresolved();
        }
        return marketplaceListingDao.findByMarketplaceListingId(marketplaceListingId)
                .map(listing -> listingLink(marketplaceListingId, listing))
                .orElse(new OrderItemListingLink(marketplaceListingId, null));
    }

    private OrderItemListingLink listingLink(Integer marketplaceListingId, MarketplaceListing listing) {
        return new OrderItemListingLink(marketplaceListingId, listing.getItemInventoryId());
    }

    private MarketplaceOrderPayload marketplaceOrderPayload(
            Integer marketplaceOrderId,
            Integer syncRunId,
            String payloadTypeCode,
            String payloadHash,
            String payloadJson
    ) {
        return MarketplaceOrderPayload.builder()
                .marketplaceOrderId(marketplaceOrderId)
                .marketplaceOrderSyncRunId(syncRunId)
                .payloadTypeCode(payloadTypeCode)
                .payloadHash(payloadHash)
                .payloadJson(payloadJson)
                .capturedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private void completeSyncRun(MarketplaceOrderSyncRun syncRun, String status, int ordersFetched, int ordersFailed, String errorMessage) {
        if (syncRun == null) {
            return;
        }
        syncRun.setSyncStatusCode(status);
        syncRun.setCompletedAt(ZonedDateTime.now(ZoneOffset.UTC));
        syncRun.setOrdersFetched(ordersFetched);
        syncRun.setOrdersFailed(ordersFailed);
        syncRun.setErrorMessage(errorMessage);
        marketplaceOrderSyncRunDao.update(syncRun);
    }

    private String externalOrderItemId(String externalOrderId, OrderItem orderItem, int index) {
        if (orderItem.getInventory_id() != null) {
            return externalOrderId + ":inventory:" + orderItem.getInventory_id();
        }
        return externalOrderId + ":line:" + index;
    }

    private String payloadJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize BrickLink order sync payload", e);
        }
    }

    private String payloadHash(String payloadJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payloadJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private BigDecimal amount(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private ZonedDateTime zoned(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(ZoneOffset.UTC);
    }

    private void addOrders(Map<String, Order> ordersById, Map<String, Object> params, String status) {
        List<Order> orders = data(bricklinkRestClient.getOrders(params, List.of(status)));
        if (orders == null) {
            return;
        }
        for (Order order : orders) {
            if (order != null && order.getOrder_id() != null) {
                ordersById.putIfAbsent(order.getOrder_id(), order);
            }
        }
    }

    private Map<String, Object> baseParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("direction", properties.effectiveDirection());
        return params;
    }

    private void logFetchedOrder(String providerTag, Order order, int orderItemCount) {
        Cost cost = order.getCost();
        Shipping shipping = order.getShipping();
        log.info(
                "bricklink.order_sync.probe.order_fetched provider={} orderId={} status={} dateOrdered={} dateStatusChanged={} totalCount={} uniqueCount={} orderItemCount={} currency={} subtotal={} grandTotal={} shippingCost={} shippingMethod={} trackingPresent={} paymentStatus={}",
                providerTag,
                order.getOrder_id(),
                order.getStatus(),
                date(order.getDate_ordered()),
                date(order.getDate_status_changed()),
                order.getTotal_count(),
                order.getUnique_count(),
                orderItemCount,
                cost == null ? null : cost.getCurrency_code(),
                cost == null ? null : cost.getSubtotal(),
                cost == null ? null : cost.getGrand_total(),
                cost == null ? null : cost.getShipping(),
                shipping == null ? null : shipping.getMethod(),
                shipping != null && shipping.getTracking_no() != null && !shipping.getTracking_no().isBlank(),
                order.getPayment() == null ? null : order.getPayment().getStatus()
        );
    }

    private void logOrderCoverage(String providerTag, Order order, int orderItemCount) {
        Cost cost = order.getCost();
        Cost displayCost = order.getDisp_cost();
        Shipping shipping = order.getShipping();
        log.info(
                "bricklink.order_sync.probe.coverage provider={} orderId={} status={} itemCount={} hasBuyerName={} hasBuyerEmail={} hasPayment={} hasPaymentMethod={} hasPaymentCurrency={} hasPaymentDatePaid={} hasPaymentStatus={} hasShipping={} hasShippingMethod={} hasShippingMethodId={} hasShippingAddress={} hasTracking={} hasCost={} hasDisplayCost={} currency={} displayCurrency={} hasRemarks={} hasTotalCount={} hasUniqueCount={} hasTotalWeight={} isInvoiced={} isFiled={} sentDriveThru={} requireInsurance={}",
                providerTag,
                order.getOrder_id(),
                order.getStatus(),
                orderItemCount,
                present(order.getBuyer_name()),
                present(order.getBuyer_email()),
                order.getPayment() != null,
                order.getPayment() != null && present(order.getPayment().getMethod()),
                order.getPayment() != null && present(order.getPayment().getCurrency_code()),
                order.getPayment() != null && order.getPayment().getDate_paid() != null,
                order.getPayment() != null && present(order.getPayment().getStatus()),
                shipping != null,
                shipping != null && present(shipping.getMethod()),
                shipping != null && present(shipping.getMethod_id()),
                shipping != null && shipping.getAddress() != null,
                shipping != null && present(shipping.getTracking_no()),
                cost != null,
                displayCost != null,
                cost == null ? null : cost.getCurrency_code(),
                displayCost == null ? null : displayCost.getCurrency_code(),
                present(order.getRemarks()),
                order.getTotal_count() != null,
                order.getUnique_count() != null,
                order.getTotal_weight() != null,
                order.getIs_invoiced(),
                order.getIs_filed(),
                order.getSent_drive_thru(),
                order.getRequire_insurance()
        );
    }

    private void logOrderItemCoverage(String providerTag, String orderId, List<OrderItem> orderItems) {
        log.info(
                "bricklink.order_sync.probe.item_coverage provider={} orderId={} itemCount={} hasInventoryIdCount={} hasItemCount={} hasItemNoCount={} hasItemTypeCount={} hasColorIdCount={} hasColorNameCount={} hasQuantityCount={} hasConditionCount={} hasCompletenessCount={} hasUnitPriceCount={} hasFinalUnitPriceCount={} hasCurrencyCount={} hasRemarksCount={} hasDescriptionCount={} hasWeightCount={}",
                providerTag,
                orderId,
                orderItems.size(),
                count(orderItems, item -> item.getInventory_id() != null),
                count(orderItems, item -> item.getItem() != null),
                count(orderItems, item -> item.getItem() != null && present(item.getItem().getNo())),
                count(orderItems, item -> item.getItem() != null && present(item.getItem().getType())),
                count(orderItems, item -> item.getColor_id() != null),
                count(orderItems, item -> present(item.getColor_name())),
                count(orderItems, item -> item.getQuantity() != null),
                count(orderItems, item -> present(item.getNew_or_used())),
                count(orderItems, item -> present(item.getCompleteness())),
                count(orderItems, item -> item.getUnit_price() != null),
                count(orderItems, item -> item.getUnit_price_final() != null),
                count(orderItems, item -> present(item.getCurrency_code())),
                count(orderItems, item -> present(item.getRemarks())),
                count(orderItems, item -> present(item.getDescription())),
                count(orderItems, item -> item.getWeight() != null)
        );
    }

    private int count(List<OrderItem> orderItems, java.util.function.Predicate<OrderItem> predicate) {
        return (int) orderItems.stream().filter(predicate).count();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private LocalDateTime date(LocalDateTime dateTime) {
        return dateTime;
    }

    private <T> T data(BricklinkResource<T> resource) {
        return resource == null ? null : resource.getData();
    }

    private String outcome(int ordersFetched, int ordersFailed) {
        if (ordersFailed == 0) {
            return "SUCCESS";
        }
        if (ordersFetched == 0) {
            return "FAILED";
        }
        return "PARTIAL_FAILURE";
    }

    private record BricklinkOrderWriteResult(int ordersWritten, int orderItemsWritten, int payloadsWritten) {
    }

    private record OrderItemListingLink(Integer marketplaceListingId, Integer itemInventoryId) {
        static OrderItemListingLink unresolved() {
            return new OrderItemListingLink(null, null);
        }
    }
}
