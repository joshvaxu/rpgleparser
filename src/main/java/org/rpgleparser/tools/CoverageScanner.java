package org.rpgleparser.tools;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.tree.ParseTree;
import org.rpgleparser.RpgLexer;
import org.rpgleparser.RpgParser;
import org.rpgleparser.tokens.PreprocessTokenSource;
import org.rpgleparser.utils.FixedWidthBufferedReader;
import org.rpgleparser.utils.Rpg400Preprocessor;

/**
 * Batch coverage scanner: parses every RPG source file under a root directory
 * and reports parse success/failure statistics, to measure how well the
 * rpgleparser grammar covers a real codebase before building a Semantic IR.
 *
 * Usage: java org.rpgleparser.tools.CoverageScanner <rootDir> [-o report.json]
 *        [--timeout seconds] [--ext rpgle,sqlrpgle,...]
 */
public class CoverageScanner {

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    private static class ErrorRecord {
        final String stage;
        final int line;
        final int col;
        final String message;
        final String token;

        ErrorRecord(final String stage, final int line, final int col, final String message) {
            this.stage = stage;
            this.line = line;
            this.col = col;
            this.message = message;
            final Matcher m = QUOTED.matcher(message);
            this.token = m.find() ? m.group(1) : "";
        }
    }

    private static class FileResult {
        String path;
        int lines;
        long bytes;
        String status; // CLEAN | SYNTAX_ERRORS | LEXER_ERRORS | MIXED_ERRORS | TIMEOUT | CRASH
        long millis;
        final List<ErrorRecord> errors = new ArrayList<>();
    }

    private static class CollectionListener implements org.antlr.v4.runtime.ANTLRErrorListener {
        final List<ErrorRecord> sink;
        final String stage;

        CollectionListener(final List<ErrorRecord> sink, final String stage) {
            this.sink = sink;
            this.stage = stage;
        }

        @Override
        public void syntaxError(final Recognizer<?, ?> recognizer, final Object offendingSymbol, final int line,
                final int charPositionInLine, final String msg, final RecognitionException e) {
            sink.add(new ErrorRecord(stage, line, charPositionInLine, msg));
        }

        @Override
        public void reportAmbiguity(final Parser ignored, final DFA dfa, final int startIndex, final int stopIndex,
                final boolean exact, final java.util.BitSet ambigAlts, final org.antlr.v4.runtime.atn.ATNConfigSet configs) {
        }

        @Override
        public void reportAttemptingFullContext(final Parser ignored, final DFA dfa, final int startIndex,
                final int stopIndex, final java.util.BitSet conflictingAlts,
                final org.antlr.v4.runtime.atn.ATNConfigSet configs) {
        }

        @Override
        public void reportContextSensitivity(final Parser ignored, final DFA dfa, final int startIndex,
                final int stopIndex, final int prediction, final org.antlr.v4.runtime.atn.ATNConfigSet configs) {
        }
    }

