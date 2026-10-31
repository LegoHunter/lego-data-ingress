package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Shipping;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipstation.api.rest.client.ShipStationRestClient;
import com.shipstation.api.rest.model.OrdersList;
import com.shipstation.api.rest.model.Shipment;
import com.shipstation.api.rest.model.ShipmentsList;
import com.shipstation.api.rest.model.ShipStationOrder;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceOrderDao;
import io.legohunter.data.dao.MarketplaceOrderItemDao;
import io.legohunter.data.dao.MarketplaceOrderPayloadDao;
import io.legohunter.data.dto.Carrier;
import io.legohunter.data.dto.MarketplaceOrder;
import io.legohunter.data.dto.MarketplaceOrderItem;
import io.legohunter.data.dto.MarketplaceOrderPayload;
import io.legohunter.ingress.config.environment.LegoEnvironmentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.fulfillment.sync.scheduled", name = "enabled", havingValue = "true")
public class FulfillmentSyncService {
    static final String ORDER_RESPONSE_PAYLOAD = "ORDER_RESPONSE";
    static final String ORDER_ITEMS_RESPONSE_PAYLOAD = "ORDER_ITEMS_RESPONSE";
    private static final String DOMESTIC_TRACKING_URL = "https://tools.usps.com/go/TrackConfirmAction.action?tLabels=%s";
    private static final String INTERNATIONAL_TRACKING_URL = "http://parcelsapp.com/en/tracking/%s";
    private static final boolean SEND_DRIVE_THRU_COPY_TO_ME = true;
    private static final int UNKNOWN_DRIVE_THRU_RECENCY_DAYS = 10;
    private static final String SOLD = "SOLD";
    static final String SKIPPED_DRY_RUN = "SKIPPED_DRY_RUN";
    static final String SKIPPED_NON_PRODUCTION = "SKIPPED_NON_PRODUCTION";

    private static final TypeReference<List<OrderItem>> ORDER_ITEMS_TYPE = new TypeReference<>() {
    };

    private final ShipStationRestClient shipStationRestClient;
    private final BricklinkRestClient bricklinkRestClient;
    private final MarketplaceOrderDao marketplaceOrderDao;
    private final MarketplaceOrderItemDao marketplaceOrderItemDao;
    private final MarketplaceOrderPayloadDao marketplaceOrderPayloadDao;
    private final ItemInventoryDao itemInventoryDao;
    private final CanonicalShipmentReconciliationService canonicalShipmentReconciliationService;
    private final BricklinkShipStationOrderMapper shipStationOrderMapper;
    private final FulfillmentOrderItemImageResolver orderItemImageResolver;
    private final FulfillmentSyncMetricsService metricsService;
    private final FulfillmentSyncProperties properties;
    private final LegoEnvironmentProperties environmentProperties;
    private final ObjectMapper objectMapper;

