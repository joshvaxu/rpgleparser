package org.rpgleparser.tools;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
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
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.rpgleparser.RpgLexer;
import org.rpgleparser.RpgParser;
import org.rpgleparser.RpgParserBaseVisitor;
import org.rpgleparser.RpgParser.Cspec_fixedContext;
import org.rpgleparser.RpgParser.Cspec_fixed_sqlContext;
import org.rpgleparser.RpgParser.Cspec_fixed_standardContext;
import org.rpgleparser.RpgParser.Cspec_fixed_standard_partsContext;
import org.rpgleparser.RpgParser.Cspec_fixed_x2Context;
import org.rpgleparser.RpgParser.Dcl_cContext;
import org.rpgleparser.RpgParser.Dds_specContext;
import org.rpgleparser.RpgParser.Dcl_dsContext;
import org.rpgleparser.RpgParser.Dcl_ds_fieldContext;
import org.rpgleparser.RpgParser.Dcl_piContext;
import org.rpgleparser.RpgParser.Dcl_prContext;
import org.rpgleparser.RpgParser.DspecContext;
import org.rpgleparser.RpgParser.Dspec_fixedContext;
import org.rpgleparser.RpgParser.Exec_sqlContext;
import org.rpgleparser.RpgParser.FspecContext;
import org.rpgleparser.RpgParser.Fspec_fixedContext;
import org.rpgleparser.RpgParser.Fspec_fixed_r400Context;
import org.rpgleparser.RpgParser.Ispec_fixed_r400Context;
import org.rpgleparser.RpgParser.FreeContext;
import org.rpgleparser.RpgParser.FreeBEGSRContext;
import org.rpgleparser.RpgParser.FreeBeginProcedureContext;
import org.rpgleparser.RpgParser.FreeENDSRContext;
import org.rpgleparser.RpgParser.FreeEndProcedureContext;
import org.rpgleparser.RpgParser.Parm_fixedContext;
import org.rpgleparser.tokens.PreprocessTokenSource;
import org.rpgleparser.utils.FixedWidthBufferedReader;
import org.rpgleparser.utils.Rpg400Preprocessor;

/**
 * ILE RPG fact extractor: walks the ANTLR parse tree and emits a JSON
 * "Semantic IR" fact sheet per program: files (F-spec), data declarations
 * (D-spec / dcl-*), procedures & subroutines, call relations
 * (CALL/CALLB/CALLP/EXSR) and embedded SQL (EXEC SQL).
 *
 * Usage: java org.rpgleparser.tools.FactExtractor <rootDir> -o ir.json
 */
public class FactExtractor {

    // ---------- data model ----------

    static class Fact {
        final Map<String, Object> m = new LinkedHashMap<>();

        Fact put(final String k, final Object v) {
            if (v != null && !(v instanceof String && ((String) v).isEmpty())) {
                m.put(k, v);
            }
            return this;
        }
    }

    static class ProgramResult {
        String path;
        String program;
        int lines;
        String status;
        long millis;
        final List<Fact> files = new ArrayList<>();
        final List<Fact> declarations = new ArrayList<>();
        final List<Fact> procedures = new ArrayList<>();
        final List<Fact> calls = new ArrayList<>();
        final List<Fact> plists = new ArrayList<>();
        final List<Fact> sql = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    static org.antlr.v4.runtime.Vocabulary VOCAB;

    // ---------- visitor ----------

    static class Extractor extends RpgParserBaseVisitor<Void> {

        final ProgramResult out;
        final Set<String> subroutines = new HashSet<>();
        final Deque<Fact> openSubs = new ArrayDeque<>();
        final Deque<Fact> openProcs = new ArrayDeque<>();

        Extractor(final ProgramResult out) {
            this.out = out;
        }

        // ----- files -----

        @Override
        public Void visitFspec_fixed(final Fspec_fixedContext ctx) {
            final Fact f = new Fact().put("spec", "fixed").put("line", ctx.getStart().getLine());
            f.put("name", trim(ctx.FS_RecordName()));
            f.put("type", trim(ctx.FS_Type()));
            f.put("designation", trim(ctx.FS_Designation()));
            f.put("format", trim(ctx.FS_Format()));
            f.put("device", trim(ctx.FS_Device()));
            f.put("keywords", keywordText(ctx));
            out.files.add(f);
            return null;
        }

        @Override
        public Void visitFspec(final FspecContext ctx) {
            final Fact f = new Fact().put("spec", "free").put("line", ctx.getStart().getLine());
            if (ctx.filename() != null && ctx.filename().ID() != null) {
                f.put("name", ctx.filename().ID().getText());
            }
            f.put("keywords", keywordText(ctx));
            out.files.add(f);
            return null;
        }

