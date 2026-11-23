package io.legohunter.ingress.photoupload;

import io.legohunter.data.dao.PhotoUploadBatchDao;
import io.legohunter.data.dto.PhotoUploadBatch;
import io.legohunter.data.dto.PhotoUploadBatchFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The state of each file in a batch (upload-design section 13, option B). A file still pending after the failure
 * window is reported as failed: its URL has expired and nothing arrived.
 */
@Service
@RequiredArgsConstructor
public class PhotoUploadStatusService {

    public record FileStatus(String name, String state, String reason) {
    }

    public record BatchStatus(String batchId, boolean complete, List<FileStatus> files) {
    }

    private final PhotoUploadBatchDao photoUploadBatchDao;
    private final PhotoUploadProperties properties;
    private final Clock clock;

    /** Empty when the batch is unknown or belongs to another environment. */
    public Optional<BatchStatus> status(String batchId) {
        Optional<PhotoUploadBatch> batch = photoUploadBatchDao.findBatch(batchId);
        if (batch.isEmpty() || !properties.getEnvironment().equals(batch.get().getEnvironment())) {
            return Optional.empty();
        }
        ZonedDateTime now = ZonedDateTime.now(clock);
        boolean expired = !now.isBefore(batch.get().getCreatedAt().plus(failureWindow()));
        List<FileStatus> files = new ArrayList<>();
        boolean complete = true;
        for (PhotoUploadBatchFile file : photoUploadBatchDao.findFiles(batchId)) {
            String state = file.getState();
            if (PhotoUploadBatchFile.PENDING.equals(state) && expired) {
                state = "failed";
            }
            if (PhotoUploadBatchFile.PENDING.equals(state)) {
                complete = false;
            }
            files.add(new FileStatus(file.getFileName(), state, file.getReason()));
        }
        return Optional.of(new BatchStatus(batchId, complete, files));
    }

    private Duration failureWindow() {
        return properties.getStatusFailedAfter();
    }
}
