package io.legohunter.ingress.source.photo.service;

import java.util.List;
import java.util.Objects;

/**
 * The owner's rules for the {@code sealed} and {@code bo} (built once) photo keywords (HOMELAB-96), applied across
 * every photo of one inventory item. The photos win over what was entered at intake.
 *
 * <p>A keyword value is {@code true}, {@code false} or {@code null} (the photo has no such keyword). The bare word
 * {@code sealed} is {@code sealed:true}.
 * <ul>
 *   <li>Any photo sealed, no photo built once: the item is sealed and New; built once is false.</li>
 *   <li>No photo sealed: {@code bo:true} means Used, {@code bo:false} means New.</li>
 *   <li>{@code sealed:false} does not count as sealed, so it may sit beside {@code bo:true}.</li>
 *   <li>Sealed on any photo with {@code bo:true} on any photo (the same one included) is a conflict.</li>
 *   <li>Two photos that disagree on a keyword ({@code bo:true} and {@code bo:false}, or {@code sealed:true} and
 *       {@code sealed:false}) are a conflict.</li>
 *   <li>No sealed and no built-once keyword on any photo: nothing changes.</li>
 * </ul>
 * Photos arrive one at a time, so a conflict rejects the photo being processed: the one that arrived second. A
 * re-uploaded photo is judged against the item's other photos only, never against its own earlier values.
 *
 * <p>A pure function: no I/O, so it is evaluated once before the upload and again under the item's row lock.
 */
final class PhotoKeywordRules {
    static final String NEW = "N";
    static final String USED = "U";

    private PhotoKeywordRules() {
    }

    /** What one of the item's other photos said. */
    record OtherPhoto(String fileName, Boolean sealedKeyword, Boolean builtOnceKeyword) {
    }

    /**
     * The values to set on the item; a null field means "leave it as it is". {@code rejection} is set instead when
     * the photo conflicts with the item's other photos.
     */
    record Outcome(Boolean sealed, Boolean builtOnce, String newOrUsed, String rejection) {
        boolean rejected() {
            return rejection != null;
        }

        private static Outcome reject(String reason) {
            return new Outcome(null, null, null, reason);
        }
    }

    static Outcome evaluate(Boolean sealedKeyword, Boolean builtOnceKeyword, List<OtherPhoto> otherPhotos) {
        List<OtherPhoto> others = otherPhotos == null ? List.of() : otherPhotos;
        boolean thisSealed = Boolean.TRUE.equals(sealedKeyword);
        boolean thisBuiltOnce = Boolean.TRUE.equals(builtOnceKeyword);

        if (thisSealed && thisBuiltOnce) {
            return Outcome.reject("the photo is tagged both sealed and bo:true; a sealed set cannot have been built");
        }
        if (thisSealed) {
            String builtOncePhoto = firstWith(others, true, false);
            if (builtOncePhoto != null) {
                return Outcome.reject("the photo is tagged sealed, but photo [%s] of the same item is tagged bo:true"
                        .formatted(builtOncePhoto));
            }
        }
        if (thisBuiltOnce) {
            String sealedPhoto = firstWith(others, true, true);
            if (sealedPhoto != null) {
                return Outcome.reject("the photo is tagged bo:true, but photo [%s] of the same item is tagged sealed"
                        .formatted(sealedPhoto));
            }
        }
        if (builtOnceKeyword != null) {
            String disagreeing = firstWith(others, !builtOnceKeyword, false);
            if (disagreeing != null) {
                return Outcome.reject("the photo is tagged bo:%s, but photo [%s] of the same item is tagged bo:%s"
                        .formatted(builtOnceKeyword, disagreeing, !builtOnceKeyword));
            }
        }
        if (sealedKeyword != null) {
            String disagreeing = firstWith(others, !sealedKeyword, true);
            if (disagreeing != null) {
                return Outcome.reject("the photo is tagged sealed:%s, but photo [%s] of the same item is tagged sealed:%s"
                        .formatted(sealedKeyword, disagreeing, !sealedKeyword));
            }
        }

        boolean anySealed = thisSealed || firstWith(others, true, true) != null;
        if (anySealed) {
            return new Outcome(true, false, NEW, null);
        }
        boolean explicitlyUnsealed = Boolean.FALSE.equals(sealedKeyword) || firstWith(others, false, true) != null;
        Boolean builtOnce = builtOnceKeyword != null ? builtOnceKeyword
                : firstWith(others, true, false) != null ? Boolean.TRUE
                : firstWith(others, false, false) != null ? Boolean.FALSE
                : null;
        return new Outcome(
                explicitlyUnsealed ? Boolean.FALSE : null,
                builtOnce,
                builtOnce == null ? null : builtOnce ? USED : NEW,
                null
        );
    }

    /** The file name of the first other photo whose sealed (or built-once) keyword has this value, or null. */
    private static String firstWith(List<OtherPhoto> others, boolean value, boolean sealed) {
        return others.stream()
                .filter(other -> Objects.equals(sealed ? other.sealedKeyword() : other.builtOnceKeyword(), value))
                .map(other -> other.fileName() == null ? "unnamed" : other.fileName())
                .findFirst()
                .orElse(null);
    }
}