        // ----- declarations -----

        @Override
        public Void visitDspec_fixed(final Dspec_fixedContext ctx) {
            addFixedDecl(ctx, "ds_or_field");
            return null;
        }

        @Override
        public Void visitParm_fixed(final Parm_fixedContext ctx) {
            addFixedDecl(ctx, "parm");
            return null;
        }

        private void addFixedDecl(final ParserRuleContext ctx, final String kind) {
            final Fact d = new Fact().put("kind", kind).put("line", ctx.getStart().getLine());
            final StringBuilder kw = new StringBuilder();
            for (final Token t : visible(ctx)) {
                final String sym = sym(t);
                if (sym == null) {
                    continue;
                }
                switch (sym) {
                    case "NAME":
                        if (d.m.get("name") == null) {
                            d.put("name", t.getText().trim());
                        }
                        break;
                    case "DATA_TYPE":
                        d.put("dataType", t.getText().trim());
                        break;
                    case "FROM_POSITION":
                        d.put("from", t.getText().trim());
                        break;
                    case "TO_POSITION":
                        d.put("to", t.getText().trim());
                        break;
                    case "DECIMAL_POSITIONS":
                        d.put("dec", t.getText().trim());
                        break;
                    default:
                        if (sym.startsWith("KEYWORD_")) {
                            kw.append(t.getText().trim()).append(' ');
                        }
                }
            }
            d.put("keywords", kw.toString().trim());
            out.declarations.add(d);
        }

        @Override
        public Void visitDspec(final DspecContext ctx) { // free dcl-s & fixed standalone D
            final Fact d = new Fact().put("kind", "field").put("line", ctx.getStart().getLine());
            d.put("name", afterStartToken(ctx));
            d.put("dataType", childOf(ctx, RpgParser.DatatypeContext.class));
            d.put("keywords", keywordText(ctx));
            // fixed-format variant carries positional type/length tokens
            for (final Token t : visible(ctx)) {
                final String sym = sym(t);
                if (sym == null) {
                    continue;
                }
                switch (sym) {
                    case "DATA_TYPE":
                        d.put("dataType", t.getText().trim());
                        break;
                    case "FROM_POSITION":
                        d.put("from", t.getText().trim());
                        break;
                    case "TO_POSITION":
                        d.put("to", t.getText().trim());
                        break;
                    case "DECIMAL_POSITIONS":
                        d.put("dec", t.getText().trim());
                        break;
                    default:
                        break;
                }
            }
            out.declarations.add(d);
            return null;
        }

        @Override
        public Void visitDcl_ds(final Dcl_dsContext ctx) {
            final Fact d = new Fact().put("kind", "ds").put("line", ctx.getStart().getLine());
            d.put("name", afterStartToken(ctx));
            d.put("keywords", keywordText(ctx));
            out.declarations.add(d);
            super.visitDcl_ds(ctx); // continue into parm_fixed subfields
            return null;
        }

        @Override
        public Void visitDcl_ds_field(final Dcl_ds_fieldContext ctx) {
            final Fact d = new Fact().put("kind", "ds_subfield").put("line", ctx.getStart().getLine());
            d.put("name", afterStartToken(ctx));
            d.put("dataType", childOf(ctx, RpgParser.DatatypeContext.class));
            d.put("keywords", keywordText(ctx));
            out.declarations.add(d);
            return null;
        }

        @Override
        public Void visitFspec_fixed_r400(final Fspec_fixed_r400Context ctx) {
            final Fact f = new Fact().put("spec", "fixed-r400").put("line", ctx.getStart().getLine());
            f.put("name", trim(ctx.FS_RecordName()));
            f.put("type", trim(ctx.FS_Type()));
            f.put("designation", trim(ctx.FS_Designation()));
            f.put("format", trim(ctx.FS_Format()));
            f.put("device", trim(ctx.FR_Device()));
            if (ctx.FR_KRENAME() != null) {
                f.put("rename", trim(ctx.FR_KwName()));
            } else if (ctx.FR_KINFDS() != null) {
                f.put("infds", trim(ctx.FR_KwName()));
            } else if (ctx.FR_KSFILE() != null) {
                f.put("ksfile", trim(ctx.FR_KwName()));
            }
            out.files.add(f);
            return null;
        }

