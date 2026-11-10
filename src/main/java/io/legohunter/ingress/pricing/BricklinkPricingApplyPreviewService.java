package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dto.PricingApplyReadinessReview;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BricklinkPricingApplyPreviewService {
    static final int MAX_REPORT_LIMIT = 500;

    private final PricingApplyReadinessDao pricingApplyReadinessDao;

    public BricklinkPricingApplyPreviewReport buildPreview(String readinessStatusCode, String blockReasonCode, int limit) {
        String effectiveReadinessStatusCode = normalize(readinessStatusCode);
        String effectiveBlockReasonCode = normalize(blockReasonCode);
        Set<PricingApplyReadinessReview> reviews = pricingApplyReadinessDao.findLatestReviews(
                effectiveReadinessStatusCode,
                effectiveBlockReasonCode,
                effectiveLimit(limit)
        );
        return new BricklinkPricingApplyPreviewReport(
                now(),
                true,
                effectiveReadinessStatusCode,
                effectiveBlockReasonCode,
                summary(reviews),
                reviews
        );
    }

    public BricklinkPricingDryRunApplySelectionReport buildDryRunApplySelection(int limit) {
        Set<PricingApplyReadinessReview> reviews = pricingApplyReadinessDao.findLatestReadyToApplyReviews(effectiveLimit(limit));
        return new BricklinkPricingDryRunApplySelectionReport(now(), true, reviews.size(), reviews);
    }

    private int effectiveLimit(int limit) {
        return Math.min(MAX_REPORT_LIMIT, Math.max(1, limit));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    private BricklinkPricingApplyPreviewSummary summary(Set<PricingApplyReadinessReview> reviews) {
        Map<String, Integer> statusCounts = countByStatus(reviews);
        Map<String, Integer> blockReasonCounts = countByBlockReason(reviews);
        int readyToApplyCount = statusCounts.getOrDefault(BricklinkPricingApplyReadinessService.READY_TO_APPLY, 0)
                + statusCounts.getOrDefault(BricklinkPricingApplyReadinessService.READY_TO_APPLY_INITIAL_PRICE, 0);
        return new BricklinkPricingApplyPreviewSummary(
                reviews.size(),
                readyToApplyCount,
                reviews.size() - readyToApplyCount,
                statusCounts,
                blockReasonCounts
        );
    }

    private Map<String, Integer> countByStatus(Set<PricingApplyReadinessReview> reviews) {
        return reviews.stream()
                .collect(Collectors.groupingBy(
                        review -> valueOrUnknown(review.getReadinessStatusCode()),
                        TreeMap::new,
                        Collectors.summingInt(ignored -> 1)
                ))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<String, Integer> countByBlockReason(Set<PricingApplyReadinessReview> reviews) {
        return reviews.stream()
                .filter(review -> review.getBlockReasonCode() != null && !review.getBlockReasonCode().isBlank())
                .collect(Collectors.groupingBy(
                        review -> valueOrUnknown(review.getBlockReasonCode()),
                        TreeMap::new,
                        Collectors.summingInt(ignored -> 1)
                ))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private String valueOrUnknown(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().toUpperCase();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }
}
