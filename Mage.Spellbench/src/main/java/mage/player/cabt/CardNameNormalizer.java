package mage.player.cabt;

/**
 * Normalizes an externally-sourced card name into the ASCII spelling XMage's
 * {@code CardRepository} stores under. The repository matches names by exact
 * SQL equality, so cosmetic differences that are common in decklist exports —
 * curly apostrophes, typographic quotes, en/em dashes, non-breaking or
 * doubled whitespace, and Arena's {@code " /// "} split separator — must be
 * folded to their canonical form <em>before</em> lookup or an otherwise valid
 * card is reported unknown.
 * <p>
 * Normalization is deliberately conservative: it only touches characters that
 * are visually equivalent to their ASCII counterparts. It never changes
 * letters or case, so it can never turn one real card name into a different
 * real card name.
 */
final class CardNameNormalizer {

    private CardNameNormalizer() {
    }

    static String normalize(String rawName) {
        if (rawName == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(rawName.length());
        for (int i = 0; i < rawName.length(); i++) {
            char c = rawName.charAt(i);
            switch (c) {
                // apostrophe variants -> ASCII apostrophe
                case '’': // right single quotation mark
                case '‘': // left single quotation mark
                case 'ʼ': // modifier letter apostrophe
                case '′': // prime
                case '´': // acute accent used as apostrophe
                case '`':      // grave accent used as apostrophe
                    builder.append('\'');
                    break;
                // double-quote variants -> ASCII quote
                case '“': // left double quotation mark
                case '”': // right double quotation mark
                    builder.append('"');
                    break;
                // dash variants -> ASCII hyphen-minus
                case '‐': // hyphen
                case '‑': // non-breaking hyphen
                case '‒': // figure dash
                case '–': // en dash
                case '—': // em dash
                case '−': // minus sign
                    builder.append('-');
                    break;
                // whitespace variants -> plain space (collapsed below)
                case ' ': // non-breaking space
                case '\t':
                case '\r':
                case '\n':
                    builder.append(' ');
                    break;
                default:
                    builder.append(c);
                    break;
            }
        }
        // Arena writes split/adventure names with a triple slash; XMage uses
        // " // ". Fold triple-or-more slashes to the two-slash separator.
        String folded = builder.toString().replaceAll("/{3,}", "//");
        // collapse any run of spaces to a single space and trim the ends
        return folded.replaceAll("\\s+", " ").trim();
    }
}
