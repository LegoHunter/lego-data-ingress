package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.Address;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Cost;
import com.bricklink.api.rest.model.v1.Item;
import com.bricklink.api.rest.model.v1.Name;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Shipping;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipstation.api.rest.client.ShipStationRestClient;
import com.shipstation.api.rest.model.OrdersList;
import com.shipstation.api.rest.model.ShipStationOrder;
import com.shipstation.api.rest.model.Shipment;
import com.shipstation.api.rest.model.ShipmentsList;
import io.legohunter.data.dao.MarketplaceOrderDao;
import io.legohunter.data.dao.MarketplaceOrderItemDao;
import io.legohunter.data.dao.MarketplaceOrderPayloadDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dto.MarketplaceOrder;
import io.legohunter.data.dto.MarketplaceOrderPayload;
import io.legohunter.ingress.config.environment.LegoEnvironmentProperties;
import io.legohunter.data.dto.Carrier;
import io.legohunter.data.dto.Transactions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FulfillmentSyncServiceApplyTest {
    private MarketplaceOrderDao marketplaceOrderDao;
    private MarketplaceOrderItemDao marketplaceOrderItemDao;
    private MarketplaceOrderPayloadDao marketplaceOrderPayloadDao;
    private ItemInventoryDao itemInventoryDao;
    private CanonicalShipmentReconciliationService canonicalShipmentReconciliationService;
    private FulfillmentOrderItemImageResolver orderItemImageResolver;
    private ShipStationRestClient shipStationRestClient;
    private BricklinkRestClient bricklinkRestClient;
    private FulfillmentSyncProperties properties;
    private LegoEnvironmentProperties environmentProperties;
    private ObjectMapper objectMapper;
    private FulfillmentSyncService service;

    @BeforeEach
    void setUp() {
        marketplaceOrderDao = mock(MarketplaceOrderDao.class);
        marketplaceOrderItemDao = mock(MarketplaceOrderItemDao.class);
        marketplaceOrderPayloadDao = mock(MarketplaceOrderPayloadDao.class);
        itemInventoryDao = mock(ItemInventoryDao.class);
        canonicalShipmentReconciliationService = mock(CanonicalShipmentReconciliationService.class);
        orderItemImageResolver = mock(FulfillmentOrderItemImageResolver.class);
        shipStationRestClient = mock(ShipStationRestClient.class);
        bricklinkRestClient = mock(BricklinkRestClient.class);
        properties = new FulfillmentSyncProperties();
        environmentProperties = new LegoEnvironmentProperties();
        // Existing scenarios describe PROD behavior; non-production suppression is covered separately.
        environmentProperties.setProduction(true);
        properties.getSync().getScheduled().setApply(true);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(canonicalShipmentReconciliationService.findInvoicedOrder(any(MarketplaceOrder.class)))
                .thenReturn(Optional.of(canonicalOrder()));
        when(canonicalShipmentReconciliationService.persistTrackedShipment(
                any(CanonicalShipmentReconciliationService.CanonicalFulfillmentOrder.class),
                any(Shipment.class),
                any(ShipStationOrder.class)
        )).thenAnswer(invocation -> canonicalShipment(invocation.getArgument(1)));
        service = new FulfillmentSyncService(
                shipStationRestClient,
                bricklinkRestClient,
                marketplaceOrderDao,
                marketplaceOrderItemDao,
                marketplaceOrderPayloadDao,
                itemInventoryDao,
                canonicalShipmentReconciliationService,
                new BricklinkShipStationOrderMapper(),
                orderItemImageResolver,
                new FulfillmentSyncMetricsService(new SimpleMeterRegistry()),
                properties,
                environmentProperties,
                objectMapper
        );
    }

    @Test
    void runOnceCreatesShipStationOrderWhenOrderDoesNotExist() throws Exception {
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        stagePayloads(marketplaceOrder, order("100", "PAID"), List.of(orderItem(3001L)));
        when(shipStationRestClient.getOrders(Map.of("orderNumber", "BL-100")))
                .thenReturn(OrdersList.builder().orders(List.of()).build());
        when(shipStationRestClient.createOrUpdateOrder(any(ShipStationOrder.class)))
                .thenReturn(ShipStationOrder.builder().orderId(42L).orderNumber("BL-100").build());

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.ordersCreated()).isEqualTo(1);
        assertThat(result.ordersUpdated()).isZero();
        assertThat(result.ordersShippedReconciled()).isZero();
        assertThat(result.ordersSkipped()).isZero();
        assertThat(result.applied()).isTrue();

        ArgumentCaptor<ShipStationOrder> orderCaptor = ArgumentCaptor.forClass(ShipStationOrder.class);
        verify(shipStationRestClient).createOrUpdateOrder(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getOrderNumber()).isEqualTo("BL-100");
        assertThat(orderCaptor.getValue().getOrderId()).isNull();
        verify(bricklinkRestClient, never()).updateOrder(any(), any());
    }

    @Test
    void runOnceUpdatesShipStationOrderWhenExistingOrderIsNotShipped() throws Exception {
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        stagePayloads(marketplaceOrder, order("100", "PAID"), List.of(orderItem(3001L)));
        when(shipStationRestClient.getOrders(Map.of("orderNumber", "BL-100")))
                .thenReturn(OrdersList.builder()
                        .orders(List.of(ShipStationOrder.builder()
                                .orderId(42L)
                                .orderNumber("BL-100")
                                .orderStatus(com.shipstation.api.rest.model.OrderStatus.AWAITING_PAYMENT.label())
                                .build()))
                        .build());
        when(shipStationRestClient.createOrUpdateOrder(any(ShipStationOrder.class)))
                .thenReturn(ShipStationOrder.builder().orderId(42L).orderNumber("BL-100").build());

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.ordersCreated()).isZero();
        assertThat(result.ordersUpdated()).isEqualTo(1);
        assertThat(result.ordersShippedReconciled()).isZero();

        ArgumentCaptor<ShipStationOrder> orderCaptor = ArgumentCaptor.forClass(ShipStationOrder.class);
        verify(shipStationRestClient).createOrUpdateOrder(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getOrderId()).isEqualTo(42L);
    }

    @Test
    void runOnceReconcilesBricklinkWhenShipStationOrderIsAlreadyShipped() throws Exception {
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        Order bricklinkOrder = order("100", "PAID");
        stagePayloads(marketplaceOrder, bricklinkOrder, List.of(orderItem(3001L)));
        when(shipStationRestClient.getOrders(Map.of("orderNumber", "BL-100")))
                .thenReturn(OrdersList.builder()
                        .orders(List.of(ShipStationOrder.builder()
                                .orderId(42L)
                                .orderNumber("BL-100")
                                .orderStatus(com.shipstation.api.rest.model.OrderStatus.SHIPPED.label())
                                .shipTo(com.shipstation.api.rest.model.Address.builder().country("US").build())
                                .build()))
                        .build());
        when(shipStationRestClient.getShipments(Map.of("orderId", 42L)))
                .thenReturn(ShipmentsList.builder()
                        .shipments(List.of(Shipment.builder()
                                .shipmentId(901L)
                                .orderId(42L)
                                .trackingNumber("9400111899223855555555")
                                .shipDate(OffsetDateTime.parse("2026-06-09T14:00:00Z"))
                                .voided(false)
                                .build()))
                        .build());
        Order updatedOrder = order("100", "SHIPPED");
        updatedOrder.setSent_drive_thru(false);
        when(bricklinkRestClient.getOrder("100")).thenReturn(resource(updatedOrder));

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.ordersCreated()).isZero();
        assertThat(result.ordersUpdated()).isZero();
        assertThat(result.ordersShippedReconciled()).isEqualTo(1);
        verify(shipStationRestClient, never()).createOrUpdateOrder(any());

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(bricklinkRestClient).updateOrder(org.mockito.Mockito.eq("100"), orderCaptor.capture());
        assertThat(orderCaptor.getValue().getShipping().getTracking_no()).isEqualTo("9400111899223855555555");
        assertThat(orderCaptor.getValue().getShipping().getTracking_link())
                .isEqualTo("https://tools.usps.com/go/TrackConfirmAction.action?tLabels=9400111899223855555555");
        assertThat(orderCaptor.getValue().getShipping().getDate_shipped())
                .isEqualTo(OffsetDateTime.parse("2026-06-09T14:00:00Z").toZonedDateTime());
        assertThat(orderCaptor.getValue().getIs_filed()).isFalse();
        assertThat(orderCaptor.getValue().getRemarks()).isNull();
        verify(bricklinkRestClient).updateOrderStatus("100", com.bricklink.api.rest.model.v1.OrderStatus.SHIPPED);
        verify(bricklinkRestClient).sendDriveThru("100", true);
    }

    @Test
    void runOnceNeverWritesNewOrderToShipStationOutsideProductionEvenWhenApplyRequested() throws Exception {
        environmentProperties.setProduction(false);
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        stagePayloads(marketplaceOrder, order("100", "PAID"), List.of(orderItem(3001L)));

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.applied()).isFalse();
        assertThat(result.ordersMapped()).isEqualTo(1);
        assertThat(result.ordersSkipped()).isEqualTo(1);
        assertThat(result.ordersCreated()).isZero();
        assertThat(result.ordersUpdated()).isZero();
        assertNoExternalOrderWrites();
    }

    @Test
    void runOnceNeverReconcilesShippedOrderToBricklinkOutsideProductionEvenWhenApplyRequested() throws Exception {
        environmentProperties.setProduction(false);
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        stagePayloads(marketplaceOrder, order("100", "PAID"), List.of(orderItem(3001L)));
        when(shipStationRestClient.getOrders(Map.of("orderNumber", "BL-100")))
                .thenReturn(OrdersList.builder()
                        .orders(List.of(ShipStationOrder.builder()
                                .orderId(42L)
                                .orderNumber("BL-100")
                                .orderStatus(com.shipstation.api.rest.model.OrderStatus.SHIPPED.label())
                                .build()))
                        .build());

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.applied()).isFalse();
        assertThat(result.ordersShippedReconciled()).isZero();
        assertThat(result.ordersSkipped()).isEqualTo(1);
        assertNoExternalOrderWrites();
    }

    private void assertNoExternalOrderWrites() {
        // Non-production runs must not even look up ShipStation orders: every lookup leads to a write.
        verifyNoInteractions(shipStationRestClient, bricklinkRestClient);
        verify(canonicalShipmentReconciliationService, never()).persistTrackedShipment(any(), any(), any());
        verify(itemInventoryDao, never()).updateInventoryState(any(), any(), any());
        verify(marketplaceOrderDao, never()).update(any());
    }

    private void stagePayloads(MarketplaceOrder marketplaceOrder, Order order, List<OrderItem> orderItems) throws Exception {
        when(marketplaceOrderDao.findFulfillmentCandidates("BRICKLINK", properties.effectiveStatuses(), 25))
                .thenReturn(new LinkedHashSet<>(List.of(marketplaceOrder)));
        when(marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                marketplaceOrder.getMarketplaceOrderId(),
                FulfillmentSyncService.ORDER_RESPONSE_PAYLOAD
        )).thenReturn(Optional.of(payload(501, marketplaceOrder.getMarketplaceOrderId(), FulfillmentSyncService.ORDER_RESPONSE_PAYLOAD, objectMapper.writeValueAsString(order))));
        when(marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                marketplaceOrder.getMarketplaceOrderId(),
                FulfillmentSyncService.ORDER_ITEMS_RESPONSE_PAYLOAD
        )).thenReturn(Optional.of(payload(502, marketplaceOrder.getMarketplaceOrderId(), FulfillmentSyncService.ORDER_ITEMS_RESPONSE_PAYLOAD, objectMapper.writeValueAsString(orderItems))));
        when(orderItemImageResolver.resolveImageUrls(marketplaceOrder)).thenReturn(Map.of());
    }

    private static MarketplaceOrder marketplaceOrder(Integer marketplaceOrderId, String externalOrderId) {
        return MarketplaceOrder.builder()
                .marketplaceOrderId(marketplaceOrderId)
                .marketplaceCode("BRICKLINK")
                .externalOrderId(externalOrderId)
                .externalStatusCode("PAID")
                .build();
    }

    private static MarketplaceOrderPayload payload(
            Integer marketplaceOrderPayloadId,
            Integer marketplaceOrderId,
            String payloadTypeCode,
            String payloadJson
    ) {
        return MarketplaceOrderPayload.builder()
                .marketplaceOrderPayloadId(marketplaceOrderPayloadId)
                .marketplaceOrderId(marketplaceOrderId)
                .payloadTypeCode(payloadTypeCode)
                .payloadJson(payloadJson)
                .build();
    }

    private static Order order(String orderId, String status) {
        Cost cost = new Cost();
        cost.setGrand_total(38.0);
        cost.setShipping(8.0);
        cost.setInsurance(0.75);

        Order order = new Order();
        order.setOrder_id(orderId);
        order.setStatus(status);
        order.setDate_ordered(LocalDateTime.parse("2026-06-08T10:00:00"));
        order.setBuyer_name("buyer-one");
        order.setBuyer_email("buyer@example.com");
        order.setShipping(shipping());
        order.setCost(cost);
        return order;
    }

    private static Shipping shipping() {
        Name name = new Name();
        name.setFull("Jane Buyer");
        Address address = new Address();
        address.setName(name);
        address.setAddress1("1 Main St");
        address.setCountry_code("US");
        Shipping shipping = new Shipping();
        shipping.setAddress(address);
        return shipping;
    }

    private static OrderItem orderItem(Long inventoryId) {
        Item item = new Item();
        item.setNo("1234-1");
        item.setName("Test Set");
        OrderItem orderItem = new OrderItem();
        orderItem.setInventory_id(inventoryId);
        orderItem.setItem(item);
        orderItem.setQuantity(1);
        orderItem.setUnit_price_final(5.49);
        return orderItem;
    }

    private static <T> BricklinkResource<T> resource(T data) {
        BricklinkResource<T> resource = new BricklinkResource<>();
        resource.setData(data);
        return resource;
    }

    private static CanonicalShipmentReconciliationService.CanonicalFulfillmentOrder canonicalOrder() {
        return new CanonicalShipmentReconciliationService.CanonicalFulfillmentOrder(
                Transactions.builder().transactionId(701L).build(),
                List.of(702L)
        );
    }

    private static CanonicalShipmentReconciliationService.CanonicalShipment canonicalShipment(Shipment shipStationShipment) {
        return new CanonicalShipmentReconciliationService.CanonicalShipment(
                io.legohunter.data.dto.Shipment.builder()
                        .shipmentId(801L)
                        .shipmentTrackingNumber(shipStationShipment.getTrackingNumber())
                        .build(),
                Carrier.builder().carrierCode("USPS")
                        .trackingUrlPattern("https://tools.usps.com/go/TrackConfirmAction.action?tLabels=%s")
                        .build()
        );
    }
}
