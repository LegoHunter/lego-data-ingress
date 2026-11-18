package io.legohunter.egress.imagehosting.plan;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Compares a title or description the system wants with the one the hosting provider returns.
 *
 * <p>Flickr does not return a description as it was written: a web address comes back as an HTML link
 * ({@code <a href="https://bit.ly/x" rel="noreferrer nofollow">bit.ly/x</a>}) and some characters come back as
 * HTML entities. Compared as plain strings, a description with a photo link would differ from Flickr's on every
 * run and be rewritten forever. Both sides are therefore reduced to the same plain form first.
 */
public final class HostedTextComparison {
    private static final Pattern LINK = Pattern.compile(
            "<a\\s[^>]*?href\\s*=\\s*\"([^\"]*)\"[^>]*>.*?</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private HostedTextComparison() {
    }

    public static boolean sameText(String left, String right) {
        return Objects.equals(plain(left), plain(right));
    }

    /** The text with links reduced to their address, entities decoded, line ends unified and ends trimmed. */
    public static String plain(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = LINK.matcher(value).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(match.group(1)));
        return text
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("\r\n", "\n")
                .trim();
    }
}
