package io.legohunter.egress.imagehosting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageHostingAlbumIdentityTest {
    private static final String LEGACY_UUID = "faeea582ea09492f17b2dcb39432604d";
    private static final String UUID = "3f2b8c1e-7a4d-4e0b-9c55-1d2e3f4a5b6c";

    @Test
    void appendsTheReferenceLineToAGeneratedDescription() {
        assertThat(ImageHostingAlbumIdentity.withItemReference("6390-1 - Main Street.\n\nNew or used: N.", UUID))
                .isEqualTo("6390-1 - Main Street.\n\nNew or used: N.\n\nItem reference: " + UUID);
    }

    @Test
    void anEmptyDescriptionBecomesTheReferenceLine() {
        assertThat(ImageHostingAlbumIdentity.withItemReference(null, UUID)).isEqualTo("Item reference: " + UUID);
        assertThat(ImageHostingAlbumIdentity.withItemReference(" ", UUID)).isEqualTo("Item reference: " + UUID);
    }

    @Test
    void doesNotAppendTwice() {
        String once = ImageHostingAlbumIdentity.withItemReference("6390-1 - Main Street.", UUID);

        assertThat(ImageHostingAlbumIdentity.withItemReference(once, UUID)).isEqualTo(once);
    }

    @Test
    void leavesTheDescriptionAloneWithoutAUuid() {
        assertThat(ImageHostingAlbumIdentity.withItemReference("6390-1 - Main Street.", null))
                .isEqualTo("6390-1 - Main Street.");
    }

    @Test
    void recognisesALegacyAlbumWhoseWholeDescriptionIsTheUuid() {
        assertThat(ImageHostingAlbumIdentity.carries(LEGACY_UUID, LEGACY_UUID)).isTrue();
        assertThat(ImageHostingAlbumIdentity.carries("  " + LEGACY_UUID.toUpperCase() + "\n", LEGACY_UUID)).isTrue();
    }

    @Test
    void recognisesTheReferenceLine() {
        assertThat(ImageHostingAlbumIdentity.carries("6390-1 - Main Street.\n\nItem reference: " + UUID, UUID))
                .isTrue();
    }

    @Test
    void anotherItemsUuidIsNotAMatch() {
        assertThat(ImageHostingAlbumIdentity.carries("Item reference: " + UUID, LEGACY_UUID)).isFalse();
        assertThat(ImageHostingAlbumIdentity.carries("6390-1 - Main Street.", UUID)).isFalse();
        assertThat(ImageHostingAlbumIdentity.carries(null, UUID)).isFalse();
        assertThat(ImageHostingAlbumIdentity.carries(LEGACY_UUID, null)).isFalse();
    }

    @Test
    void aUuidInsideALongerValueIsNotAMatch() {
        assertThat(ImageHostingAlbumIdentity.carries("a" + LEGACY_UUID, LEGACY_UUID)).isFalse();
        assertThat(ImageHostingAlbumIdentity.carries(LEGACY_UUID + "0", LEGACY_UUID)).isFalse();
        assertThat(ImageHostingAlbumIdentity.carries(UUID + "-copy", UUID)).isFalse();
    }
}