        @Override
        public Void visitIspec_fixed_r400(final Ispec_fixed_r400Context ctx) {
            final Fact d = new Fact().put("line", ctx.getStart().getLine());
            if (ctx.IR_FieldName() != null) {
                d.put("kind", "field");
                d.put("name", ctx.IR_FieldName().getText().trim());
                if (ctx.IR_To() != null) {
                    d.put("to", ctx.IR_To().getText().trim());
                }
                if (ctx.IR_Len() != null) {
                    d.put("dataType", ctx.IR_Len().getText().trim());
                }
            } else if (ctx.IR_Name() != null) {
                d.put("kind", "ds_or_record");
                d.put("name", ctx.IR_Name().getText().trim());
                if (ctx.IR_DS() != null) {
                    d.put("ds", "DS");
                }
                if (ctx.IR_DSLength() != null) {
                    d.put("length", ctx.IR_DSLength().getText().trim());
                }
                if (ctx.IR_82() != null) {
                    d.put("length", ctx.IR_82().getText().trim());
                }
            } else {
                return null;
            }
            out.declarations.add(d);
            return null;
        }

        private String currentDdsRecord = null;

        @Override
        public Void visitDds_spec(final Dds_specContext ctx) {
            final String raw = ctx.DDS_SPEC().getText().replace("\r", "").replace("\n", "");
            final int lineNo = ctx.getStart().getLine();
            if (raw.length() < 12) {
                return null; // bare 'A' or too short to hold an entry
            }
            // token text starts at the 'A' (column 6): entry letter at col 17 (index 11),
            // name area from col 19 (index 13)
            final String entry = raw.substring(11, 12).trim();
            final String rest = raw.length() > 13 ? raw.substring(13).trim() : "";
            final int sp = restFirstBlank(raw);
            final String name = sp > 0 ? raw.substring(13, sp).trim() : rest;
            final String func = sp > 0 ? raw.substring(sp).trim() : "";
            if (name.isEmpty() && func.isEmpty()) {
                return null;
            }
            switch (entry) {
                case "R":
                    currentDdsRecord = name;
                    final Fact f = new Fact().put("spec", "dds").put("type", "R").put("line", lineNo);
                    f.put("name", name);
                    f.put("pfile", funcKeywordArg(func, "PFILE"));
                    f.put("function", func);
                    out.files.add(f);
                    break;
                case "K":
                case "S":
                case "O":
                default:
                    final String kindSuffix = entry.isEmpty() ? "field" : entry.toLowerCase();
                    final Fact d = new Fact().put("kind", "dds_" + kindSuffix)
                            .put("line", lineNo).put("name", name);
                    if (currentDdsRecord != null) {
                        d.put("record", currentDdsRecord);
                    }
                    d.put("function", func);
                    out.declarations.add(d);
                    break;
            }
            return null;
        }

        /** index of first blank at/after column 19 (index 13), or -1 */
        private int restFirstBlank(final String raw) {
            for (int i = 13; i < raw.length(); i++) {
                if (raw.charAt(i) == ' ') {
                    return i;
                }
            }
            return -1;
        }

        private String funcKeywordArg(final String func, final String keyword) {
            final int i = func.toUpperCase().indexOf(keyword + "(");
            if (i < 0) {
                return "";
            }
            final int s = i + keyword.length() + 1;
            final int e = func.indexOf(')', s);
            return e > s ? func.substring(s, e).trim() : "";
        }

        @Override
        public Void visitDcl_c(final Dcl_cContext ctx) {
            final Fact d = new Fact().put("kind", "const").put("line", ctx.getStart().getLine());
            if (ctx.name != null) {
                d.put("name", ctx.name.getText());
            }
            d.put("value", childOf(ctx, RpgParser.LiteralContext.class));
            out.declarations.add(d);
            return null;
        }

        @Override
        public Void visitDcl_pr(final Dcl_prContext ctx) {
            final Fact d = new Fact().put("kind", "proto").put("line", ctx.getStart().getLine());
            d.put("name", afterStartToken(ctx));
            d.put("keywords", keywordText(ctx));
            out.declarations.add(d);
            super.visitDcl_pr(ctx); // continue into pr parm fields
            return null;
        }

        // fixed-format BEGSR/ENDSR are standalone rules (not under cspec_fixed)

        @Override
        public Void visitCsBEGSR(final RpgParser.CsBEGSRContext ctx) {
            beginSub(factor1Text(ctx), ctx.getStart().getLine());
            return null;
        }

        @Override
        public Void visitCsENDSR(final RpgParser.CsENDSRContext ctx) {
            endSub(ctx.getStart().getLine());
            return null;
        }

        @Override
        public Void visitDcl_pi(final Dcl_piContext ctx) {
            final Fact d = new Fact().put("kind", "pi").put("line", ctx.getStart().getLine());
            d.put("name", afterStartToken(ctx));
            out.declarations.add(d);
            return null;
        }

        // ----- procedures / subroutines -----

