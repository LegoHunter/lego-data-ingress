package io.legohunter.ingress.config.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3BucketPropertiesTest {

    @Test
    void finalPhotoBucketReturnsTrimmedBucketName() {
        S3BucketProperties properties = s3BucketProperties(" lego-photos-sandbox ");

        assertThat(properties.finalPhotoBucket())
                .isEqualTo("lego-photos-sandbox");
    }

    @Test
    void finalPhotoBucketRejectsBlankBucketName() {
        S3BucketProperties properties = s3BucketProperties(" ");

        assertThatThrownBy(properties::finalPhotoBucket)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("lego.s3.buckets.final-photo must be configured");
    }

    private static S3BucketProperties s3BucketProperties(String finalPhotoBucket) {
        S3BucketProperties properties = new S3BucketProperties();
        S3BucketProperties.Buckets buckets = new S3BucketProperties.Buckets();
        buckets.setFinalPhoto(finalPhotoBucket);
        properties.setBuckets(buckets);
        return properties;
    }
}
