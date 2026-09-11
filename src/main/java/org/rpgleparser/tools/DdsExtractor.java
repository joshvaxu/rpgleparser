package org.rpgleparser.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DDS source extractor for physical/logical file definitions (A-spec format).
 * Emits a JSON schema IR per DDS file: record formats, fields (name, type,
 * length, decimals, COLHDG text), key fields, select/omit criteria and all
 * keywords. Pure columnar parsing - no ANTLR involved.
 *
 * Usage: java org.rpgleparser.tools.DdsExtractor <rootDir> -o ir-dds.json
 *        [--charset Shift_JIS] [--ext TXT,...]
 */
public class DdsExtractor {

    static class DdsResult {
        String path;
        String name;
        int lines;
        String status;
        final List<FactExtractor.Fact> records = new ArrayList<>();
        final List<FactExtractor.Fact> criteria = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    private static final Set<String> FUNC_CHARS = new java.util.HashSet<>(Arrays.asList("R", "K", "S", "O", "J", "P", ""));

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: DdsExtractor <rootDir> -o ir-dds.json [--charset Shift_JIS] [--ext TXT,txt]");
            System.exit(2);
        }
        File root = new File(args[0]);
        File out = new File("ir-dds.json");
        String charset = "Shift_JIS";
        Set<String> exts = new java.util.HashSet<>(Arrays.asList("txt", "dds", "pf", "lf"));
        for (int i = 1; i < args.length; i++) {
            if ("-o".equals(args[i]) && i + 1 < args.length) {
                out = new File(args[++i]);
            } else if ("--charset".equals(args[i]) && i + 1 < args.length) {
                charset = args[++i];
            } else if ("--ext".equals(args[i]) && i + 1 < args.length) {
                exts = new java.util.HashSet<>(Arrays.asList(args[++i].toLowerCase().split(",")));
            }
        }
        final List<File> files = new ArrayList<>();
        collect(root, exts, files, 0);
        Collections.sort(files);
        System.out.println("Extracting " + files.size() + " DDS files from " + root.getAbsolutePath());
        final List<DdsResult> results = new ArrayList<>();
        int done = 0;
        for (final File f : files) {
            results.add(extract(f));
            done++;
            if (done % 10 == 0 || done == files.size()) {
                System.out.println("Processed " + done + "/" + files.size());
            }
        }
        printSummary(results);
        writeJson(results, out);
        System.out.println("DDS IR written to " + out.getAbsolutePath());
    }

    private static DdsResult extract(final File file) {
        final DdsResult r = new DdsResult();
        r.path = file.getAbsolutePath();
        r.name = file.getName().replaceFirst("\\.[^.]+$", "").toUpperCase();
        final String text;
        try {
            text = loadFile(file);
        } catch (final IOException e) {
            r.status = "READ_FAIL: " + e.getMessage();
            return r;
        }
        r.lines = 1 + countOf(text, '\n');
        try {
            parseDds(text, r);
            r.status = "OK";
        } catch (final Exception e) {
            r.status = "FAIL: " + e;
        }
        return r;
    }

    private static void parseDds(final String text, final DdsResult r) {
        FactExtractor.Fact currentRecord = null;
        String[] lines = text.split("\n");
        for (int ln = 0; ln < lines.length; ln++) {
            final String raw = lines[ln].replace("\r", "");
            if (raw.length() < 7 || raw.charAt(5) != 'A') {
                continue;
            }
            final String line = raw;
            final char func = line.length() > 16 ? line.charAt(16) : ' ';
            final String name = line.length() > 28 ? line.substring(18, 28).trim() : "";
            final String tail = line.length() > 44 ? line.substring(44) : "";
            final String body = line.length() > 18 ? line.substring(18) : "";

            if (func == 'R') {
                final FactExtractor.Fact rec = new FactExtractor.Fact().put("record", name).put("line", ln + 1);
                rec.put("text", firstQuoted(tail, "TEXT"));
                rec.put("keywords", tail.trim());
                final java.util.regex.Matcher pf =
                        java.util.regex.Pattern.compile("(?i)(PFILE|JFILE)\\(([^)]+)\\)").matcher(tail);
                if (pf.find()) {
                    rec.put(pf.group(1).toLowerCase().replace("file", "file"),
                            pf.group(2).trim().toUpperCase());
                }
                r.records.add(rec);
                currentRecord = rec;
                rec.m.put("_keys", new ArrayList<String>());
            } else if (func == 'K' && currentRecord != null && !name.isEmpty()) {
                @SuppressWarnings("unchecked")
                final List<String> keys = (List<String>) currentRecord.m.get("_keys");
                keys.add(name);
            } else if (func == ' ' && !name.isEmpty() && currentRecord != null) {
                // field definition: length/type/decimals around cols 30-44 (files drift +-2 cols)
                final String lenZone = line.length() > 44 ? line.substring(29, 44) : "";
                String len = null;
                String type = null;
                String dec = null;
                final java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("^\\s*(\\d{1,5})\\s*([APSBFGIJLTVZWH])(?:\\s+(\\d{1,3}))?\\s*$")
                                .matcher(lenZone);
                if (m.find()) {
                    len = m.group(1);
                    type = m.group(2);
                    dec = m.group(3);
                }
                final FactExtractor.Fact fld = new FactExtractor.Fact().put("field", name).put("line", ln + 1);
                fld.put("type", type);
                fld.put("len", len);
                fld.put("dec", dec);
                final String colhdg = firstQuoted(tail, "COLHDG");
                if (colhdg != null) {
                    fld.put("colhdg", colhdg);
                }
                fld.put("keywords", tail.trim());
                @SuppressWarnings("unchecked")
                final List<Object> fields = (List<Object>) currentRecord.m.computeIfAbsent("fields",
                        k -> new ArrayList<Object>());
                ((List<Object>) fields).add(fld);
            } else if (func == 'S' || func == 'O') {
                final FactExtractor.Fact c = new FactExtractor.Fact().put("kind", func == 'S' ? "select" : "omit").put("line", ln + 1)
                        .put("text", tail.trim());
                r.criteria.add(c);
            } else if (func == 'J' || func == 'P') {
                final FactExtractor.Fact c = new FactExtractor.Fact().put("kind", func == 'J' ? "join" : "fldref").put("line", ln + 1)
                        .put("text", tail.trim());
                r.criteria.add(c);
            }
        }
    }

    private static String firstQuoted(final String s, final String kw) {
        if (s == null) {
            return null;
        }
        final int i = s.indexOf(kw);
        if (i < 0) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([^']*)'").matcher(s.substring(i));
        while (m.find()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(m.group(1).trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String loadFile(final File file) throws IOException {
        final byte[] bytes = Files.readAllBytes(file.toPath());
        boolean highBytes = false;
        for (final byte b : bytes) {
            if ((b & 0x80) != 0) {
                highBytes = true;
                break;
            }
        }
        if (highBytes) {
            // Japanese shop sources: SJIS/CP943; stray 0x85 bytes (NEL artifacts)
            // may decode as U+FFFD but the rest is fine - do not fall back
            return new String(bytes, Charset.forName("Windows-31j")).replace("\u0085", "\n");
        }
        String t = new String(bytes, StandardCharsets.UTF_8);
        if (t.contains("\uFFFD")) {
            t = new String(bytes, Charset.forName("ISO-8859-1"));
        }
        return t;
    }

    private static int countOf(final String s, final char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static void collect(final File f, final Set<String> exts, final List<File> out, final int depth) {
        if (f == null || depth > 32) {
            return;
        }
        if (f.isDirectory()) {
            final File[] ch = f.listFiles();
            if (ch != null) {
                for (final File c : ch) {
                    collect(c, exts, out, depth + 1);
                }
            }
        } else {
            final String n = f.getName().toLowerCase();
            final int dot = n.lastIndexOf('.');
            if (dot >= 0 && exts.contains(n.substring(dot + 1))) {
                out.add(f);
            }
        }
    }

    private static void printSummary(final List<DdsResult> results) {
        System.out.println();
        System.out.println("================ DDS EXTRACTION SUMMARY ================");
        long nRec = 0;
        long nFld = 0;
        long nKey = 0;
        long nCrit = 0;
        for (final DdsResult r : results) {
            long k = 0;
            long fl = 0;
            for (final FactExtractor.Fact rec : r.records) {
                final Object keys = rec.m.get("_keys");
                if (keys instanceof List) {
                    k += ((List<?>) keys).size();
                }
                final Object fs = rec.m.get("fields");
                if (fs instanceof List) {
                    fl += ((List<?>) fs).size();
                }
            }
            nRec += r.records.size();
            nFld += fl;
            nKey += k;
            nCrit += r.criteria.size();
            System.out.println(String.format("  %-16s %-8s rec=%-3d fields=%-4d keys=%-4d sel/omit=%-3d",
                    r.name, r.status, r.records.size(), fl, k, r.criteria.size()));
        }
        System.out.println("  TOTAL: records=" + nRec + " fields=" + nFld + " keys=" + nKey + " criteria=" + nCrit);
    }

    private static void writeJson(final List<DdsResult> results, final File out) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\"ddsFiles\": [\n");
        boolean first = true;
        for (final DdsResult r : results) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  {\"file\": ").append(FactExtractor.J(r.name)).append(", \"path\": ")
                    .append(FactExtractor.J(r.path));
            sb.append(", \"lines\": ").append(r.lines).append(", \"status\": ").append(FactExtractor.J(r.status));
            sb.append(", \"records\": [");
            boolean fr = true;
            for (final FactExtractor.Fact rec : r.records) {
                if (!fr) {
                    sb.append(", ");
                }
                fr = false;
                sb.append("{");
                boolean fp = true;
                for (final Map.Entry<String, Object> e : rec.m.entrySet()) {
                    if (!fp) {
                        sb.append(", ");
                    }
                    fp = false;
                    sb.append(FactExtractor.J(e.getKey())).append(": ");
                    writeValue(sb, e.getValue());
                }
                sb.append("}");
            }
            sb.append("]");
            sb.append(", \"criteria\": ").append(FactExtractor.factArr(r.criteria));
            sb.append(", \"errors\": ").append(FactExtractor.strArr(r.errors)).append("}");
        }
        sb.append("\n]}\n");
        Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(final StringBuilder sb, final Object v) {
        if (v instanceof List) {
            sb.append("[");
            final List<Object> l = (List<Object>) v;
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                final Object o = l.get(i);
                if (o instanceof FactExtractor.Fact) {
                    sb.append("{");
                    boolean fp = true;
                    for (final Map.Entry<String, Object> e : ((FactExtractor.Fact) o).m.entrySet()) {
                        if (!fp) {
                            sb.append(", ");
                        }
                        fp = false;
                        sb.append(FactExtractor.J(e.getKey())).append(": ");
                        writeValue(sb, e.getValue());
                    }
                    sb.append("}");
                } else if (o instanceof Integer) {
                    sb.append(o);
                } else {
                    sb.append(FactExtractor.J(String.valueOf(o)));
                }
            }
            sb.append("]");
        } else if (v instanceof Integer) {
            sb.append(v);
        } else {
            sb.append(FactExtractor.J(String.valueOf(v)));
        }
    }
}