    public FulfillmentSyncResult runOnce() {
        long startedAt = System.currentTimeMillis();
        FulfillmentSyncProperties.Scheduled scheduled = properties.getSync().getScheduled();
        int batchSize = scheduled.effectiveBatchSize();
        boolean applyRequested = scheduled.isApply();
        boolean production = environmentProperties.isProduction();
        // Orders are real buyer orders on the single shared BrickLink/ShipStation accounts, so
        // only PROD may write to them. Any other environment is forced to dry-run mapping.
        boolean apply = applyRequested && production;
        String skipReason = applyRequested ? SKIPPED_NON_PRODUCTION : SKIPPED_DRY_RUN;
        String marketplaceCode = properties.effectiveMarketplaceCode();
        List<String> statuses = properties.effectiveStatuses();

        if (applyRequested && !production) {
            log.warn(
                    "fulfillment.sync_job.apply_suppressed provider={} reason=non_production_environment applyRequested=true production=false",
                    properties.effectiveMetricsTag()
            );
        }
        log.info(
                "fulfillment.sync_job.started provider={} marketplaceCode={} statuses={} batchSize={} apply={} applyRequested={} production={}",
                properties.effectiveMetricsTag(),
                marketplaceCode,
                statuses,
                batchSize,
                apply,
                applyRequested,
                production
        );

        Set<MarketplaceOrder> candidates = marketplaceOrderDao.findFulfillmentCandidates(
                marketplaceCode,
                statuses,
                batchSize
        );
        if (candidates.isEmpty()) {
            long elapsedMillis = System.currentTimeMillis() - startedAt;
            metricsService.recordRun(properties.effectiveMetricsTag(), "no_work", apply, elapsedMillis);
            log.info(
                    "fulfillment.sync_job.no_work provider={} marketplaceCode={} elapsedMillis={}",
                    properties.effectiveMetricsTag(),
                    marketplaceCode,
                    elapsedMillis
            );
            return result("NO_WORK", 0, 0, 0, 0, new FulfillmentCounters(), elapsedMillis, apply, List.of(), List.of());
        }

        int ordersLoaded = 0;
        int payloadsMissing = 0;
        int ordersMapped = 0;
        int ordersFailed = 0;
        FulfillmentCounters counters = new FulfillmentCounters();
        List<String> mappedOrderNumbers = new ArrayList<>();
        List<String> failedOrderIds = new ArrayList<>();

        for (MarketplaceOrder candidate : candidates) {
            try {
                Optional<CanonicalShipmentReconciliationService.CanonicalFulfillmentOrder> canonicalOrder =
                        canonicalShipmentReconciliationService.findInvoicedOrder(candidate);
                if (canonicalOrder.isEmpty()) {
                    counters.add(FulfillmentOrderAction.skipped("SKIPPED_NOT_INVOICED", null, false));
                    log.info(
                            "fulfillment.sync_job.order_skipped provider={} marketplaceOrderId={} externalOrderId={} reason=not_invoiced_or_not_projected",
                            properties.effectiveMetricsTag(),
                            candidate.getMarketplaceOrderId(),
                            candidate.getExternalOrderId()
                    );
                    continue;
                }
                Optional<LoadedBricklinkOrder> loadedOrder = loadOrder(candidate);
                if (loadedOrder.isEmpty()) {
                    payloadsMissing++;
                    continue;
                }

                ordersLoaded++;
                ShipStationOrder shipStationOrder = shipStationOrderMapper.map(
                        loadedOrder.get().order(),
                        loadedOrder.get().orderItems(),
                        properties.getShipstation(),
                        orderItemImageResolver.resolveImageUrls(candidate)
                );
                ordersMapped++;
                mappedOrderNumbers.add(shipStationOrder.getOrderNumber());
                FulfillmentOrderAction action = fulfillOrder(
                        candidate,
                        canonicalOrder.get(),
                        loadedOrder.get(),
                        shipStationOrder,
                        apply,
                        skipReason
                );
                counters.add(action);
                log.info(
                        "fulfillment.sync_job.order_mapped provider={} marketplaceOrderId={} transactionId={} externalOrderId={} orderNumber={} orderStatus={} itemCount={} action={} shipStationOrderId={} trackingPresent={} apply={}",
                        properties.effectiveMetricsTag(),
                        candidate.getMarketplaceOrderId(),
                        canonicalOrder.get().transaction().getTransactionId(),
                        candidate.getExternalOrderId(),
                        shipStationOrder.getOrderNumber(),
                        shipStationOrder.getOrderStatus(),
                        shipStationOrder.getItems().length,
                        action.action(),
                        action.shipStationOrderId(),
                        action.trackingPresent(),
                        apply
                );
            } catch (RuntimeException e) {
                ordersFailed++;
                counters.ordersFailed++;
                failedOrderIds.add(candidate.getExternalOrderId());
                log.warn(
                        "fulfillment.sync_job.order_failed provider={} marketplaceOrderId={} externalOrderId={} message={}",
                        properties.effectiveMetricsTag(),
                        candidate.getMarketplaceOrderId(),
                        candidate.getExternalOrderId(),
                        e.getMessage(),
                        e
                );
            }
        }

        long elapsedMillis = System.currentTimeMillis() - startedAt;
        String outcome = outcome(ordersMapped, ordersFailed, payloadsMissing);
        metricsService.recordRun(properties.effectiveMetricsTag(), outcome.toLowerCase(), apply, elapsedMillis);
        metricsService.recordOrders(properties.effectiveMetricsTag(), "discovered", candidates.size());
        metricsService.recordOrders(properties.effectiveMetricsTag(), "loaded", ordersLoaded);
        metricsService.recordOrders(properties.effectiveMetricsTag(), "payload_missing", payloadsMissing);
        metricsService.recordOrders(properties.effectiveMetricsTag(), "mapped", ordersMapped);
        metricsService.recordOrders(properties.effectiveMetricsTag(), "created", counters.ordersCreated);
        metricsService.recordOrders(properties.effectiveMetricsTag(), "updated", counters.ordersUpdated);
        metricsService.recordOrders(properties.effectiveMetricsTag(), "shipped_reconciled", counters.ordersShippedReconciled);
        metricsService.recordOrders(properties.effectiveMetricsTag(), "skipped", counters.ordersSkipped);
        metricsService.recordOrders(properties.effectiveMetricsTag(), "failed", ordersFailed);
        log.info(
                "fulfillment.sync_job.completed provider={} marketplaceCode={} outcome={} ordersDiscovered={} ordersLoaded={} payloadsMissing={} ordersMapped={} ordersCreated={} ordersUpdated={} ordersShippedReconciled={} ordersSkipped={} ordersFailed={} elapsedMillis={} apply={}",
                properties.effectiveMetricsTag(),
                marketplaceCode,
                outcome,
                candidates.size(),
                ordersLoaded,
                payloadsMissing,
                ordersMapped,
                counters.ordersCreated,
                counters.ordersUpdated,
                counters.ordersShippedReconciled,
                counters.ordersSkipped,
                ordersFailed,
                elapsedMillis,
                apply
        );
        return result(
                outcome,
                candidates.size(),
                ordersLoaded,
                payloadsMissing,
                ordersMapped,
                counters,
                elapsedMillis,
                apply,
                mappedOrderNumbers,
                failedOrderIds
        );
    }