    private final int timeoutSeconds;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2), r -> {
                final Thread t = new Thread(r, "rpg-parse");
                t.setDaemon(true);
                return t;
            });

    CoverageScanner(final int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: CoverageScanner <rootDir> [-o report.json] [--timeout sec] "
                    + "[--ext rpgle,sqlrpgle,...]");
            System.exit(2);
        }
        File root = new File(args[0]);
        File reportOut = new File("coverage-report.json");
        int timeoutSeconds = 60;
        Set<String> exts = new java.util.HashSet<>(java.util.Arrays.asList("rpgle", "sqlrpgle", "rpg", "rpg4", "mbr"));
        for (int i = 1; i < args.length; i++) {
            if ("-o".equals(args[i]) && i + 1 < args.length) {
                reportOut = new File(args[++i]);
            } else if ("--timeout".equals(args[i]) && i + 1 < args.length) {
                timeoutSeconds = Integer.parseInt(args[++i]);
            } else if ("--ext".equals(args[i]) && i + 1 < args.length) {
                exts = new java.util.HashSet<>(java.util.Arrays.asList(args[++i].toLowerCase().split(",")));
            }
        }
        final List<File> files = new ArrayList<>();
        collect(root, exts, files, 0);
        Collections.sort(files);
        System.out.println("Found " + files.size() + " source files under " + root.getAbsolutePath());

        final CoverageScanner scanner = new CoverageScanner(timeoutSeconds);
        final List<FileResult> results = new ArrayList<>();
        int done = 0;
        for (final File f : files) {
            results.add(scanner.scan(f));
            done++;
            if (done % 25 == 0 || done == files.size()) {
                System.out.println("Processed " + done + "/" + files.size());
            }
        }
        scanner.executor.shutdownNow();
        printSummary(results, root);
        writeReport(results, reportOut);
        System.out.println("Report written to " + reportOut.getAbsolutePath());
    }

    private static void collect(final File f, final Set<String> exts, final List<File> out, final int depth) {
        if (f == null || depth > 32) {
            return;
        }
        if (f.isDirectory()) {
            final File[] children = f.listFiles();
            if (children != null) {
                for (final File c : children) {
                    collect(c, exts, out, depth + 1);
                }
            }
        } else {
            final String name = f.getName().toLowerCase();
            final int dot = name.lastIndexOf('.');
            if (dot >= 0 && exts.contains(name.substring(dot + 1))) {
                out.add(f);
            }
        }
    }

    private FileResult scan(final File file) {
        final FileResult result = new FileResult();
        result.path = file.getAbsolutePath();
        result.bytes = file.length();
        String source;
        try {
            source = loadFile(file);
        } catch (final IOException e) {
            result.status = "CRASH";
            result.errors.add(new ErrorRecord("read", 0, 0, "read failed: " + e.getMessage()));
            return result;
        }
        result.lines = countLines(source);

        final boolean rpg400 = Rpg400Preprocessor.isRpg400(source);
        if (rpg400) {
            source = Rpg400Preprocessor.normalize(source);
        }

        final long t0 = System.currentTimeMillis();
        ParseOutcome outcome = runParse(source, file, PredictionMode.SLL, result, rpg400);
        if (outcome.timedOut) {
            // retry LL once; if LL also times out, report TIMEOUT
            final ParseOutcome ll = runParse(source, file, PredictionMode.LL, result, rpg400);
            result.millis = System.currentTimeMillis() - t0;
            finish(result, ll);
            return result;
        }
        if (outcome.errors.isEmpty()) {
            result.status = "CLEAN";
            result.millis = System.currentTimeMillis() - t0;
            return result;
        }
        // SLL reported errors: retry with LL for accurate diagnostics
        final ParseOutcome ll = runParse(source, file, PredictionMode.LL, result, rpg400);
        result.millis = System.currentTimeMillis() - t0;
        finish(result, ll);
        return result;
    }

    private void finish(final FileResult result, final ParseOutcome outcome) {
        if (outcome.timedOut) {
            result.status = "TIMEOUT";
        } else if (outcome.crash != null) {
            result.status = "CRASH";
            result.errors.add(new ErrorRecord("crash", 0, 0, outcome.crash));
        } else if (outcome.errors.isEmpty()) {
            result.status = "CLEAN";
        } else {
            boolean lex = false;
            boolean syn = false;
            for (final ErrorRecord e : outcome.errors) {
                if ("lexer".equals(e.stage)) {
                    lex = true;
                } else {
                    syn = true;
                }
            }
            result.status = lex && syn ? "MIXED_ERRORS" : lex ? "LEXER_ERRORS" : "SYNTAX_ERRORS";
            result.errors.addAll(outcome.errors);
        }
    }

    private static class ParseOutcome {
        boolean timedOut;
        String crash;
        final List<ErrorRecord> errors = new ArrayList<>();
    }

    private ParseOutcome runParse(final String source, final File file, final PredictionMode mode,
            final FileResult resultHolder, final boolean rpg400) {
        final ParseOutcome outcome = new ParseOutcome();
        final Future<ParseOutcome> future = executor.submit(() -> {
            try {
                final ANTLRInputStream input = new ANTLRInputStream(
                        new FixedWidthBufferedReader(new StringReader(source)));
                final RpgLexer rpgLexer = new RpgLexer(input);
                rpgLexer.rpg400 = rpg400;
                rpgLexer.removeErrorListeners();
                rpgLexer.addErrorListener(new CollectionListener(outcome.errors, "lexer"));
                final TokenSource pre = new PreprocessTokenSource(rpgLexer,
                        new PreprocessTokenSource.FileFolderCopyBookProvider(file));
                final CommonTokenStream tokens = new CommonTokenStream(pre);
                final RpgParser parser = new RpgParser(tokens);
                parser.removeErrorListeners();
                parser.addErrorListener(new CollectionListener(outcome.errors, "parser"));
                parser.getInterpreter().setPredictionMode(mode);
                final ParseTree tree = parser.r();
                return outcome;
            } catch (final Throwable t) {
                outcome.crash = t.getClass().getSimpleName() + ": " + t.getMessage();
                return outcome;
            }
        });
        try {
            return future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            future.cancel(true);
            outcome.timedOut = true;
            return outcome;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome.crash = "interrupted";
            return outcome;
        } catch (final ExecutionException e) {
            outcome.crash = String.valueOf(e.getCause());
            return outcome;
        }
    }

    private static String loadFile(final File file) throws IOException {
        final byte[] bytes = Files.readAllBytes(file.toPath());
        String text = new String(bytes, Charset.forName("UTF-8"));
        if (text.contains("\uFFFD")) { // decode fallback for non-UTF8 (CCSID 5035 etc.)
            text = new String(bytes, Charset.forName("ISO-8859-1"));
        }
        return text;
    }

    private static int countLines(final String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static void printSummary(final List<FileResult> results, final File root) {
        final Map<String, Integer> byStatus = new LinkedHashMap<>();
        long totalLines = 0;
        long totalBytes = 0;
        final Map<String, Integer> tokenHits = new HashMap<>();
        final Map<String, Integer> patternHits = new HashMap<>();
        final List<FileResult> failing = new ArrayList<>();
        for (final FileResult r : results) {
            byStatus.merge(r.status, 1, Integer::sum);
            totalLines += r.lines;
            totalBytes += r.bytes;
            if (!"CLEAN".equals(r.status)) {
                failing.add(r);
            }
            for (final ErrorRecord e : r.errors) {
                if (!e.token.isEmpty() && e.token.matches("[A-Za-z][A-Za-z0-9_#@$\\-]*")
                        && e.token.length() >= 2) {
                    tokenHits.merge(e.token.toUpperCase(), 1, Integer::sum);
                }
                final String pattern = e.message.replaceAll("'[^']*'", "'...'");
                patternHits.merge(pattern.length() > 120 ? pattern.substring(0, 120) + "..." : pattern, 1,
                        Integer::sum);
            }
        }
        System.out.println();
        System.out.println("================ COVERAGE SUMMARY ================");
        System.out.println("Files: " + results.size() + "  Lines: " + totalLines + "  Bytes: " + totalBytes);
        System.out.println("-- by status --");
        byStatus.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.println(String.format("  %-14s %5d  (%.1f%%)", e.getKey(), e.getValue(),
                        100.0 * e.getValue() / Math.max(1, results.size()))));
        final long clean = byStatus.getOrDefault("CLEAN", 0);
        System.out.println(String.format("Parse-clean rate: %.1f%%",
                100.0 * clean / Math.max(1, results.size())));
        System.out.println("-- top offending tokens (possible unsupported features) --");
        tokenHits.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(20)
                .forEach(e -> System.out.println(String.format("  %6d  %s", e.getValue(), e.getKey())));
        System.out.println("-- top error patterns --");
        patternHits.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .forEach(e -> System.out.println(String.format("  %6d  %s", e.getValue(), e.getKey())));
        Collections.sort(failing, Comparator.comparing(f -> f.path));
        System.out.println("-- failing files (first 50) --");
        failing.stream().limit(50).forEach(f -> System.out.println(
                "  [" + f.status + "] " + root.toPath().relativize(java.nio.file.Paths.get(f.path))));
    }

    private static String json(final String s) {
        if (s == null) {
            return "\"\"";
        }
        final StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static void writeReport(final List<FileResult> results, final File out) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"generatedAt\": ").append(json(new java.util.Date().toString()));
        sb.append(",\n  \"files\": [\n");
        boolean first = true;
        for (final FileResult r : results) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("    {\"path\": ").append(json(r.path));
            sb.append(", \"lines\": ").append(r.lines);
            sb.append(", \"bytes\": ").append(r.bytes);
            sb.append(", \"status\": ").append(json(r.status));
            sb.append(", \"millis\": ").append(r.millis);
            sb.append(", \"errors\": [");
            boolean fe = true;
            for (final ErrorRecord e : r.errors) {
                if (!fe) {
                    sb.append(", ");
                }
                fe = false;
                sb.append("{\"stage\": ").append(json(e.stage));
                sb.append(", \"line\": ").append(e.line);
                sb.append(", \"col\": ").append(e.col);
                sb.append(", \"token\": ").append(json(e.token));
                sb.append(", \"message\": ").append(json(e.message)).append("}");
            }
            sb.append("]}");
        }
        sb.append("\n  ]\n}\n");
        Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