        @Override
        public Void visitFreeBeginProcedure(final FreeBeginProcedureContext ctx) {
            final Fact p = new Fact().put("kind", "procedure").put("start", ctx.getStart().getLine());
            if (ctx.identifier() != null) {
                p.put("name", ctx.identifier().getText());
            }
            out.procedures.add(p);
            openProcs.push(p);
            return null;
        }

        @Override
        public Void visitFreeEndProcedure(final FreeEndProcedureContext ctx) {
            if (!openProcs.isEmpty()) {
                openProcs.pop().put("end", ctx.getStart().getLine());
            }
            return null;
        }

        @Override
        public Void visitFreeBEGSR(final FreeBEGSRContext ctx) {
            beginSub(ctx.identifier() != null ? ctx.identifier().getText() : null, ctx.getStart().getLine());
            return null;
        }

        @Override
        public Void visitFreeENDSR(final FreeENDSRContext ctx) {
            endSub(ctx.getStart().getLine());
            return null;
        }

        @Override
        public Void visitCspec_fixed(final Cspec_fixedContext ctx) {
            final Token op = firstOpToken(ctx);
            if (op == null) {
                return null;
            }
            switch (sym(op)) {
                case "OP_BEGSR":
                    beginSub(factor2Text(ctx, op), op.getLine());
                    return null;
                case "OP_ENDSR":
                    endSub(op.getLine());
                    return null;
                case "OP_EXSR":
                    addCall("EXSR", factor2Text(ctx, op), op.getLine(), null);
                    return null;
                case "OP_CALL":
                case "OP_CALLB": {
                    final List<String> parms = new ArrayList<>();
                    for (final ParseTree c : ctx.children) {
                        if (c instanceof RpgParser.CsPARMContext) {
                            final String p = partsResultText((ParserRuleContext) c);
                            if (p != null) {
                                parms.add(p);
                            }
                        }
                    }
                    final Fact call = new Fact()
                            .put("type", "OP_CALL".equals(sym(op)) ? "CALL" : "CALLB")
                            .put("target", strip(factor2Text(ctx, op))).put("line", op.getLine());
                    final String plist = partsResultText(ctx);
                    if (plist != null) {
                        call.put("plist", plist);
                    }
                    out.calls.add(call);
                    return null;
                }
                case "OP_PLIST": {
                    final Fact pl = new Fact().put("kind", "plist").put("line", op.getLine())
                            .put("name", factor1Text(ctx));
                    final List<String> parms = new ArrayList<>();
                    final RpgParser.CsPLISTContext plctx =
                            (RpgParser.CsPLISTContext) findDescendant(ctx, RpgParser.CsPLISTContext.class);
                    if (plctx != null) {
                        for (final ParseTree c : plctx.children) {
                            if (c instanceof RpgParser.CsPARMContext) {
                                final String p = partsResultText((ParserRuleContext) c);
                                if (p != null) {
                                    parms.add(p);
                                }
                            }
                        }
                    }
                    pl.put("parms", parms);
                    out.plists.add(pl);
                    return null;
                }
                default:
                    return super.visitCspec_fixed(ctx); // keep traversal alive (PLIST/PARM/SQL...)
            }
        }

        @Override
        public Void visitCspec_fixed_x2(final Cspec_fixed_x2Context ctx) {
            handleOpExpression(ctx);
            return super.visitCspec_fixed_x2(ctx);
        }

        @Override
        public Void visitFree(final FreeContext ctx) {
            handleOpExpression(ctx);
            return super.visitFree(ctx);
        }

        /** CALLP / EXSR statements whose body is a free expression. */
        private void handleOpExpression(final ParserRuleContext ctx) {
            final Token op = firstOpToken(ctx);
            if (op == null) {
                return;
            }
            final List<Token> after = afterOp(visible(ctx), op);
            switch (sym(op)) {
                case "OP_CALLP": {
                    final Token target = firstIdLike(after);
                    if (target != null) {
                        addCall("CALLP", target.getText(), op.getLine(), parenArgs(after, target));
                    }
                    break;
                }
                case "OP_EXSR": {
                    if (!after.isEmpty()) {
                        addCall("EXSR", after.get(0).getText(), op.getLine(), null);
                    }
                    break;
                }
                default:
                    break;
            }
        }

        // ----- SQL -----

        @Override
        public Void visitCspec_fixed_sql(final Cspec_fixed_sqlContext ctx) {
            final StringBuilder sb = new StringBuilder();
            for (final Token t : visible(ctx)) {
                if ("CSQL_TEXT".equals(sym(t)) || "CSQL_END".equals(sym(t))) {
                    sb.append(t.getText());
                }
            }
            addSql(sb.toString(), ctx.getStart().getLine());
            return null;
        }

