package io.legohunter.egress.imagehosting;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageHostingSyncMetricsService {
    private static final String PROVIDER_TAG = "provider";
    private static final String OUTCOME_TAG = "outcome";
    private static final String DRY_RUN_TAG = "dry_run";
    private static final String RESULT_TAG = "result";
    private static final String OPERATION_TAG = "operation";

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicInteger> differenceGauges = new ConcurrentHashMap<>();

    public void recordSync(ImageHostingSyncResult result, String providerTag, long elapsedMillis) {
        Counter.builder("image_hosting_sync")
                .tag(PROVIDER_TAG, providerTag)
                .tag(OUTCOME_TAG, result.getOutcome().name().toLowerCase())
                .tag(DRY_RUN_TAG, Boolean.toString(result.isDryRun()))
                .register(meterRegistry)
                .increment();

        Timer.builder("image_hosting_sync_duration")
                .tag(PROVIDER_TAG, providerTag)
                .tag(OUTCOME_TAG, result.getOutcome().name().toLowerCase())
                .tag(DRY_RUN_TAG, Boolean.toString(result.isDryRun()))
                .register(meterRegistry)
                .record(elapsedMillis, TimeUnit.MILLISECONDS);
    }

    public void recordPhotoUpload(String providerTag, String result, double count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("image_hosting_photo_upload")
                .tag(PROVIDER_TAG, providerTag)
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment(count);
    }

    public void recordAlbumOperation(String providerTag, String operation, String result) {
        Counter.builder("image_hosting_album_operation")
                .tag(PROVIDER_TAG, providerTag)
                .tag(OPERATION_TAG, operation)
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment();
    }

    public void recordAlbumCreation(String providerTag, String result) {
        Counter.builder("image_hosting_album_creation")
                .tag(PROVIDER_TAG, providerTag(providerTag))
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment();
        log.info(
                "image_hosting.album_creation provider={} result={}",
                providerTag(providerTag),
                result
        );
    }

    public void recordHeadroomBlocked(String providerTag, int newPhotos) {
        Counter.builder("image_hosting_headroom_blocked")
                .tag(PROVIDER_TAG, providerTag(providerTag))
                .register(meterRegistry)
                .increment(newPhotos);
    }

    /**
     * One difference report run. The gauges hold the last complete run: albums compared, and albums where the
     * image host differs from the database.
     */
    public void recordDifferenceReport(String providerTag, boolean complete, int albumsChecked, int albumsWithDifferences) {
        String provider = providerTag(providerTag);
        Counter.builder("image_hosting_difference_report")
                .tag(PROVIDER_TAG, provider)
                .tag(OUTCOME_TAG, complete ? "completed" : "failed")
                .register(meterRegistry)
                .increment();
        if (!complete) {
            return;
        }
        differenceGauge("image_hosting_difference_report_albums_checked", provider).set(albumsChecked);
        differenceGauge("image_hosting_difference_report_albums_different", provider).set(albumsWithDifferences);
    }

    private AtomicInteger differenceGauge(String name, String provider) {
        return differenceGauges.computeIfAbsent(name + "|" + provider, key -> {
            AtomicInteger value = new AtomicInteger();
            io.micrometer.core.instrument.Gauge.builder(name, value, AtomicInteger::get)
                    .tag(PROVIDER_TAG, provider)
                    .register(meterRegistry);
            return value;
        });
    }

    public void recordAlbumAdoption(String providerTag, String result) {
        Counter.builder("image_hosting_album_adoption")
                .tag(PROVIDER_TAG, providerTag(providerTag))
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment();
        if ("adopted".equals(result) || "no_match".equals(result)) {
            log.info(
                    "image_hosting.album_adoption provider={} result={}",
                    providerTag(providerTag),
                    result
            );
        } else {
            log.warn(
                    "image_hosting.album_adoption provider={} result={}",
                    providerTag(providerTag),
                    result
            );
        }
    }

    public void recordShortUrl(String providerTag, String operation, String result) {
        Counter.builder("image_hosting_short_url")
                .tag(PROVIDER_TAG, providerTag(providerTag))
                .tag(OPERATION_TAG, operation)
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment();
        if ("failed".equals(result)) {
            log.warn(
                    "image_hosting.short_url provider={} operation={} result={}",
                    providerTag(providerTag),
                    operation,
                    result
            );
        } else {
            log.info(
                    "image_hosting.short_url provider={} operation={} result={}",
                    providerTag(providerTag),
                    operation,
                    result
            );
        }
    }

    public void recordScheduledSync(String providerTag, String outcome, long elapsedMillis) {
        Counter.builder("image_hosting_scheduled_sync")
                .tag(PROVIDER_TAG, providerTag)
                .tag(OUTCOME_TAG, outcome)
                .register(meterRegistry)
                .increment();

        Timer.builder("image_hosting_scheduled_sync_duration")
                .tag(PROVIDER_TAG, providerTag)
                .tag(OUTCOME_TAG, outcome)
                .register(meterRegistry)
                .record(elapsedMillis, TimeUnit.MILLISECONDS);
    }

    public void recordScheduledSyncInventory(String providerTag, String result, double count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("image_hosting_scheduled_sync_inventory")
                .tag(PROVIDER_TAG, providerTag)
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment(count);
    }

    private String providerTag(String providerTag) {
        return providerTag == null || providerTag.isBlank() ? "unknown" : providerTag;
    }
}
