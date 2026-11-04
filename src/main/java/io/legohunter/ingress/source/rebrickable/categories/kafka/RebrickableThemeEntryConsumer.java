package io.legohunter.ingress.source.rebrickable.categories.kafka;

import io.legohunter.data.dto.ExternalService;
import io.legohunter.data.dao.ExternalCategoryDao;
import io.legohunter.data.dto.ExternalCategory;
import io.legohunter.ingress.source.rebrickable.categories.model.RebrickableThemeEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RebrickableThemeEntryConsumer {

    private final ExternalCategoryDao externalCategoryDao;


    @KafkaListener(
            topics = "${kafka.topic-configuration.rebrickable-theme-entry.topic}",
            groupId = "${kafka.topic-configuration.rebrickable-theme-entry.consumer.group-id}",
            containerFactory = "rebrickableThemeEntryContainerFactory")
    public void listen(@Payload RebrickableThemeEntry entry) {
        try {
            ExternalCategory category = ExternalCategory.builder()
                    .externalServiceId(ExternalService.Service.REBRICKABLE.getExternalServiceId())
                    .externalCategoryKey(String.valueOf(entry.getId()))
                    .categoryName(entry.getName())
                    .parentExternalCategoryId(parentExternalCategoryId(entry.getParentId()))
                    .build();

            externalCategoryDao.upsert(category);
        } catch (Exception e) {
            log.error("Failed to process Rebrickable Theme entry {} message: {}", entry, e.getMessage(), e);
        }
    }

    private Integer parentExternalCategoryId(Integer parentId) {
        if (parentId == null) {
            return null;
        }
        return externalCategoryDao.findByExternalServiceIdAndExternalCategoryKey(ExternalService.Service.REBRICKABLE.getExternalServiceId(), String.valueOf(parentId))
                .map(ExternalCategory::getExternalCategoryId)
                .orElse(null);
    }
}