        @Override
        public Void visitExec_sql(final Exec_sqlContext ctx) {
            final StringBuilder sb = new StringBuilder();
            for (final Token t : visible(ctx)) {
                if ("WORDS".equals(sym(t))) {
                    sb.append(t.getText()).append(' ');
                }
            }
            addSql(sb.toString(), ctx.getStart().getLine());
            return null;
        }

        // ----- helpers -----

        private void beginSub(final String name, final int line) {
            if (name == null || name.isEmpty()) {
                return;
            }
            subroutines.add(name.toUpperCase());
            final Fact p = new Fact().put("kind", "subroutine").put("name", name).put("start", line);
            out.procedures.add(p);
            openSubs.push(p);
        }

        private void endSub(final int line) {
            if (!openSubs.isEmpty()) {
                openSubs.pop().put("end", line);
            }
        }

        /** EXSR internal-ness is only known after all BEGSRs are seen. */
        void finalizeInternalFlags() {
            for (final Fact c : out.calls) {
                if ("EXSR".equals(c.m.get("type")) && c.m.get("target") != null) {
                    c.put("internal", subroutines.contains(String.valueOf(c.m.get("target")).toUpperCase()));
                }
            }
        }

        private void addCall(final String type, final String target, final int line, final List<String> parms) {
            if (target == null || target.isEmpty()) {
                return;
            }
            final Fact c = new Fact().put("type", type).put("target", target.replace("'", "").replace("(", "").trim())
                    .put("line", line);
            if ("EXSR".equals(type)) {
                c.put("internal", subroutines.contains(target.replace("'", "").toUpperCase()));
            }
            if (parms != null && !parms.isEmpty()) {
                c.put("parms", parms);
            }
            out.calls.add(c);
        }

        private static final Pattern SQL_VERB = Pattern.compile(
                "(?i)(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|CREATE\\s+(?:TABLE|INDEX|VIEW)|DROP\\s+TABLE|MERGE\\s+INTO|DECLARE\\s+\\S+\\s+CURSOR|CALL)\\s+([A-Za-z0-9_./#@$]+)?");
        private static final Pattern SQL_FROM = Pattern.compile("(?i)FROM\\s+([A-Za-z0-9_./#@$]+)");

        private void addSql(final String rawText, final int line) {
            final String text = rawText.replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) {
                return;
            }
            final Fact s = new Fact().put("line", line).put("text", text);
            final Matcher m = SQL_VERB.matcher(text);
            if (m.find()) {
                s.put("verb", m.group(1).replaceAll("\\s+", " ").trim().toUpperCase());
                if (m.group(2) != null) {
                    s.put("table", m.group(2).toUpperCase());
                }
            }
            final Matcher sel = SQL_FROM.matcher(text);
            if (sel.find()) {
                s.put("table", sel.group(1).toUpperCase());
            }
            out.sql.add(s);
        }

        private static List<String> parenArgs(final List<Token> toks, final Token from) {
            final List<String> args = new ArrayList<>();
            boolean inParens = false;
            final StringBuilder cur = new StringBuilder();
            boolean seenFrom = false;
            for (final Token t : toks) {
                if (t == from) {
                    seenFrom = true;
                    continue;
                }
                if (!seenFrom) {
                    continue;
                }
                if ("(".equals(t.getText())) {
                    inParens = true;
                    cur.setLength(0);
                    continue;
                }
                if (inParens) {
                    if (")".equals(t.getText())) {
                        if (cur.length() > 0) {
                            args.add(cur.toString().trim());
                        }
                        break;
                    }
                    if (":".equals(t.getText())) {
                        args.add(cur.toString().trim());
                        cur.setLength(0);
                    } else {
                        cur.append(t.getText());
                    }
                }
            }
            return args;
        }

        private static List<Token> afterOp(final List<Token> toks, final Token op) {
            final List<Token> r = new ArrayList<>();
            boolean after = false;
            for (final Token t : toks) {
                if (after) {
                    r.add(t);
                }
                if (t == op) {
                    after = true;
                }
            }
            return r;
        }

        private Token firstIdLike(final List<Token> toks) {
            for (final Token t : toks) {
                final String sym = sym(t);
                if ("ID".equals(sym) || "NAME".equals(sym)) {
                    return t;
                }
            }
            return toks.isEmpty() ? null : toks.get(0);
        }

