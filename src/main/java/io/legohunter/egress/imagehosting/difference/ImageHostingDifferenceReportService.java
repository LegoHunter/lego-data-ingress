package io.legohunter.egress.imagehosting.difference;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.egress.imagehosting.ImageHostingSyncMetricsService;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.difference.HostedDifferenceReport.AlbumFindings;
import io.legohunter.egress.imagehosting.difference.HostedDifferenceReport.Finding;
import io.legohunter.egress.imagehosting.headroom.AccountHeadroom;
import io.legohunter.egress.imagehosting.headroom.ImageHostingHeadroomGuard;
import io.legohunter.egress.imagehosting.plan.HostedTextComparison;
import io.legohunter.egress.imagehosting.plan.ImageHostingReconciliationPlanner;
import io.legohunter.egress.imagehosting.remote.HostedAccountListing;
import io.legohunter.egress.imagehosting.remote.ImageHostingRemoteSnapshot;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingPhoto;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateReader;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateRequest;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.lego.data.environment.LegoEnvironmentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Compares every album the database knows with the image host and reports where they differ (HOMELAB-111).
 *
 * <p>The database is the system of record and the host is a published copy. The sync job chooses its work from
 * database state alone, so a change made on the host (an edit by hand, a deletion by the host, a write that half
 * succeeded) is never noticed by it. This report notices. It repairs nothing: it changes nothing on the host
 * and nothing in the database.
 *
 * <p>How an album is compared:
 * <ol>
 *   <li>From the host's album listing, read once for the whole account: is the album there, and do its title,
 *       description, cover and photo count match? That costs no call per album.</li>
 *   <li>The album's photo list is read only when the listing shows something off, or when it is the album's turn:
 *       each album gets a full read once every {@code full-check-days} nights. The turn exists because the
 *       listing's photo count is a hint, not proof (the host has been seen to report it late).</li>
 * </ol>
 *
 * <p>What counts as a difference is decided by {@link ImageHostingReconciliationPlanner}, the planner behind an
 * item's sync plan, so a finding here is something that item's sync plan would also act on.
 */
@Slf4j
@Service
public class ImageHostingDifferenceReportService {
    private static final int DETAIL_TEXT_LIMIT = 200;

    private final ExternalImageAlbumDao externalImageAlbumDao;
    private final ImageHostingDesiredStateReader desiredStateReader;
    private final ImageHostingReconciliationPlanner reconciliationPlanner;
    private final HostedAccountListing accountListing;
    private final ImageHostingHeadroomGuard headroomGuard;
    private final ImageHostingSyncProperties properties;
    private final ImageHostingSyncMetricsService metricsService;
    private final LegoEnvironmentProperties environment;
    private final Clock clock;

    private final AtomicReference<HostedDifferenceReport> lastReport = new AtomicReference<>();

    @Autowired
    public ImageHostingDifferenceReportService(
            ExternalImageAlbumDao externalImageAlbumDao,
            ImageHostingDesiredStateReader desiredStateReader,
            ImageHostingReconciliationPlanner reconciliationPlanner,
            HostedAccountListing accountListing,
            ImageHostingHeadroomGuard headroomGuard,
            ImageHostingSyncProperties properties,
            ImageHostingSyncMetricsService metricsService,
            LegoEnvironmentProperties environment
    ) {
        this(externalImageAlbumDao, desiredStateReader, reconciliationPlanner, accountListing, headroomGuard,
                properties, metricsService, environment, Clock.systemUTC());
    }

    ImageHostingDifferenceReportService(
            ExternalImageAlbumDao externalImageAlbumDao,
            ImageHostingDesiredStateReader desiredStateReader,
            ImageHostingReconciliationPlanner reconciliationPlanner,
            HostedAccountListing accountListing,
            ImageHostingHeadroomGuard headroomGuard,
            ImageHostingSyncProperties properties,
            ImageHostingSyncMetricsService metricsService,
            LegoEnvironmentProperties environment,
            Clock clock
    ) {
        this.externalImageAlbumDao = externalImageAlbumDao;
        this.desiredStateReader = desiredStateReader;
        this.reconciliationPlanner = reconciliationPlanner;
        this.accountListing = accountListing;
        this.headroomGuard = headroomGuard;
        this.properties = properties;
        this.metricsService = metricsService;
        this.environment = environment;
        this.clock = clock;
    }

