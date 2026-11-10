package io.legohunter.ingress.orders;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkOpenOrderProbeJobTest {
    @Test
    void runOnceDelegatesToProbeService() {
        BricklinkOpenOrderProbeService probeService = mock(BricklinkOpenOrderProbeService.class);
        BricklinkOrderProbeResult expected = new BricklinkOrderProbeResult(
                "SUCCESS",
                2,
                2,
                0,
                5,
                100L
        );
        when(probeService.runOnce()).thenReturn(expected);
        BricklinkOpenOrderProbeJob job = new BricklinkOpenOrderProbeJob(probeService);

        BricklinkOrderProbeResult result = job.runOnce();

        assertThat(result).isEqualTo(expected);
        verify(probeService).runOnce();
    }
}