        /** factor1 text: last non-blank token before the opcode (skips spec/indicator tokens). */
        private String factor1Text(final ParserRuleContext ctx) {
            final Token op = firstOpToken(ctx);
            if (op == null) {
                return null;
            }
            String name = null;
            for (final Token t : visible(ctx)) {
                if (t == op) {
                    break;
                }
                if (!t.getText().trim().isEmpty() && !"CS_FIXED".equals(sym(t))) {
                    name = t.getText().trim();
                }
            }
            return name;
        }

        /** factor2 text via the labeled cspec_fixed_standard_parts node. */
        private String factor2Text(final ParserRuleContext cspecCtx, final Token op) {
            if (op == null) {
                return null;
            }
            final ParserRuleContext parts = findDescendant(cspecCtx, Cspec_fixed_standard_partsContext.class);
            if (parts instanceof Cspec_fixed_standard_partsContext) {
                final RpgParser.FactorContext f = ((Cspec_fixed_standard_partsContext) parts).factor2;
                if (f != null) {
                    final String t = f.getText().trim();
                    return t.isEmpty() ? null : t;
                }
            }
            return null;
        }

        private String partsResultText(final ParserRuleContext cspecCtx) {
            final ParserRuleContext parts = findDescendant(cspecCtx, Cspec_fixed_standard_partsContext.class);
            if (parts instanceof Cspec_fixed_standard_partsContext) {
                final RpgParser.ResultTypeContext r = ((Cspec_fixed_standard_partsContext) parts).result;
                if (r != null) {
                    final String t = r.getText().trim();
                    return t.isEmpty() ? null : t;
                }
            }
            return null;
        }

        private static String strip(final String s) {
            return s == null ? null : s.replace("'", "").replace("(", "").trim();
        }

        private static ParserRuleContext findDescendant(final ParseTree root,
                final Class<? extends ParserRuleContext> cls) {
            if (cls.isInstance(root)) {
                return (ParserRuleContext) root;
            }
            for (int i = 0; i < root.getChildCount(); i++) {
                final ParserRuleContext r = findDescendant(root.getChild(i), cls);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }

        private Token firstOpToken(final ParserRuleContext ctx) {
            for (final Token t : visible(ctx)) {
                final String s = sym(t);
                if (s != null && s.startsWith("OP_")) {
                    return t;
                }
            }
            return null;
        }

        private List<Token> visible(final ParserRuleContext ctx) {
            final List<Token> out = new ArrayList<>();
            collectVisible(ctx, out);
            return out;
        }

        private void collectVisible(final ParseTree t, final List<Token> out) {
            if (t instanceof TerminalNode) {
                final Token tok = ((TerminalNode) t).getSymbol();
                if (tok.getChannel() == Token.DEFAULT_CHANNEL) {
                    out.add(tok);
                }
                return;
            }
            for (int i = 0; i < t.getChildCount(); i++) {
                collectVisible(t.getChild(i), out);
            }
        }

        private String sym(final Token t) {
            return VOCAB.getSymbolicName(t.getType());
        }

        private static String txt(final TerminalNode n) {
            return n == null ? null : n.getText();
        }

        private static String trim(final TerminalNode n) {
            return n == null ? null : n.getText().trim();
        }

        private String childOf(final ParserRuleContext ctx, final Class<? extends ParserRuleContext> cls) {
            for (int i = 0; i < ctx.getChildCount(); i++) {
                final ParseTree c = ctx.getChild(i);
                if (cls.isInstance(c)) {
                    return c.getText();
                }
            }
            return null;
        }

        /** first token after the dcl-xxx *Start keyword (the declared name). */
        private String afterStartToken(final ParserRuleContext ctx) {
            boolean afterStart = false;
            for (final Token t : visible(ctx)) {
                if (afterStart) {
                    return t.getText();
                }
                final String s = sym(t);
                if (s != null && s.endsWith("_Start")) {
                    afterStart = true;
                }
            }
            for (final Token t : visible(ctx)) {
                final String s = sym(t);
                if ("ID".equals(s) || "NAME".equals(s)) {
                    return t.getText();
                }
            }
            return null;
        }

        private String keywordText(final ParserRuleContext ctx) {
            final StringBuilder sb = new StringBuilder();
            for (final Token t : visible(ctx)) {
                final String s = sym(t);
                if (s != null && s.startsWith("KEYWORD_")) {
                    sb.append(t.getText()).append(' ');
                }
            }
            return sb.toString().trim();
        }
    }

    // ---------- parse + drive ----------

    private static class Listener implements org.antlr.v4.runtime.ANTLRErrorListener {
        final List<String> sink;
        final String stage;

        Listener(final List<String> sink, final String stage) {
            this.sink = sink;
            this.stage = stage;
        }