    /** The last report since the service started; reports are kept in memory only. */
    public Optional<HostedDifferenceReport> last() {
        return Optional.ofNullable(lastReport.get());
    }

    /** Compares now. Never throws: a run that cannot finish returns a report with {@code complete} false. */
    public HostedDifferenceReport run() {
        Instant startedAt = clock.instant();
        ImageHostingSyncProperties.ResolvedProvider provider = properties.resolveProvider(null, null);
        HostedDifferenceReport report;
        try {
            report = compare(provider, startedAt);
        } catch (RuntimeException e) {
            // Without the whole album listing every album would look missing, so report nothing rather than that.
            log.warn(
                    "image_hosting.difference_report.failed environment={} provider={} message={}",
                    environment.getCode(),
                    provider.provider(),
                    e.getMessage(),
                    e
            );
            report = new HostedDifferenceReport(
                    environment.getCode(), provider.provider(), startedAt, elapsedMillis(startedAt), false,
                    e.getMessage(), null, 0, 0, 0, 0, 0, 0, 0, 0, List.of()
            );
        }
        lastReport.set(report);
        metricsService.recordDifferenceReport(
                provider.metricsTag(),
                report.complete(),
                report.albumsChecked(),
                report.albumsWithDifferences()
        );
        return report;
    }

