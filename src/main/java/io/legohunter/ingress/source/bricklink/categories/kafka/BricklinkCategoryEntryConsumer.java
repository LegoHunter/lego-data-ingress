package io.legohunter.ingress.source.bricklink.categories.kafka;

import io.legohunter.ingress.common.ExternalServiceIds;
import io.legohunter.data.dao.ExternalCategoryDao;
import io.legohunter.data.dto.ExternalCategory;
import io.legohunter.ingress.source.bricklink.categories.model.CategoryEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BricklinkCategoryEntryConsumer {

    private final ExternalCategoryDao externalCategoryDao;


    @KafkaListener(
            topics = "${kafka.topic-configuration.bricklink-category-entry.topic}",
            groupId = "${kafka.topic-configuration.bricklink-category-entry.consumer.group-id}",
            containerFactory = "bricklinkCategoryEntryContainerFactory")
    public void listen(@Payload CategoryEntry entry) {
        try {
            ExternalCategory category = ExternalCategory.builder()
                    .externalServiceId(ExternalServiceIds.BRICKLINK)
                    .externalCategoryKey(String.valueOf(entry.getCategory()))
                    .categoryName(entry.getCategoryName())
                    .parentExternalCategoryId(null)
                    .build();

            externalCategoryDao.upsert(category);
        } catch (Exception e) {
            log.error("Failed to process Bricklink Category entry {},  message: {}", entry, e.getMessage(), e);
        }
    }
}