    private FulfillmentOrderAction fulfillOrder(
            MarketplaceOrder marketplaceOrder,
            CanonicalShipmentReconciliationService.CanonicalFulfillmentOrder canonicalOrder,
            LoadedBricklinkOrder loadedOrder,
            ShipStationOrder desiredOrder,
            boolean apply,
            String skipReason
    ) {
        // Every external write below (ShipStation create/update, shipment persistence, BrickLink
        // order update/status, Drive Thru email, local SOLD marking) is reachable only when apply.
        if (!apply) {
            return FulfillmentOrderAction.skipped(skipReason, null, false);
        }

        Optional<ShipStationOrder> existingOrder = findExistingShipStationOrder(desiredOrder.getOrderNumber());
        if (existingOrder.isPresent() && existingOrder.get().isShipped()) {
            TrackedShipment trackedShipment = findTrackedShipment(existingOrder.get());
            CanonicalShipmentReconciliationService.CanonicalShipment canonicalShipment =
                    canonicalShipmentReconciliationService.persistTrackedShipment(
                            canonicalOrder,
                            trackedShipment.shipment(),
                            existingOrder.get()
                    );
            TrackingDetails tracking = new TrackingDetails(
                    canonicalShipment.shipment().getShipmentTrackingNumber(),
                    trackingUrl(canonicalShipment.carrier(), existingOrder.get(), trackedShipment.shipment().getTrackingNumber()),
                    trackedShipment.dateShipped()
            );
            reconcileShippedOrder(marketplaceOrder, loadedOrder.order(), tracking);
            return FulfillmentOrderAction.shippedReconciled(existingOrder.get().getOrderId(), true);
        }

        existingOrder.map(ShipStationOrder::getOrderId).ifPresent(desiredOrder::setOrderId);
        ShipStationOrder savedOrder = shipStationRestClient.createOrUpdateOrder(desiredOrder);
        Long savedOrderId = savedOrder == null ? null : savedOrder.getOrderId();
        if (existingOrder.isPresent()) {
            return FulfillmentOrderAction.updated(savedOrderId);
        }
        return FulfillmentOrderAction.created(savedOrderId);
    }