    private HostedDifferenceReport compare(ImageHostingSyncProperties.ResolvedProvider provider, Instant startedAt) {
        Map<String, HostedAlbum> hostAlbums = accountListing.albums().stream()
                .filter(album -> album.getId() != null)
                .collect(Collectors.toMap(HostedAlbum::getId, album -> album, (first, second) -> first, LinkedHashMap::new));
        List<ExternalImageAlbum> databaseAlbums = externalImageAlbumDao.findAll().stream()
                .filter(album -> Objects.equals(album.getExternalServiceId(), provider.externalServiceId()))
                .sorted(Comparator.comparing(ExternalImageAlbum::getExternalImageAlbumId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        AccountHeadroom account = headroomGuard.current();
        Run run = new Run(provider, hostAlbums, accountLimitLikely(account), LocalDate.now(clock).toEpochDay());

        List<AlbumFindings> albums = new ArrayList<>();
        int notPublished = 0;
        int checked = 0;
        int known = 0;
        for (ExternalImageAlbum databaseAlbum : databaseAlbums) {
            if (isBlank(databaseAlbum.getExternalAlbumId())) {
                notPublished++;
                continue;
            }
            checked++;
            if (hostAlbums.containsKey(databaseAlbum.getExternalAlbumId())) {
                known++;
            }
            albumFindings(databaseAlbum, run).ifPresent(albums::add);
        }

        int withDifferences = (int) albums.stream().filter(album -> !album.pendingWorkOnly()).count();
        int pendingOnly = albums.size() - withDifferences;
        HostedDifferenceReport report = new HostedDifferenceReport(
                environment.getCode(),
                provider.provider(),
                startedAt,
                elapsedMillis(startedAt),
                true,
                null,
                account,
                databaseAlbums.size(),
                notPublished,
                checked,
                run.readInFull,
                withDifferences,
                pendingOnly,
                hostAlbums.size(),
                hostAlbums.size() - known,
                List.copyOf(albums)
        );
        log(report);
        return report;
    }

    private Optional<AlbumFindings> albumFindings(ExternalImageAlbum databaseAlbum, Run run) {
        String itemUuid = null;
        List<Finding> findings;
        try {
            ImageHostingDesiredStateSnapshot desired = desiredStateReader.read(ImageHostingDesiredStateRequest.builder()
                    .itemInventoryId(databaseAlbum.getItemInventoryId())
                    .provider(run.provider.provider())
                    .externalServiceId(run.provider.externalServiceId())
                    .build());
            itemUuid = desired.getInventory() == null ? null : desired.getInventory().getUuid();
            findings = compareAlbum(databaseAlbum, desired, run);
        } catch (RuntimeException e) {
            // One album that cannot be read must not hide the others.
            findings = List.of(finding(HostedDifferenceType.CHECK_FAILED, e.getMessage(), List.of(), false));
        }
        if (findings.isEmpty()) {
            return Optional.empty();
        }
        boolean pendingOnly = findings.stream().allMatch(finding -> finding.type().pending());
        return Optional.of(new AlbumFindings(
                databaseAlbum.getItemInventoryId(),
                itemUuid,
                databaseAlbum.getExternalAlbumId(),
                databaseAlbum.getAlbumUrl(),
                pendingOnly,
                findings
        ));
    }

    private List<Finding> compareAlbum(ExternalImageAlbum databaseAlbum, ImageHostingDesiredStateSnapshot desired, Run run) {
        String albumId = databaseAlbum.getExternalAlbumId();
        HostedAlbum hostAlbum = run.hostAlbums.get(albumId);
        List<String> publishedPhotoIds = desired.getPhotos().stream()
                .map(DesiredImageHostingPhoto::getExternalServiceImageId)
                .filter(id -> !isBlank(id))
                .toList();

        List<HostedPhoto> hostPhotos = List.of();
        if (hostAlbum != null) {
            // First pass: the listing only. The album's photos are taken to be the ones the database lists.
            List<HostedPhoto> assumedPhotos = publishedPhotoIds.stream()
                    .map(id -> HostedPhoto.builder().id(id).build())
                    .toList();
            SyncPlan listingPlan = reconciliationPlanner.plan(desired, remote(run, albumId, hostAlbum, assumedPhotos));
            boolean countMatches = Objects.equals(hostAlbum.getPhotoCount(), publishedPhotoIds.size());
            if (!listingPlan.hasActions() && countMatches && !isFullCheckTurn(databaseAlbum, run)) {
                return List.of();
            }
            hostPhotos = accountListing.albumPhotos(albumId);
            run.readInFull++;
        }

        SyncPlan plan = reconciliationPlanner.plan(desired, remote(run, albumId, hostAlbum, hostPhotos));
        return findings(plan, publishedPhotoIds, run);
    }

    private ImageHostingRemoteSnapshot remote(Run run, String albumId, HostedAlbum hostAlbum, List<HostedPhoto> photos) {
        return ImageHostingRemoteSnapshot.builder()
                .provider(run.provider.provider())
                .externalServiceId(run.provider.externalServiceId())
                .requestedAlbumId(albumId)
                .album(hostAlbum)
                .photos(photos)
                .build();
    }

    /** Each album gets a full read once every {@code full-check-days} nights; one day or less means every night. */
    private boolean isFullCheckTurn(ExternalImageAlbum databaseAlbum, Run run) {
        int days = properties.getSync().getDifferenceReport().getFullCheckDays();
        if (days <= 1 || databaseAlbum.getExternalImageAlbumId() == null) {
            return true;
        }
        return Math.floorMod(databaseAlbum.getExternalImageAlbumId(), (long) days) == Math.floorMod(run.epochDay, (long) days);
    }

    private List<Finding> findings(SyncPlan plan, List<String> publishedPhotoIds, Run run) {
        List<Finding> findings = new ArrayList<>();
        List<String> notPublished = new ArrayList<>();
        List<String> metadataPending = new ArrayList<>();
        for (SyncAction action : plan.getActions()) {
            Map<String, String> attributes = action.getAttributes();
            switch (action.getType()) {
                case REPAIR_ALBUM_ID -> {
                    if (action.isBlocked()) {
                        findings.add(finding(HostedDifferenceType.CHECK_FAILED,
                                attributes.getOrDefault("failureMessages", action.getDescription()), List.of(), false));
                    } else {
                        findings.add(albumMissing(publishedPhotoIds, run));
                    }
                }
                case UPDATE_ALBUM_METADATA -> {
                    addTextFinding(findings, HostedDifferenceType.TITLE,
                            attributes.get("desiredTitle"), attributes.get("remoteTitle"));
                    addTextFinding(findings, HostedDifferenceType.DESCRIPTION,
                            attributes.get("desiredDescription"), attributes.get("remoteDescription"));
                }
                case UPDATE_ALBUM_MEMBERSHIP, FIX_PRIMARY_PHOTO -> addMembershipFindings(findings, attributes, run);
                case UPLOAD_PHOTO -> notPublished.add(Optional.ofNullable(action.getFilename()).orElse("?"));
                case UPDATE_PHOTO_METADATA -> metadataPending.add(action.getPhotoId());
                default -> findings.add(finding(HostedDifferenceType.CHECK_FAILED,
                        "%s: %s".formatted(action.getType(), action.getDescription()), List.of(), false));
            }
        }
        if (!notPublished.isEmpty()) {
            findings.add(finding(HostedDifferenceType.PHOTOS_NOT_PUBLISHED,
                    "%s photo(s) not published: %s".formatted(notPublished.size(), String.join(", ", notPublished)),
                    List.of(), false));
        }
        if (!metadataPending.isEmpty()) {
            findings.add(finding(HostedDifferenceType.PHOTO_METADATA_PENDING,
                    "%s photo(s) with a title or caption not sent yet".formatted(metadataPending.size()),
                    metadataPending, false));
        }
        return findings;
    }

    private Finding albumMissing(List<String> publishedPhotoIds, Run run) {
        Set<String> loose = run.loosePhotoIds();
        long stillOnAccount = publishedPhotoIds.stream().filter(loose::contains).count();
        String detail = "The album is not on the image host. %s of its %s photo(s) are still on the account, in no album."
                .formatted(stillOnAccount, publishedPhotoIds.size());
        return finding(HostedDifferenceType.ALBUM_MISSING, detail, List.of(),
                run.accountLimitLikely && stillOnAccount < publishedPhotoIds.size());
    }

    private void addTextFinding(List<Finding> findings, HostedDifferenceType type, String desired, String remote) {
        if (HostedTextComparison.sameText(desired, remote)) {
            return;
        }
        findings.add(finding(type,
                "Database: \"%s\". Image host: \"%s\".".formatted(shortened(desired), shortened(remote)),
                List.of(), false));
    }

    private void addMembershipFindings(List<Finding> findings, Map<String, String> attributes, Run run) {
        List<String> missing = ids(attributes.get("desiredOnlyPhotoIds"));
        List<String> extra = ids(attributes.get("remoteOnlyPhotoIds"));
        if (!missing.isEmpty()) {
            Set<String> loose = run.loosePhotoIds();
            List<String> takenOut = missing.stream().filter(loose::contains).toList();
            List<String> deleted = missing.stream().filter(id -> !loose.contains(id)).toList();
            if (!deleted.isEmpty()) {
                findings.add(finding(HostedDifferenceType.PHOTOS_DELETED,
                        "%s photo(s) are neither in the album nor anywhere else on the account".formatted(deleted.size()),
                        deleted, run.accountLimitLikely));
            }
            if (!takenOut.isEmpty()) {
                findings.add(finding(HostedDifferenceType.PHOTOS_NOT_IN_ALBUM,
                        "%s photo(s) are on the account but not in the album".formatted(takenOut.size()),
                        takenOut, false));
            }
        }
        if (!extra.isEmpty()) {
            findings.add(finding(HostedDifferenceType.EXTRA_PHOTOS,
                    "%s photo(s) in the album that the database does not list for this item".formatted(extra.size()),
                    extra, false));
        }
        if (missing.isEmpty() && extra.isEmpty()
                && !Objects.equals(attributes.get("desiredPhotoIds"), attributes.get("remotePhotoIds"))) {
            findings.add(finding(HostedDifferenceType.PHOTO_ORDER,
                    "Database order: %s. Image host order: %s.".formatted(
                            attributes.get("desiredPhotoIds"), attributes.get("remotePhotoIds")),
                    List.of(), false));
        }
        String desiredCover = attributes.get("desiredPrimaryPhotoId");
        String hostCover = attributes.get("remotePrimaryPhotoId");
        // A cover that is itself missing is already reported above.
        if (!isBlank(desiredCover) && !Objects.equals(desiredCover, hostCover) && !missing.contains(desiredCover)) {
            findings.add(finding(HostedDifferenceType.COVER,
                    "Primary photo: %s. Album cover on the image host: %s.".formatted(desiredCover, hostCover),
                    List.of(desiredCover), false));
        }
    }

    /**
     * A limited account deletes its oldest items above its limit. When such an account is at or past the count
     * where publishing stops, content the host no longer has was most likely removed that way.
     */
    private boolean accountLimitLikely(AccountHeadroom account) {
        return account != null
                && account.known()
                && account.limited()
                && account.itemCount() != null
                && account.publishCeiling() != null
                && account.itemCount() >= account.publishCeiling();
    }

    private void log(HostedDifferenceReport report) {
        for (AlbumFindings album : report.albums()) {
            String types = album.findings().stream().map(finding -> finding.type().name()).collect(Collectors.joining(","));
            if (album.pendingWorkOnly()) {
                log.info(
                        "image_hosting.difference_report.album_pending environment={} itemInventoryId={} albumId={} findings={}",
                        report.environment(), album.itemInventoryId(), album.hostedAlbumId(), types
                );
            } else {
                log.warn(
                        "image_hosting.difference_report.album_differs environment={} itemInventoryId={} albumId={} findings={}",
                        report.environment(), album.itemInventoryId(), album.hostedAlbumId(), types
                );
            }
        }
        log.info(
                "image_hosting.difference_report.completed environment={} provider={} albumsInDatabase={} albumsNotPublished={} albumsChecked={} albumsReadInFull={} albumsWithDifferences={} albumsWithPendingWorkOnly={} albumsOnHost={} albumsOnHostUnknown={} elapsedMillis={}",
                report.environment(),
                report.provider(),
                report.albumsInDatabase(),
                report.albumsNotPublished(),
                report.albumsChecked(),
                report.albumsReadInFull(),
                report.albumsWithDifferences(),
                report.albumsWithPendingWorkOnly(),
                report.albumsOnHost(),
                report.albumsOnHostUnknown(),
                report.elapsedMillis()
        );
    }

    private static Finding finding(HostedDifferenceType type, String detail, List<String> photoIds, boolean accountLimitLikely) {
        return new Finding(type, detail, List.copyOf(photoIds), accountLimitLikely, type.whatToDo());
    }

    private static List<String> ids(String commaSeparated) {
        if (isBlank(commaSeparated)) {
            return List.of();
        }
        return Arrays.stream(commaSeparated.split(",")).filter(id -> !id.isBlank()).toList();
    }

    private static String shortened(String text) {
        String plain = HostedTextComparison.plain(text).replace('\n', ' ');
        return plain.length() <= DETAIL_TEXT_LIMIT ? plain : plain.substring(0, DETAIL_TEXT_LIMIT) + "...";
    }

    private long elapsedMillis(Instant startedAt) {
        return clock.millis() - startedAt.toEpochMilli();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** State of one run. The photos in no album are read at most once, and only when a finding needs them. */
    private final class Run {
        private final ImageHostingSyncProperties.ResolvedProvider provider;
        private final Map<String, HostedAlbum> hostAlbums;
        private final boolean accountLimitLikely;
        private final long epochDay;
        private int readInFull;
        private Set<String> loosePhotoIds;

        private Run(
                ImageHostingSyncProperties.ResolvedProvider provider,
                Map<String, HostedAlbum> hostAlbums,
                boolean accountLimitLikely,
                long epochDay
        ) {
            this.provider = provider;
            this.hostAlbums = hostAlbums;
            this.accountLimitLikely = accountLimitLikely;
            this.epochDay = epochDay;
        }

        private Set<String> loosePhotoIds() {
            if (loosePhotoIds == null) {
                loosePhotoIds = accountListing.photosNotInAlbum().stream()
                        .map(HostedPhoto::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
            }
            return loosePhotoIds;
        }
    }
}
