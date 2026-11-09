package io.legohunter.ingress.orders;

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
import io.legohunter.data.dto.MarketplaceOrderTransactionLink;
import io.legohunter.data.dto.Party;
import io.legohunter.data.dto.TransactionPlatform;
import io.legohunter.data.dto.Transactions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BricklinkOrderProjectionServiceTest {
    @Test
    void keepsAnInvoicedProjectionImmutableOnLaterPolls() {
        TransactionPlatformDao platformDao = mock(TransactionPlatformDao.class);
        PartyDao partyDao = mock(PartyDao.class);
        PartyExternalIdentityDao identityDao = mock(PartyExternalIdentityDao.class);
        TransactionsDao transactionsDao = mock(TransactionsDao.class);
        TransactionPartySnapshotDao snapshotDao = mock(TransactionPartySnapshotDao.class);
        TransactionItemDao itemDao = mock(TransactionItemDao.class);
        TransactionItemRevenueDao revenueDao = mock(TransactionItemRevenueDao.class);
        TransactionCostDao costDao = mock(TransactionCostDao.class);
        MarketplaceOrderTransactionLinkDao linkDao = mock(MarketplaceOrderTransactionLinkDao.class);
        BricklinkOrderProjectionService service = new BricklinkOrderProjectionService(platformDao, partyDao, identityDao,
                transactionsDao, snapshotDao, itemDao, revenueDao, costDao, linkDao);

        MarketplaceOrder order = MarketplaceOrder.builder().marketplaceOrderId(7).externalOrderId("12345").build();
        Order bricklinkOrder = new Order();
        bricklinkOrder.setBuyer_name("buyer");
        when(platformDao.findTransactionPlatformByName("Bricklink"))
                .thenReturn(Optional.of(TransactionPlatform.builder().transactionPlatformId(1).build()));
        when(partyDao.findPartyById(0L)).thenReturn(Optional.of(Party.builder().partyId(0L).build()));
        when(identityDao.findByPlatformAndExternalPartyId(1, "buyer"))
                .thenReturn(Optional.of(io.legohunter.data.dto.PartyExternalIdentity.builder().partyId(2L).build()));
        when(partyDao.findPartyById(2L)).thenReturn(Optional.of(Party.builder().partyId(2L).build()));
        when(transactionsDao.findByTransactionPlatformIdAndTransactionOrderId(1, "12345"))
                .thenReturn(Optional.of(Transactions.builder().transactionId(9L).build()));
        when(linkDao.findByMarketplaceOrderId(7)).thenReturn(Set.of(MarketplaceOrderTransactionLink.builder()
                .marketplaceOrderTransactionLinkId(3).linkTypeCode("ORDER").linkStatusCode("INVOICED").build()));

        BricklinkOrderProjectionResult result = service.project(order, List.of(), bricklinkOrder);

        assertThat(result).isEqualTo(new BricklinkOrderProjectionResult(9L, true, 0, 0));
        verifyNoInteractions(snapshotDao, itemDao, revenueDao, costDao);
        verify(linkDao, never()).update(org.mockito.ArgumentMatchers.any());
    }
}
