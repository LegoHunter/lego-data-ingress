package io.legohunter.ingress.source.photo.service;

import io.legohunter.ingress.source.photo.service.PhotoKeywordRules.OtherPhoto;
import io.legohunter.ingress.source.photo.service.PhotoKeywordRules.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The owner's sealed and built-once rules (HOMELAB-96), case by case. */
class PhotoKeywordRulesTest {
    private static final List<OtherPhoto> NO_OTHER_PHOTOS = List.of();

    private static OtherPhoto other(String fileName, Boolean sealed, Boolean builtOnce) {
        return new OtherPhoto(fileName, sealed, builtOnce);
    }

    @Test
    void sealedWithNoBuiltOnceKeywordAnywhereIsSealedAndNew() {
        Outcome outcome = PhotoKeywordRules.evaluate(true, null, List.of(other("a.jpg", null, null)));

        assertThat(outcome).isEqualTo(new Outcome(true, false, "N", null));
    }

    @Test
    void sealedWithAnExplicitBuiltOnceFalseIsSealedAndNew() {
        assertThat(PhotoKeywordRules.evaluate(true, false, NO_OTHER_PHOTOS)).isEqualTo(new Outcome(true, false, "N", null));
        // The bo:false can also be on another photo.
        assertThat(PhotoKeywordRules.evaluate(true, null, List.of(other("a.jpg", null, false))))
                .isEqualTo(new Outcome(true, false, "N", null));
    }

    @Test
    void aSealedPhotoElsewhereMakesAPlainPhotoSealedAndNewToo() {
        assertThat(PhotoKeywordRules.evaluate(null, null, List.of(other("a.jpg", true, null))))
                .isEqualTo(new Outcome(true, false, "N", null));
    }

    @Test
    void withoutSealedBuiltOnceDecidesNewOrUsed() {
        assertThat(PhotoKeywordRules.evaluate(null, true, NO_OTHER_PHOTOS)).isEqualTo(new Outcome(null, true, "U", null));
        assertThat(PhotoKeywordRules.evaluate(null, false, NO_OTHER_PHOTOS)).isEqualTo(new Outcome(null, false, "N", null));
        // Taken from another photo when this one has no bo keyword.
        assertThat(PhotoKeywordRules.evaluate(null, null, List.of(other("a.jpg", null, true))))
                .isEqualTo(new Outcome(null, true, "U", null));
    }

    @Test
    void sealedFalseDoesNotCountAsSealed() {
        assertThat(PhotoKeywordRules.evaluate(false, true, NO_OTHER_PHOTOS)).isEqualTo(new Outcome(false, true, "U", null));
        assertThat(PhotoKeywordRules.evaluate(null, true, List.of(other("a.jpg", false, null))))
                .isEqualTo(new Outcome(false, true, "U", null));
        // sealed:false alone clears the sealed flag and leaves New/Used alone.
        assertThat(PhotoKeywordRules.evaluate(false, null, NO_OTHER_PHOTOS)).isEqualTo(new Outcome(false, null, null, null));
    }

    @Test
    void noSealedAndNoBuiltOnceKeywordAnywhereChangesNothing() {
        assertThat(PhotoKeywordRules.evaluate(null, null, List.of(other("a.jpg", null, null))))
                .isEqualTo(new Outcome(null, null, null, null));
        assertThat(PhotoKeywordRules.evaluate(null, null, null)).isEqualTo(new Outcome(null, null, null, null));
    }

    @Test
    void sealedAndBuiltOnceOnTheSamePhotoIsRejected() {
        Outcome outcome = PhotoKeywordRules.evaluate(true, true, NO_OTHER_PHOTOS);

        assertThat(outcome.rejected()).isTrue();
        assertThat(outcome.rejection()).contains("both sealed and bo:true");
    }

    @Test
    void aSealedPhotoIsRejectedWhenAnEarlierPhotoIsBuiltOnce() {
        Outcome outcome = PhotoKeywordRules.evaluate(true, null, List.of(other("front.jpg", null, true)));

        assertThat(outcome.rejected()).isTrue();
        assertThat(outcome.rejection()).contains("tagged sealed", "front.jpg", "bo:true");
    }

    @Test
    void aBuiltOncePhotoIsRejectedWhenAnEarlierPhotoIsSealed() {
        Outcome outcome = PhotoKeywordRules.evaluate(null, true, List.of(other("box.jpg", true, null)));

        assertThat(outcome.rejected()).isTrue();
        assertThat(outcome.rejection()).contains("tagged bo:true", "box.jpg", "sealed");
    }

    @Test
    void photosThatDisagreeOnBuiltOnceAreRejected() {
        assertThat(PhotoKeywordRules.evaluate(null, true, List.of(other("a.jpg", null, false))).rejection())
                .contains("bo:true", "a.jpg", "bo:false");
        assertThat(PhotoKeywordRules.evaluate(null, false, List.of(other("a.jpg", null, true))).rejection())
                .contains("bo:false", "a.jpg", "bo:true");
    }

    @Test
    void photosThatDisagreeOnSealedAreRejected() {
        assertThat(PhotoKeywordRules.evaluate(false, null, List.of(other("a.jpg", true, null))).rejection())
                .contains("sealed:false", "a.jpg", "sealed:true");
        assertThat(PhotoKeywordRules.evaluate(true, null, List.of(other("a.jpg", false, null))).rejection())
                .contains("sealed:true", "a.jpg", "sealed:false");
    }

    @Test
    void photosThatAgreeAreAccepted() {
        assertThat(PhotoKeywordRules.evaluate(null, true, List.of(other("a.jpg", null, true), other("b.jpg", false, null))))
                .isEqualTo(new Outcome(false, true, "U", null));
        assertThat(PhotoKeywordRules.evaluate(true, false, List.of(other("a.jpg", true, false))))
                .isEqualTo(new Outcome(true, false, "N", null));
    }
}