    private Optional<ShipStationOrder> findExistingShipStationOrder(String orderNumber) {
        OrdersList ordersList = shipStationRestClient.getOrders(Map.of("orderNumber", orderNumber));
        List<ShipStationOrder> orders = ordersList == null || ordersList.getOrders() == null ? List.of() : ordersList.getOrders();
        if (orders.isEmpty()) {
            return Optional.empty();
        }
        if (orders.size() > 1) {
            throw new IllegalStateException("Found %d ShipStation orders for order number [%s]".formatted(orders.size(), orderNumber));
        }
        return Optional.of(orders.getFirst());
    }

    private TrackedShipment findTrackedShipment(ShipStationOrder shipStationOrder) {
        Long orderId = shipStationOrder.getOrderId();
        if (orderId == null) {
            throw new IllegalStateException("Cannot fetch tracking for shipped ShipStation order without orderId [%s]".formatted(shipStationOrder.getOrderNumber()));
        }

        ShipmentsList shipmentsList = shipStationRestClient.getShipments(Map.of("orderId", orderId));
        List<Shipment> shipments = shipmentsList == null || shipmentsList.getShipments() == null ? List.of() : shipmentsList.getShipments();
        return shipments.stream()
                .filter(shipment -> !Boolean.TRUE.equals(shipment.getVoided()))
                .filter(shipment -> present(shipment.getTrackingNumber()))
                .filter(shipment -> shipment.getShipmentId() != null)
                .map(shipment -> new TrackedShipment(shipment, shippedAt(shipment)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unable to find non-voided tracked ShipStation shipment with an id for orderId [%s]".formatted(orderId)));
    }

    private void reconcileShippedOrder(MarketplaceOrder marketplaceOrder, Order order, TrackingDetails tracking) {
        if (trackingMatches(order, tracking) && isShipped(order)) {
            markMarketplaceOrderShipped(marketplaceOrder);
            markLinkedInventorySold(marketplaceOrder, tracking.dateShipped());
            return;
        }

        Shipping shipping = order.getShipping() == null ? new Shipping() : order.getShipping();
        shipping.setTracking_no(tracking.trackingNumber());
        shipping.setTracking_link(tracking.trackingUrl());
        shipping.setDate_shipped(tracking.dateShipped());

        Order orderUpdate = new Order();
        orderUpdate.setShipping(shipping);
        orderUpdate.setCost(order.getCost());
        orderUpdate.setRemarks(null);
        orderUpdate.setIs_filed(false);

        bricklinkRestClient.updateOrder(order.getOrder_id(), orderUpdate);
        bricklinkRestClient.updateOrderStatus(order.getOrder_id(), com.bricklink.api.rest.model.v1.OrderStatus.SHIPPED);
        Order updatedOrder = data(bricklinkRestClient.getOrder(order.getOrder_id()));
        if (shouldSendDriveThru(updatedOrder, tracking)) {
            bricklinkRestClient.sendDriveThru(order.getOrder_id(), SEND_DRIVE_THRU_COPY_TO_ME);
        }
        markMarketplaceOrderShipped(marketplaceOrder);
        markLinkedInventorySold(marketplaceOrder, tracking.dateShipped());
    }

    private boolean shouldSendDriveThru(Order updatedOrder, TrackingDetails tracking) {
        if (updatedOrder == null) {
            log.info("fulfillment.sync_job.drive_thru_skipped reason=order_unknown");
            return false;
        }
        Boolean sentDriveThru = updatedOrder.getSent_drive_thru();
        if (Boolean.TRUE.equals(sentDriveThru)) {
            return false;
        }
        if (Boolean.FALSE.equals(sentDriveThru)) {
            return true;
        }

        boolean recentShipment = tracking.dateShipped() != null
                && ZonedDateTime.now(ZoneOffset.UTC).isBefore(tracking.dateShipped().plusDays(UNKNOWN_DRIVE_THRU_RECENCY_DAYS));
        if (isShipped(updatedOrder) && recentShipment) {
            return true;
        }
        log.info(
                "fulfillment.sync_job.drive_thru_skipped reason=sent_drive_thru_unknown orderId={} shipped={} dateShipped={} recencyDays={}",
                updatedOrder.getOrder_id(),
                isShipped(updatedOrder),
                tracking.dateShipped(),
                UNKNOWN_DRIVE_THRU_RECENCY_DAYS
        );
        return false;
    }

    private void markMarketplaceOrderShipped(MarketplaceOrder marketplaceOrder) {
        marketplaceOrder.setExternalStatusCode("SHIPPED");
        marketplaceOrder.setTrackingPresent(true);
        marketplaceOrder.setStatusChangedAt(ZonedDateTime.now(ZoneOffset.UTC));
        marketplaceOrder.setLastSeenAt(ZonedDateTime.now(ZoneOffset.UTC));
        marketplaceOrderDao.update(marketplaceOrder);
    }

    private void markLinkedInventorySold(MarketplaceOrder marketplaceOrder, ZonedDateTime soldAt) {
        ZonedDateTime stateChangedAt = soldAt == null ? ZonedDateTime.now(ZoneOffset.UTC) : soldAt;
        marketplaceOrderItemDao.findByMarketplaceOrderId(marketplaceOrder.getMarketplaceOrderId()).stream()
                .map(MarketplaceOrderItem::getItemInventoryId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(itemInventoryId -> {
                    itemInventoryDao.updateInventoryState(itemInventoryId, SOLD, stateChangedAt);
                    log.info(
                            "fulfillment.sync_job.inventory_sold provider={} marketplaceOrderId={} externalOrderId={} itemInventoryId={} stateChangedAt={}",
                            properties.effectiveMetricsTag(),
                            marketplaceOrder.getMarketplaceOrderId(),
                            marketplaceOrder.getExternalOrderId(),
                            itemInventoryId,
                            stateChangedAt
                    );
                });
    }

    private boolean trackingMatches(Order order, TrackingDetails tracking) {
        Shipping shipping = order.getShipping();
        return shipping != null && tracking.trackingNumber().equals(shipping.getTracking_no());
    }

    private boolean isShipped(Order order) {
        try {
            return order.getStatus() != null && order.isShipped();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String trackingUrl(Carrier carrier, ShipStationOrder shipStationOrder, String trackingNumber) {
        String pattern = carrier == null || !present(carrier.getTrackingUrlPattern())
                ? (isDomestic(shipStationOrder) ? DOMESTIC_TRACKING_URL : INTERNATIONAL_TRACKING_URL)
                : carrier.getTrackingUrlPattern();
        try {
            return URI.create(pattern.formatted(trackingNumber)).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unable to build tracking URL for tracking number [%s]".formatted(trackingNumber), e);
        }
    }

    private boolean isDomestic(ShipStationOrder shipStationOrder) {
        String countryCode = shipStationOrder == null || shipStationOrder.getShipTo() == null
                ? null
                : shipStationOrder.getShipTo().getCountry();
        return countryCode != null && countryCode.equalsIgnoreCase(properties.getShipstation().effectiveDomesticCountryCode());
    }

    private ZonedDateTime shippedAt(Shipment shipment) {
        OffsetDateTime shipDate = shipment.getShipDate() == null ? shipment.getCreateDate() : shipment.getShipDate();
        return shipDate == null ? ZonedDateTime.now(ZoneOffset.UTC) : shipDate.toZonedDateTime();
    }

    private Optional<LoadedBricklinkOrder> loadOrder(MarketplaceOrder marketplaceOrder) {
        Integer marketplaceOrderId = marketplaceOrder.getMarketplaceOrderId();
        Optional<MarketplaceOrderPayload> orderPayload =
                marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                        marketplaceOrderId,
                        ORDER_RESPONSE_PAYLOAD
                );
        Optional<MarketplaceOrderPayload> orderItemsPayload =
                marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                        marketplaceOrderId,
                        ORDER_ITEMS_RESPONSE_PAYLOAD
                );

        if (orderPayload.isEmpty() || orderItemsPayload.isEmpty()) {
            log.info(
                    "fulfillment.sync_job.payload_missing provider={} marketplaceOrderId={} externalOrderId={} hasOrderPayload={} hasOrderItemsPayload={}",
                    properties.effectiveMetricsTag(),
                    marketplaceOrderId,
                    marketplaceOrder.getExternalOrderId(),
                    orderPayload.isPresent(),
                    orderItemsPayload.isPresent()
            );
            return Optional.empty();
        }

        return Optional.of(new LoadedBricklinkOrder(
                readOrder(orderPayload.get()),
                readOrderItems(orderItemsPayload.get())
        ));
    }

    private Order readOrder(MarketplaceOrderPayload payload) {
        try {
            return objectMapper.readValue(payload.getPayloadJson(), Order.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to decode BrickLink order payload " + payload.getMarketplaceOrderPayloadId(), e);
        }
    }

    private List<OrderItem> readOrderItems(MarketplaceOrderPayload payload) {
        try {
            return objectMapper.readValue(payload.getPayloadJson(), ORDER_ITEMS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to decode BrickLink order items payload " + payload.getMarketplaceOrderPayloadId(), e);
        }
    }

    private String outcome(int ordersMapped, int ordersFailed, int payloadsMissing) {
        if (ordersFailed == 0) {
            if (ordersMapped == 0 && payloadsMissing > 0) {
                return "PAYLOADS_MISSING";
            }
            return "SUCCESS";
        }
        if (ordersMapped - ordersFailed <= 0) {
            return "FAILED";
        }
        return "PARTIAL_FAILURE";
    }

    private FulfillmentSyncResult result(
            String outcome,
            int ordersDiscovered,
            int ordersLoaded,
            int payloadsMissing,
            int ordersMapped,
            FulfillmentCounters counters,
            long elapsedMillis,
            boolean apply,
            List<String> mappedOrderNumbers,
            List<String> failedOrderIds
    ) {
        return new FulfillmentSyncResult(
                outcome,
                ordersDiscovered,
                ordersLoaded,
                payloadsMissing,
                ordersMapped,
                counters.ordersCreated,
                counters.ordersUpdated,
                counters.ordersShippedReconciled,
                counters.ordersSkipped,
                counters.ordersFailed,
                elapsedMillis,
                apply,
                mappedOrderNumbers,
                failedOrderIds
        );
    }

    private <T> T data(com.bricklink.api.rest.model.v1.BricklinkResource<T> resource) {
        return resource == null ? null : resource.getData();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private record LoadedBricklinkOrder(Order order, List<OrderItem> orderItems) {
    }

    private record TrackedShipment(Shipment shipment, ZonedDateTime dateShipped) {
    }

    private record TrackingDetails(String trackingNumber, String trackingUrl, ZonedDateTime dateShipped) {
    }

    private record FulfillmentOrderAction(String action, Long shipStationOrderId, boolean trackingPresent) {
        static FulfillmentOrderAction created(Long shipStationOrderId) {
            return new FulfillmentOrderAction("CREATED", shipStationOrderId, false);
        }

        static FulfillmentOrderAction updated(Long shipStationOrderId) {
            return new FulfillmentOrderAction("UPDATED", shipStationOrderId, false);
        }

        static FulfillmentOrderAction shippedReconciled(Long shipStationOrderId, boolean trackingPresent) {
            return new FulfillmentOrderAction("SHIPPED_RECONCILED", shipStationOrderId, trackingPresent);
        }

        static FulfillmentOrderAction skipped(String reason, Long shipStationOrderId, boolean trackingPresent) {
            return new FulfillmentOrderAction(reason, shipStationOrderId, trackingPresent);
        }
    }

    private static class FulfillmentCounters {
        private int ordersCreated;
        private int ordersUpdated;
        private int ordersShippedReconciled;
        private int ordersSkipped;
        private int ordersFailed;

        void add(FulfillmentOrderAction action) {
            switch (action.action()) {
                case "CREATED" -> ordersCreated++;
                case "UPDATED" -> ordersUpdated++;
                case "SHIPPED_RECONCILED" -> ordersShippedReconciled++;
                default -> ordersSkipped++;
            }
        }
    }
}