        @Override
        public void syntaxError(final Recognizer<?, ?> recognizer, final Object offendingSymbol, final int line,
                final int charPositionInLine, final String msg, final RecognitionException e) {
            if (sink.size() < 50) {
                sink.add(stage + " L" + line + ":" + charPositionInLine + " " + msg);
            }
        }

        @Override
        public void reportAmbiguity(final org.antlr.v4.runtime.Parser recognizer,
                final org.antlr.v4.runtime.dfa.DFA dfa, final int startIndex, final int stopIndex,
                final boolean exact, final java.util.BitSet ambigAlts,
                final org.antlr.v4.runtime.atn.ATNConfigSet configs) {
        }

        @Override
        public void reportAttemptingFullContext(final org.antlr.v4.runtime.Parser recognizer,
                final org.antlr.v4.runtime.dfa.DFA dfa, final int startIndex, final int stopIndex,
                final java.util.BitSet conflictingAlts, final org.antlr.v4.runtime.atn.ATNConfigSet configs) {
        }

        @Override
        public void reportContextSensitivity(final org.antlr.v4.runtime.Parser recognizer,
                final org.antlr.v4.runtime.dfa.DFA dfa, final int startIndex, final int stopIndex,
                final int prediction, final org.antlr.v4.runtime.atn.ATNConfigSet configs) {
        }
    }

