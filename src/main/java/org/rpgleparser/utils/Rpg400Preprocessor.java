package org.rpgleparser.utils;

/**
 * RPG/400 (RPG III) dialect support.
 *
 * DHPN-style sources carry 5-character change tags in columns 1-5
 * (e.g. " 8.23C", "11.30F", "///  I") and occasional edit markers that the
 * ILE-oriented lexer cannot handle. This class detects the RPG/400 dialect
 * and normalizes those columns away before lexing.
 *
 * Normalization rule (applied only BEFORE the first end-of-source "**" line):
 *   - if columns 1-5 of a line contain any non-blank character, replace
 *     columns 1-5 with blanks.
 * Lines from the first "**" marker onwards (end-of-source marker plus
 * compile-time data) are passed through untouched.
 */
public final class Rpg400Preprocessor {

    private Rpg400Preprocessor() {
    }

    /**
     * Heuristic dialect detection.
     * RPG/400-only signals win immediately; otherwise fall back to voting on
     * C-spec opcode geometry (RPG/400 opcodes start in column 28, ILE in 26).
     */
    public static boolean isRpg400(final String source) {
        final int len = source.length();
        int rpg400Votes = 0;
        int ileVotes = 0;
        int lineStart = 0;
        boolean endSourceSeen = false;
        while (lineStart < len && !endSourceSeen) {
            int eol = lineStart;
            while (eol < len && source.charAt(eol) != '\n' && source.charAt(eol) != '\r') {
                eol++;
            }
            final int lineLen = eol - lineStart;
            if (lineLen >= 6 && source.startsWith("**", lineStart)) {
                endSourceSeen = true;
                break;
            }
            if (lineLen >= 6) {
                final char spec = source.charAt(lineStart + 5);
                final char c1 = source.charAt(lineStart + 6);
                if (spec == 'E' && c1 != '*' && c1 != '/' && !isBlankRun(source, lineStart + 6, eol)) {
                    return true; // E-spec: RPG/400 only
                }
                if (spec == 'L' && c1 != '*' && !isBlankRun(source, lineStart + 6, eol)) {
                    return true; // L-spec: RPG/400 only
                }
                if (spec == 'C' || spec == 'c') {
                    final int vote = voteCLine(source, lineStart, eol);
                    if (vote > 0) {
                        rpg400Votes++;
                    } else if (vote < 0) {
                        ileVotes++;
                    }
                }
            }
            lineStart = eol;
            if (lineStart < len && source.charAt(lineStart) == '\r') {
                lineStart++;
            }
            if (lineStart < len && source.charAt(lineStart) == '\n') {
                lineStart++;
            }
        }
        return rpg400Votes > ileVotes;
    }

    /** +1 = opcode starts at col 28 (RPG/400), -1 = content at col 26 (ILE), 0 = no signal. */
    private static int voteCLine(final String s, final int start, final int end) {
        // ignore comment lines: '*' in col 7
        if (start + 6 < end && s.charAt(start + 6) == '*') {
            return 0;
        }
        // opcode window cols 26..32 (0-based start+25 .. start+31)
        int contentCol = -1;
        for (int col = 26; col <= 32; col++) {
            final int idx = start + col - 1;
            if (idx < end && s.charAt(idx) != ' ' && s.charAt(idx) != '\t') {
                contentCol = col;
                break;
            }
        }
        if (contentCol == 28) {
            return 1;
        }
        if (contentCol == 26) {
            return -1;
        }
        return 0;
    }

    private static boolean isBlankRun(final String s, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (s.charAt(i) != ' ' && s.charAt(i) != '\t') {
                return false;
            }
        }
        return true;
    }

    /**
     * Blanks out columns 1-5 on lines that carry non-blank content there.
     * Everything from the first "**" end-of-source marker onwards is copied
     * verbatim. Line terminators are preserved as-is.
     */
    public static String normalize(final String source) {
        final int len = source.length();
        final StringBuilder out = new StringBuilder(len + 16);
        int lineStart = 0;
        while (lineStart < len) {
            int eol = lineStart;
            while (eol < len && source.charAt(eol) != '\n' && source.charAt(eol) != '\r') {
                eol++;
            }
            final boolean endSource = source.startsWith("**", lineStart);
            if (endSource) {
                out.append(source, lineStart, len);
                break;
            }
            int firstNonBlank = -1;
            final int max = Math.min(eol, lineStart + 5);
            for (int i = lineStart; i < max; i++) {
                if (source.charAt(i) != ' ' && source.charAt(i) != '\t') {
                    firstNonBlank = i;
                    break;
                }
            }
            if (firstNonBlank >= 0) {
                out.append("     ");
                if (eol > lineStart + 5) {
                    out.append(source, lineStart + 5, eol);
                }
            } else {
                out.append(source, lineStart, eol);
            }
            // copy terminator verbatim
            if (eol < len) {
                out.append(source.charAt(eol));
                if (source.charAt(eol) == '\r' && eol + 1 < len && source.charAt(eol + 1) == '\n') {
                    out.append('\n');
                    eol++;
                }
            }
            lineStart = eol + 1;
        }
        return out.toString();
    }
}
