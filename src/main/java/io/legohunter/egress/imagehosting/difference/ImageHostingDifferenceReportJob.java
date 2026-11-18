package io.legohunter.egress.imagehosting.difference;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the difference report once a night. The report itself logs its outcome. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lego.image-hosting.sync.difference-report", name = "enabled", havingValue = "true")
public class ImageHostingDifferenceReportJob {
    private final ImageHostingDifferenceReportService reportService;

    @Scheduled(
            cron = "${lego.image-hosting.sync.difference-report.cron:0 30 3 * * *}",
            zone = "${lego.image-hosting.sync.difference-report.zone:America/New_York}"
    )
    @SchedulerLock(
            name = "ImageHostingDifferenceReportJob",
            lockAtMostFor = "${lego.image-hosting.sync.difference-report.lock-at-most-for:60m}",
            lockAtLeastFor = "${lego.image-hosting.sync.difference-report.lock-at-least-for:1m}"
    )
    public void runScheduled() {
        reportService.run();
    }
}