    private static final int TIMEOUT_SECONDS = 90;
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2), r -> {
                final Thread t = new Thread(r, "rpg-extract");
                t.setDaemon(true);
                return t;
            });

    private static ProgramResult extract(final File file) throws IOException {
        final ProgramResult out = new ProgramResult();
        out.path = file.getAbsolutePath();
        out.program = file.getName().replaceFirst("\\.[^.]+$", "").split("  +")[0].trim();
        final String rawSource = loadFile(file);
        final boolean rpg400 = Rpg400Preprocessor.isRpg400(rawSource);
        final String source = rpg400 ? Rpg400Preprocessor.normalize(rawSource) : rawSource;
        out.lines = 1 + countOf(source, '\n');
        final long t0 = System.currentTimeMillis();
        final List<String> errs = new ArrayList<>();
        Throwable crash = runParse(source, file, PredictionMode.SLL, out, errs, rpg400);
        if (crash == null && !errs.isEmpty()) {
            // SLL may produce spurious errors; retry LL for accuracy
            errs.clear();
            out.calls.clear();
            out.files.clear();
            out.declarations.clear();
            out.procedures.clear();
            out.plists.clear();
            out.sql.clear();
            crash = runParse(source, file, PredictionMode.LL, out, errs, rpg400);
        }
        out.millis = System.currentTimeMillis() - t0;
        out.status = crash != null ? "CRASH: " + crash.getClass().getSimpleName()
                : errs.isEmpty() ? "CLEAN" : "ERRORS";
        out.errors.addAll(errs);
        return out;
    }

    private static Throwable runParse(final String source, final File file, final PredictionMode mode,
            final ProgramResult out, final List<String> errs, final boolean rpg400) {
        final Future<Throwable> f = EXEC.submit((Callable<Throwable>) () -> {
            try {
                final ANTLRInputStream input = new ANTLRInputStream(
                        new FixedWidthBufferedReader(new StringReader(source)));
                final RpgLexer lexer = new RpgLexer(input);
                lexer.rpg400 = rpg400;
                lexer.removeErrorListeners();
                lexer.addErrorListener(new Listener(errs, "lex"));
                final TokenSource pre = new PreprocessTokenSource(lexer,
                        new PreprocessTokenSource.FileFolderCopyBookProvider(file));
                final CommonTokenStream tokens = new CommonTokenStream(pre);
                final RpgParser parser = new RpgParser(tokens);
                VOCAB = parser.getVocabulary();
                parser.removeErrorListeners();
                parser.addErrorListener(new Listener(errs, "par"));
                parser.getInterpreter().setPredictionMode(mode);
                final ParserRuleContext tree = parser.r();
                final Extractor ex = new Extractor(out);
                ex.visit(tree);
                ex.finalizeInternalFlags();
                return null;
            } catch (final Throwable t) {
                return t;
            }
        });
        try {
            return f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            f.cancel(true);
            return new TimeoutException("parse timeout");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        } catch (final ExecutionException e) {
            return e.getCause() != null ? e.getCause() : e;
        }
    }

    // ---------- main ----------

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: FactExtractor <rootDir> -o ir.json [--ext a,b,...]");
            System.exit(2);
        }
        File root = new File(args[0]);
        File out = new File("ir.json");
        Set<String> exts = new HashSet<>(Arrays.asList("rpgle", "sqlrpgle", "rpg", "rpg4", "mbr", "txt"));
        for (int i = 1; i < args.length; i++) {
            if ("-o".equals(args[i]) && i + 1 < args.length) {
                out = new File(args[++i]);
            } else if ("--ext".equals(args[i]) && i + 1 < args.length) {
                exts = new HashSet<>(Arrays.asList(args[++i].toLowerCase().split(",")));
            }
        }
        final List<File> files = new ArrayList<>();
        collect(root, exts, files, 0);
        Collections.sort(files);
        System.out.println("Extracting " + files.size() + " files from " + root.getAbsolutePath());
        final List<ProgramResult> results = new ArrayList<>();
        int done = 0;
        for (final File f : files) {
            try {
                results.add(extract(f));
            } catch (final IOException e) {
                final ProgramResult r = new ProgramResult();
                r.path = f.getAbsolutePath();
                r.program = f.getName();
                r.status = "READ_FAIL";
                results.add(r);
            }
            done++;
            if (done % 5 == 0 || done == files.size()) {
                System.out.println("Processed " + done + "/" + files.size());
            }
        }
        EXEC.shutdownNow();
        printSummary(results);
        writeJson(results, out);
        System.out.println("IR written to " + out.getAbsolutePath());
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

    private static String loadFile(final File file) throws IOException {
        final byte[] bytes = Files.readAllBytes(file.toPath());
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.contains("\uFFFD")) {
            text = new String(bytes, Charset.forName("ISO-8859-1"));
        }
        return text;
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

    private static void printSummary(final List<ProgramResult> results) {
        System.out.println();
        System.out.println("================ FACT EXTRACTION SUMMARY ================");
        long clean = 0, nFiles = 0, nDecl = 0, nProc = 0, nCalls = 0, nSql = 0, nPlists = 0;
        for (final ProgramResult r : results) {
            final boolean c = "CLEAN".equals(r.status);
            clean += c ? 1 : 0;
            nFiles += r.files.size();
            nDecl += r.declarations.size();
            nProc += r.procedures.size();
            nCalls += r.calls.size();
            nPlists += r.plists.size();
            nSql += r.sql.size();
            System.out.println(String.format("  %-16s %-8s files=%-3d decl=%-4d proc=%-3d calls=%-3d plist=%-2d sql=%-2d",
                    r.program, c ? "CLEAN" : r.status, r.files.size(), r.declarations.size(), r.procedures.size(),
                    r.calls.size(), r.plists.size(), r.sql.size()));
        }
        System.out.println("  TOTAL: files=" + nFiles + " decl=" + nDecl + " procs=" + nProc + " calls=" + nCalls
                + " plist=" + nPlists + " sql=" + nSql + "  parseClean=" + clean + "/" + results.size());
    }

    private static void writeJson(final List<ProgramResult> results, final File out) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\"programs\": [\n");
        boolean first = true;
        for (final ProgramResult r : results) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  {\"program\": ").append(J(r.program)).append(", \"path\": ").append(J(r.path));
            sb.append(", \"lines\": ").append(r.lines).append(", \"status\": ").append(J(r.status));
            sb.append(", \"files\": ").append(factArr(r.files));
            sb.append(", \"declarations\": ").append(factArr(r.declarations));
            sb.append(", \"procedures\": ").append(factArr(r.procedures));
            sb.append(", \"calls\": ").append(factArr(r.calls));
            sb.append(", \"plists\": ").append(factArr(r.plists));
            sb.append(", \"sql\": ").append(factArr(r.sql));
            sb.append(", \"errors\": ").append(strArr(r.errors)).append("}");
        }
        sb.append("\n]}\n");
        Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String factArr(final List<Fact> facts) {
        final StringBuilder sb = new StringBuilder("[");
        boolean f = true;
        for (final Fact x : facts) {
            if (!f) {
                sb.append(", ");
            }
            f = false;
            sb.append("{");
            boolean fp = true;
            for (final Map.Entry<String, Object> e : x.m.entrySet()) {
                if (!fp) {
                    sb.append(", ");
                }
                fp = false;
                sb.append(J(e.getKey())).append(": ");
                final Object v = e.getValue();
                if (v instanceof List) {
                    sb.append(strArr((List<String>) v));
                } else if (v instanceof Integer) {
                    sb.append(v);
                } else {
                    sb.append(J(String.valueOf(v)));
                }
            }
            sb.append("}");
        }
        return sb.append("]").toString();
    }

    @SuppressWarnings("unchecked")
    static String strArr(final List<String> xs) {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(J(xs.get(i)));
        }
        return sb.append("]").toString();
    }

    static String J(final String s) {
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
                    if (c < 0x20 || c == '\u0085') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
