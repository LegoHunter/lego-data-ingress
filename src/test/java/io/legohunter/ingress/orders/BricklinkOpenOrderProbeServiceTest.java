package io.legohunter.ingress.orders;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Cost;
import com.bricklink.api.rest.model.v1.Item;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Payment;
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
import io.legohunter.data.dto.MarketplaceOrderPayload;
import io.legohunter.data.dto.MarketplaceOrderSyncRun;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BricklinkOpenOrderProbeServiceTest {
    @Mock
    private BricklinkRestClient bricklinkRestClient;
    @Mock
    private MarketplaceOrderSyncRunDao marketplaceOrderSyncRunDao;
    @Mock
    private MarketplaceOrderDao marketplaceOrderDao;
    @Mock
    private MarketplaceOrderItemDao marketplaceOrderItemDao;
    @Mock
    private MarketplaceOrderPayloadDao marketplaceOrderPayloadDao;
    @Mock
    private BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao;
    @Mock
    private MarketplaceListingDao marketplaceListingDao;
    @Mock
    private ItemInventoryDao itemInventoryDao;
    @Mock
    private BricklinkOrderProjectionService projectionService;

    private BricklinkOrderSyncProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private BricklinkOpenOrderProbeService probeService;

    @BeforeEach
    void setUp() {
        properties = new BricklinkOrderSyncProperties();
        meterRegistry = new SimpleMeterRegistry();
        probeService = new BricklinkOpenOrderProbeService(
                bricklinkRestClient,
                properties,
                new BricklinkOrderSyncMetricsService(meterRegistry),
                marketplaceOrderSyncRunDao,
                marketplaceOrderDao,
                marketplaceOrderItemDao,
                marketplaceOrderPayloadDao,
                bricklinkMarketplaceListingDao,
                marketplaceListingDao,
                itemInventoryDao,
                projectionService,
                new ObjectMapper().findAndRegisterModules()
        );
        lenient().when(projectionService.project(any(MarketplaceOrder.class), any(Set.class), any(Order.class)))
                .thenReturn(new BricklinkOrderProjectionResult(1L, false, 1, 0));
    }

    @Test
    void runOnceRecordsNoWorkAndQueriesConfiguredStatusesPlusUnfiledCancelled() {
        when(bricklinkRestClient.getOrders(anyMap(), any()))
                .thenReturn(resource(List.of()));

        BricklinkOrderProbeResult result = probeService.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        assertThat(result.ordersDiscovered()).isZero();
        assertThat(result.ordersFetched()).isZero();
        assertThat(result.ordersFailed()).isZero();
        assertThat(result.orderItemsFetched()).isZero();
        assertThat(result.applied()).isFalse();
        assertThat(result.ordersWritten()).isZero();
        verifyNoInteractions(marketplaceOrderSyncRunDao, marketplaceOrderDao, marketplaceOrderItemDao, marketplaceOrderPayloadDao, itemInventoryDao);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Iterable> statusCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(bricklinkRestClient, org.mockito.Mockito.times(7))
                .getOrders(paramsCaptor.capture(), statusCaptor.capture());

        List<String> queriedStatuses = statusCaptor.getAllValues().stream()
                .flatMap(statuses -> {
                    List<String> values = new ArrayList<>();
                    statuses.forEach(status -> values.add(String.valueOf(status)));
                    return values.stream();
                })
                .toList();
        assertThat(queriedStatuses).containsExactly(
                "PENDING",
                "UPDATED",
                "READY",
                "PROCESSING",
                "PAID",
                "PACKED",
                "CANCELLED"
        );
        assertThat(paramsCaptor.getAllValues().getLast())
                .containsEntry("direction", "in")
                .containsEntry("filed", false);
        assertThat(meterRegistry.counter(
                "bricklink_order_sync",
                "provider", "bricklink",
                "outcome", "no_work"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void runOnceFetchesOrderDetailsAndFlattensOrderItemBatches() {
        properties.setStatuses(List.of("pending"));
        properties.setIncludeUnfiledCancelled(false);
        Order summary = order("100", "PENDING");
        Order detail = order("100", "PAID");
        detail.setTotal_count(3);
        detail.setUnique_count(2);
        detail.setCost(cost("USD", 30.0, 38.0, 8.0));

        when(bricklinkRestClient.getOrders(anyMap(), any()))
                .thenReturn(resource(List.of(summary)));
        when(bricklinkRestClient.getOrder("100"))
                .thenReturn(resource(detail));
        when(bricklinkRestClient.getOrderItems("100"))
                .thenReturn(resource(List.of(
                        List.of(orderItem("3001"), orderItem("3002")),
                        List.of(orderItem("3003"))
                )));

        BricklinkOrderProbeResult result = probeService.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.ordersDiscovered()).isEqualTo(1);
        assertThat(result.ordersFetched()).isEqualTo(1);
        assertThat(result.ordersFailed()).isZero();
        assertThat(result.orderItemsFetched()).isEqualTo(3);
        assertThat(result.applied()).isFalse();
        verifyNoInteractions(marketplaceOrderSyncRunDao, marketplaceOrderDao, marketplaceOrderItemDao, marketplaceOrderPayloadDao, itemInventoryDao);
        assertThat(meterRegistry.counter(
                "bricklink_order_sync_order",
                "provider", "bricklink",
                "result", "discovered"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "bricklink_order_sync_order",
                "provider", "bricklink",
                "result", "fetched"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void runOncePersistsOrdersItemsPayloadsAndSyncRunWhenApplyIsEnabled() {
        properties.getScheduled().setApply(true);
        properties.setStatuses(List.of("PENDING"));
        properties.setIncludeUnfiledCancelled(false);
        Order summary = order("100", "PENDING");
        Order detail = order("100", "PAID");
        detail.setBuyer_name("buyer-one");
        detail.setBuyer_email("buyer@example.com");
        detail.setTotal_count(2);
        detail.setUnique_count(2);
        detail.setTotal_weight(12.5);
        detail.setCost(cost("USD", 30.0, 38.0, 8.0));
        detail.setPayment(payment("Received"));
        io.legohunter.data.dto.MarketplaceOrderItem existingItem = marketplaceOrderItem(10, 20, "100:inventory:3001");
        io.legohunter.data.dto.MarketplaceOrderItem staleItem = marketplaceOrderItem(11, 20, "100:inventory:stale");

        when(marketplaceOrderSyncRunDao.insert(any(MarketplaceOrderSyncRun.class)))
                .thenAnswer(invocation -> {
                    MarketplaceOrderSyncRun syncRun = invocation.getArgument(0);
                    syncRun.setMarketplaceOrderSyncRunId(10);
                    return syncRun;
                });
        when(bricklinkRestClient.getOrders(anyMap(), any()))
                .thenReturn(resource(List.of(summary)));
        when(bricklinkRestClient.getOrder("100"))
                .thenReturn(resource(detail));
        when(bricklinkRestClient.getOrderItems("100"))
                .thenReturn(resource(List.of(List.of(orderItem("3001"), orderItem("3002")))));
        when(marketplaceOrderDao.findByMarketplaceCodeAndExternalOrderId("BRICKLINK", "100"))
                .thenReturn(Optional.of(MarketplaceOrder.builder()
                        .marketplaceOrderId(20)
                        .marketplaceCode("BRICKLINK")
                        .externalOrderId("100")
                        .build()));
        when(marketplaceOrderItemDao.findByMarketplaceOrderId(20))
                .thenReturn(Set.of(existingItem, staleItem));
        when(bricklinkMarketplaceListingDao.findByBricklinkInventoryId(3001))
                .thenReturn(Optional.of(BricklinkMarketplaceListing.builder()
                        .marketplaceListingId(1001)
                        .bricklinkInventoryId(3001)
                        .build()));
        when(marketplaceListingDao.findByMarketplaceListingId(1001))
                .thenReturn(Optional.of(MarketplaceListing.builder()
                        .marketplaceListingId(1001)
                        .itemInventoryId(501)
                        .build()));
        when(bricklinkMarketplaceListingDao.findByBricklinkInventoryId(3002))
                .thenReturn(Optional.empty());

        BricklinkOrderProbeResult result = probeService.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.applied()).isTrue();
        assertThat(result.ordersWritten()).isEqualTo(1);
        assertThat(result.orderItemsWritten()).isEqualTo(2);
        assertThat(result.payloadsWritten()).isEqualTo(2);

        ArgumentCaptor<MarketplaceOrder> orderCaptor = ArgumentCaptor.forClass(MarketplaceOrder.class);
        verify(marketplaceOrderDao).upsert(orderCaptor.capture());
        assertThat(orderCaptor.getValue())
                .extracting(
                        MarketplaceOrder::getMarketplaceCode,
                        MarketplaceOrder::getExternalOrderId,
                        MarketplaceOrder::getExternalStatusCode,
                        MarketplaceOrder::getBuyerDisplayName,
                        MarketplaceOrder::getBuyerEmail,
                        MarketplaceOrder::getPaymentStatusCode,
                        MarketplaceOrder::getCurrencyCode
                )
                .containsExactly("BRICKLINK", "100", "PAID", "buyer-one", "buyer@example.com", "Received", "USD");
        assertThat(orderCaptor.getValue().getPayloadHash()).hasSize(64);

        ArgumentCaptor<io.legohunter.data.dto.MarketplaceOrderItem> itemCaptor =
                ArgumentCaptor.forClass(io.legohunter.data.dto.MarketplaceOrderItem.class);
        verify(marketplaceOrderItemDao).update(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getMarketplaceOrderItemId()).isEqualTo(10);
        assertThat(itemCaptor.getValue().getExternalInventoryId()).isEqualTo("3001");
        assertThat(itemCaptor.getValue().getMarketplaceListingId()).isEqualTo(1001);
        assertThat(itemCaptor.getValue().getItemInventoryId()).isEqualTo(501);

        verify(marketplaceOrderItemDao).insert(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues().getLast().getExternalInventoryId()).isEqualTo("3002");
        assertThat(itemCaptor.getAllValues().getLast().getMarketplaceListingId()).isNull();
        assertThat(itemCaptor.getAllValues().getLast().getItemInventoryId()).isNull();
        verify(marketplaceOrderItemDao).delete(11);
        verify(itemInventoryDao).updateInventoryState(eq(501), eq("RESERVED_FOR_ORDER"), any());

        ArgumentCaptor<MarketplaceOrderPayload> payloadCaptor = ArgumentCaptor.forClass(MarketplaceOrderPayload.class);
        verify(marketplaceOrderPayloadDao, org.mockito.Mockito.times(2)).insert(payloadCaptor.capture());
        assertThat(payloadCaptor.getAllValues())
                .extracting(MarketplaceOrderPayload::getPayloadTypeCode)
                .containsExactly("ORDER_RESPONSE", "ORDER_ITEMS_RESPONSE");

        ArgumentCaptor<MarketplaceOrderSyncRun> syncRunCaptor = ArgumentCaptor.forClass(MarketplaceOrderSyncRun.class);
        verify(marketplaceOrderSyncRunDao, org.mockito.Mockito.atLeastOnce()).update(syncRunCaptor.capture());
        assertThat(syncRunCaptor.getAllValues().getLast().getSyncStatusCode()).isEqualTo("SUCCESS");
        assertThat(syncRunCaptor.getAllValues().getLast().getOrdersFetched()).isEqualTo(1);
        assertThat(syncRunCaptor.getAllValues().getLast().getOrdersFailed()).isZero();
    }

    @Test
    void runOnceDoesNotReserveInventoryForCancelledOrders() {
        properties.getScheduled().setApply(true);
        properties.setStatuses(List.of("CANCELLED"));
        properties.setIncludeUnfiledCancelled(false);
        Order summary = order("100", "CANCELLED");
        Order detail = order("100", "CANCELLED");
        io.legohunter.data.dto.MarketplaceOrderItem existingItem = marketplaceOrderItem(10, 20, "100:inventory:3001");

        when(marketplaceOrderSyncRunDao.insert(any(MarketplaceOrderSyncRun.class)))
                .thenAnswer(invocation -> {
                    MarketplaceOrderSyncRun syncRun = invocation.getArgument(0);
                    syncRun.setMarketplaceOrderSyncRunId(10);
                    return syncRun;
                });
        when(bricklinkRestClient.getOrders(anyMap(), any()))
                .thenReturn(resource(List.of(summary)));
        when(bricklinkRestClient.getOrder("100"))
                .thenReturn(resource(detail));
        when(bricklinkRestClient.getOrderItems("100"))
                .thenReturn(resource(List.of(List.of(orderItem("3001")))));
        when(marketplaceOrderDao.findByMarketplaceCodeAndExternalOrderId("BRICKLINK", "100"))
                .thenReturn(Optional.of(MarketplaceOrder.builder()
                        .marketplaceOrderId(20)
                        .marketplaceCode("BRICKLINK")
                        .externalOrderId("100")
                        .build()));
        when(marketplaceOrderItemDao.findByMarketplaceOrderId(20))
                .thenReturn(Set.of(existingItem));
        when(bricklinkMarketplaceListingDao.findByBricklinkInventoryId(3001))
                .thenReturn(Optional.of(BricklinkMarketplaceListing.builder()
                        .marketplaceListingId(1001)
                        .bricklinkInventoryId(3001)
                        .build()));
        when(marketplaceListingDao.findByMarketplaceListingId(1001))
                .thenReturn(Optional.of(MarketplaceListing.builder()
                        .marketplaceListingId(1001)
                        .itemInventoryId(501)
                        .build()));

        BricklinkOrderProbeResult result = probeService.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.applied()).isTrue();
        assertThat(result.orderItemsWritten()).isEqualTo(1);
        verify(marketplaceOrderItemDao).update(any(io.legohunter.data.dto.MarketplaceOrderItem.class));
        verify(itemInventoryDao, never()).updateInventoryState(any(), any(), any());
    }

    @Test
    void runOnceContinuesWhenOneOrderFails() {
        properties.setStatuses(List.of("PENDING"));
        properties.setIncludeUnfiledCancelled(false);
        Order first = order("100", "PENDING");
        Order second = order("200", "PENDING");

        when(bricklinkRestClient.getOrders(anyMap(), any()))
                .thenReturn(resource(List.of(first, second)));
        when(bricklinkRestClient.getOrder("100"))
                .thenReturn(resource(first));
        when(bricklinkRestClient.getOrderItems("100"))
                .thenReturn(resource(List.of()));
        when(bricklinkRestClient.getOrder("200"))
                .thenThrow(new IllegalStateException("BrickLink unavailable"));

        BricklinkOrderProbeResult result = probeService.runOnce();

        assertThat(result.outcome()).isEqualTo("PARTIAL_FAILURE");
        assertThat(result.ordersDiscovered()).isEqualTo(2);
        assertThat(result.ordersFetched()).isEqualTo(1);
        assertThat(result.ordersFailed()).isEqualTo(1);
        verifyNoInteractions(marketplaceOrderSyncRunDao, marketplaceOrderDao, marketplaceOrderItemDao, marketplaceOrderPayloadDao, itemInventoryDao);
        assertThat(meterRegistry.counter(
                "bricklink_order_sync",
                "provider", "bricklink",
                "outcome", "partial_failure"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "bricklink_order_sync_order",
                "provider", "bricklink",
                "result", "failed"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void getOpenOrderSummariesDeduplicatesByOrderId() {
        properties.setIncludeUnfiledCancelled(false);
        when(bricklinkRestClient.getOrders(anyMap(), any()))
                .thenReturn(resource(List.of(order("100", "PENDING"), order("100", "PENDING"))));

        List<Order> orders = probeService.getOpenOrderSummaries(List.of("PENDING"));

        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst().getOrder_id()).isEqualTo("100");
    }

    private static Order order(String orderId, String status) {
        Order order = new Order();
        order.setOrder_id(orderId);
        order.setStatus(status);
        order.setDate_ordered(LocalDateTime.parse("2026-06-08T10:00:00"));
        order.setDate_status_changed(LocalDateTime.parse("2026-06-08T11:00:00"));
        return order;
    }

    private static OrderItem orderItem(String inventoryId) {
        Item item = new Item();
        item.setNo("1234-1");
        item.setType("SET");
        item.setName("Test Set");

        OrderItem orderItem = new OrderItem();
        orderItem.setInventory_id(Long.valueOf(inventoryId));
        orderItem.setItem(item);
        orderItem.setQuantity(1);
        orderItem.setNew_or_used("N");
        orderItem.setCompleteness("C");
        orderItem.setUnit_price(5.99);
        orderItem.setUnit_price_final(5.49);
        orderItem.setCurrency_code("USD");
        return orderItem;
    }

    private static io.legohunter.data.dto.MarketplaceOrderItem marketplaceOrderItem(
            Integer marketplaceOrderItemId,
            Integer marketplaceOrderId,
            String externalOrderItemId
    ) {
        return io.legohunter.data.dto.MarketplaceOrderItem.builder()
                .marketplaceOrderItemId(marketplaceOrderItemId)
                .marketplaceOrderId(marketplaceOrderId)
                .externalOrderItemId(externalOrderItemId)
                .externalInventoryId(externalOrderItemId.substring(externalOrderItemId.lastIndexOf(':') + 1))
                .build();
    }

    private static Cost cost(String currencyCode, double subtotal, double grandTotal, double shipping) {
        Cost cost = new Cost();
        cost.setCurrency_code(currencyCode);
        cost.setSubtotal(subtotal);
        cost.setGrand_total(grandTotal);
        cost.setShipping(shipping);
        return cost;
    }

    private static Payment payment(String status) {
        Payment payment = new Payment();
        payment.setMethod("PayPal");
        payment.setCurrency_code("USD");
        payment.setDate_paid(LocalDateTime.parse("2026-06-08T12:00:00"));
        payment.setStatus(status);
        return payment;
    }

    private static <T> BricklinkResource<T> resource(T data) {
        BricklinkResource<T> resource = new BricklinkResource<>();
        resource.setData(data);
        return resource;
    }
}
