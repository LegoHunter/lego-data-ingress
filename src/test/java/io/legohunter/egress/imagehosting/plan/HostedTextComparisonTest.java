package io.legohunter.egress.imagehosting.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostedTextComparisonTest {

    @Test
    void aDescriptionWithAPhotoLinkEqualsFlickrsLinkedForm() {
        // As written to Flickr, and as Flickr returned it for a real SANDBOX album.
        String written = "6365-1 - Summer Cottage.\n\nPhotos: https://bit.ly/4kgbu2d\n\n"
                + "Item reference: e2e84cab-4c01-4f77-b6ca-e143e0d85a53";
        String returned = "6365-1 - Summer Cottage.\n\nPhotos: "
                + "<a href=\"https://bit.ly/4kgbu2d\" rel=\"noreferrer nofollow\">bit.ly/4kgbu2d</a>\n\n"
                + "Item reference: e2e84cab-4c01-4f77-b6ca-e143e0d85a53";

        assertThat(HostedTextComparison.sameText(written, returned)).isTrue();
    }

    @Test
    void aDifferentLinkIsStillADifference() {
        assertThat(HostedTextComparison.sameText(
                "Photos: https://bit.ly/aaa",
                "Photos: <a href=\"https://bit.ly/bbb\" rel=\"noreferrer nofollow\">bit.ly/bbb</a>"
        )).isFalse();
    }

    @Test
    void entitiesAndLineEndsDoNotCount() {
        assertThat(HostedTextComparison.sameText(
                "Box & instructions: \"very good\"\nOwner's note",
                "Box &amp; instructions: &quot;very good&quot;\r\nOwner&#39;s note "
        )).isTrue();
    }

    @Test
    void realTextDifferencesCount() {
        assertThat(HostedTextComparison.sameText("Sealed: no.", "Sealed: yes.")).isFalse();
        assertThat(HostedTextComparison.sameText("Sealed: no.", null)).isFalse();
        assertThat(HostedTextComparison.sameText(" ", null)).isTrue();
    }
}
