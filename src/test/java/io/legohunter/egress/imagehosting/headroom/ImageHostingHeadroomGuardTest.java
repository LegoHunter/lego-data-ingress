package io.legohunter.egress.imagehosting.headroom;

import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.model.HostedAccountUsage;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImageHostingHeadroomGuardTest {
    private ImageHostingService imageHostingService;
    private ImageHostingSyncProperties properties;
    private MovableClock clock;
    private ImageHostingHeadroomGuard guard;

    @BeforeEach
    void setUp() {
        imageHostingService = mock(ImageHostingService.class);
        properties = new ImageHostingSyncProperties();
        properties.getSync().getRetry().setInitialBackoffMs(0L);
        clock = new MovableClock();
        guard = new ImageHostingHeadroomGuard(
                Optional.of(imageHostingService),
                properties,
                new ImageHostingRetryTemplate(properties),
                clock
        );
    }

    @Test
    void anAccountWithNoLimitIsNeverHeldBack() {
        when(imageHostingService.getAccountUsage()).thenReturn(ok(new HostedAccountUsage(24703, null)));

        HeadroomDecision decision = guard.reserve(500);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.headroom().limited()).isFalse();
    }

    @Test
    void aLimitedAccountBelowItsCeilingMayPublish() {
        when(imageHostingService.getAccountUsage()).thenReturn(ok(new HostedAccountUsage(880, 1000)));

        HeadroomDecision decision = guard.reserve(20);

        // Default reserve is 100, so publishing stops at 900.
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.headroom().publishCeiling()).isEqualTo(900);
        assertThat(decision.headroom().itemCount()).isEqualTo(900);
        assertThat(decision.headroom().available()).isZero();
    }

    @Test
    void aLimitedAccountIsRefusedWhenTheNewPhotosWouldPassTheCeiling() {
        when(imageHostingService.getAccountUsage()).thenReturn(ok(new HostedAccountUsage(895, 1000)));

        HeadroomDecision decision = guard.reserve(6);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.message())
                .contains("895 of 1000")
                .contains("stops at 900")
                .contains("6 new photo(s)");
        // A refused request reserves nothing: a smaller one still fits.
        assertThat(guard.reserve(5).allowed()).isTrue();
    }

    @Test
    void photosAllowedSinceTheLastReadingCountAgainstTheAccount() {
        when(imageHostingService.getAccountUsage()).thenReturn(ok(new HostedAccountUsage(880, 1000)));

        assertThat(guard.reserve(15).allowed()).isTrue();
        assertThat(guard.reserve(10).allowed()).isFalse();
        assertThat(guard.reserve(5).allowed()).isTrue();

        // One reading served all three requests.
        verify(imageHostingService, times(1)).getAccountUsage();
    }

    @Test
    void theAccountIsReadAgainAfterTheCacheTime() {
        when(imageHostingService.getAccountUsage())
                .thenReturn(ok(new HostedAccountUsage(899, 1000)))
                .thenReturn(ok(new HostedAccountUsage(700, 1000)));

        assertThat(guard.reserve(5).allowed()).isFalse();
        clock.advance(Duration.ofSeconds(61));

        assertThat(guard.reserve(5).allowed()).isTrue();
        verify(imageHostingService, times(2)).getAccountUsage();
    }

    @Test
    void publishingIsAllowedWhenTheAccountCannotBeRead() {
        when(imageHostingService.getAccountUsage()).thenReturn(error("Flickr unavailable"));

        HeadroomDecision decision = guard.reserve(50);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.headroom().known()).isFalse();
        assertThat(decision.headroom().message()).isEqualTo("Flickr unavailable");
    }

    @Test
    void aLimitedAccountThatDoesNotReportItsCountIsNotHeldBack() {
        when(imageHostingService.getAccountUsage()).thenReturn(ok(new HostedAccountUsage(null, 1000)));

        assertThat(guard.reserve(50).allowed()).isTrue();
    }

    @Test
    void theGuardCanBeSwitchedOff() {
        properties.getSync().getHeadroom().setEnabled(false);

        assertThat(guard.reserve(5000).allowed()).isTrue();

        verifyNoInteractions(imageHostingService);
    }

    @Test
    void theReserveIsConfigurable() {
        properties.getSync().getHeadroom().setReserveItems(250);
        when(imageHostingService.getAccountUsage()).thenReturn(ok(new HostedAccountUsage(745, 1000)));

        assertThat(guard.reserve(6).allowed()).isFalse();
        assertThat(guard.current().publishCeiling()).isEqualTo(750);
    }

    private static PhotoServiceResponse<HostedAccountUsage> ok(HostedAccountUsage usage) {
        return new TestResponse(usage, false, null);
    }

    private static PhotoServiceResponse<HostedAccountUsage> error(String message) {
        return new TestResponse(null, true, message);
    }

    private record TestResponse(HostedAccountUsage value, boolean error, String responseMessage)
            implements PhotoServiceResponse<HostedAccountUsage> {
        @Override
        public boolean isError() {
            return error;
        }

        @Override
        public Integer responseCode() {
            return error ? 1 : null;
        }

        @Override
        public PhotoServiceErrorType errorType() {
            return PhotoServiceErrorType.UNKNOWN;
        }

        @Override
        public HostedAccountUsage get() {
            return value;
        }

        @Override
        public void accept(HostedAccountUsage value) {
        }
    }

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-17T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
