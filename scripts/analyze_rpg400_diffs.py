#!/usr/bin/env python3
"""Analyze rpgleparser failures on RPG/400 sources -> grammar-fix work items.

Clusters the errors in a CoverageScanner JSON report into:
  - lexer fix clusters (token recognition errors, by offending-text shape)
  - parser fix clusters (grouped by "expecting {...}" token-set signature)
  - column-position drift (error column histogram per spec letter)
  - opcode gap list (opcodes seen on failing fixed C-spec lines vs ILE OP_* set)

Output: a markdown worksheet with counts, evidence lines and a suggested
implementation order, plus a machine-readable JSON summary.

Usage:
  python3 scripts/analyze_rpg400_diffs.py coverage-report-dhpn.json \
      [--root RPG/DHPN] [-o docs/rpg400-diff-worksheet.md]
"""
import argparse
import collections
import json
import os
import re
import sys

OUT_DEFAULT = "docs/rpg400-diff-worksheet.md"

ILE_OPCODES = {
    "ACQ", "ADD", "ADDDUR", "ALLOC", "ANDEQ", "ANDGE", "ANDGT", "ANDLE", "ANDLT", "ANDNE",
    "BEGSR", "BITOFF", "BITON", "CABEQ", "CABGE", "CABGT", "CABLE", "CABLT", "CABNE",
    "CALL", "CALLB", "CALLP", "CASEQ", "CASGE", "CASGT", "CASLE", "CASLT", "CASNE", "CAT",
    "CHAIN", "CHECK", "CHECKR", "CLEAR", "CLOSE", "COMMIT", "COMP", "DEALLOC", "DEFINE",
    "DELETE", "DIV", "DO", "DOUEQ", "DOUGE", "DOUGT", "DOULE", "DOULT", "DOUNE", "DOWEQ",
    "DOWGE", "DOWGT", "DOWLE", "DOWLT", "DOWNE", "DSPLY", "ELSE", "ELSEIF", "END", "ENDCS",
    "ENDDO", "ENDFOR", "ENDIF", "ENDMON", "ENDSL", "ENDSR", "EVAL", "EVALR", "EXCEPT",
    "EXFMT", "EXSR", "EXTRCT", "FEOD", "FOR", "FORCE", "GOTO", "IFEQ", "IFGE", "IFGT",
    "IFLE", "IFLT", "IFNE", "IN", "ITER", "KFLD", "KLIST", "LEAVE", "LEAVESR", "LOOKUP",
    "MONITOR", "MOVE", "MOVEA", "MOVEL", "MULT", "MVR", "NEXT", "ON-ERROR", "ON-EXIT",
    "OPEN", "OREQ", "ORGE", "ORGT", "ORLE", "ORLT", "ORNE", "OTHER", "OUT", "PARM", "PLIST",
    "POST", "READ", "READC", "READE", "READP", "READPE", "REL", "RESET", "RETURN", "ROLBK",
    "SCAN", "SELECT", "SETGT", "SETLL", "SETOFF", "SETON", "SHTDN", "SORTA", "SUB",
    "SUBDUR", "SUBST", "TAG", "TEST", "TESTB", "TESTN", "TESTZ", "TIME", "UNLOCK", "UPDATE",
    "WHENEQ", "WHENGE", "WHENGT", "WHENLE", "WHENLT", "WHENNE", "WRITE", "XFOOT", "XLATE",
    "Z-ADD", "Z-SUB",
}
ILE_OPCODES = {o.upper() for o in ILE_OPCODES}

CHANGE_TAG_RE = re.compile(r"^[\w.]{1,6} ?\*", re.IGNORECASE)



def guess_root(report):
    paths = [f["path"] for f in report["files"]]
    if not paths:
        return None
    return os.path.dirname(os.path.dirname(paths[0])) or "."


def read_source_line(path, line_no):
    try:
        with open(path, "rb") as fh:
            txt = fh.read().decode("shift_jis", errors="replace")
    except OSError:
        return None
    lines = txt.split("\n")
    if 1 <= line_no <= len(lines):
        return lines[line_no - 1].rstrip("\r").rstrip()
    return None


def normalize_expected(msg):
    m = re.search(r"expecting \{(.+?)\}", msg)
    if m:
        toks = sorted(set(t.strip().strip("'") for t in m.group(1).split(",")))
        label = "expect{" + ",".join(toks[:6]) + ("…" if len(toks) > 6 else "") + "}"
        return label
    return re.split(r" at: | at input ", msg)[0][:60]


def offending(msg):
    m = re.search(r"'([^']*)'", msg)
    return m.group(1) if m else ""


def lexer_shape(text):
    t = text
    if not t.strip():
        return "blanks/blank-run"
    s = t.strip()
    if CHANGE_TAG_RE.match(s):
        return "change-tag in seq/indicator cols (BEAM/11.30)"
    if re.fullmatch(r"[0-9]+[.]?[0-9]*", s):
        return "bare number"
    if re.fullmatch(r"[A-Z]{1,6}", s):
        return "bare-word"
    if re.fullmatch(r"[A-Z ]{2,}", s):
        return "word+trailing-blanks (keyword column drift)"
    if " " in t:
        return "word+blanks mix"
    return "other"


def opcode_from_source(line):
    """RPG/400 fixed C-spec: opcode at cols 28-32 (0-based 27..31)."""
    if not line or len(line) < 32 or line[5] != "C":
        return None
    op = line[27:32].strip().upper()
    if not op or not re.fullmatch(r"[A-Z][A-Z0-9 -]{1,8}", op):
        return None
    return op


def in_ile(op):
    return op in ILE_OPCODES or any(x.startswith(op) for x in ILE_OPCODES)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("report")
    ap.add_argument("--root", default=None)
    ap.add_argument("--exclude", default=r"/DDS/", help="regex of paths to skip")
    ap.add_argument("--max-examples", type=int, default=3)
    ap.add_argument("-o", "--out", default=OUT_DEFAULT)
    ap.add_argument("--json", dest="json_out", default=None)
    args = ap.parse_args()

    with open(args.report, "r", encoding="utf-8") as fh:
        report = json.load(fh)
    root = args.root or guess_root(report)

    EXCLUDE_RE = re.compile(args.exclude) if args.exclude else None
    kept = [f for f in report["files"] if not (EXCLUDE_RE and EXCLUDE_RE.search(f["path"]))]
    files_n = len(kept)
    lines_n = sum(f["lines"] for f in kept)

    lex = collections.Counter()
    lex_ex = collections.defaultdict(list)
    par = collections.Counter()
    par_ex = collections.defaultdict(list)
    col_hist = collections.Counter()
    stage = collections.Counter()
    op_hist = collections.Counter()
    op_not_ile = collections.Counter()
    tag_files = collections.Counter()

    for f in report["files"]:
        if EXCLUDE_RE and EXCLUDE_RE.search(f["path"]):
            continue
        for e in f["errors"]:
            msg = e["message"]
            col_hist[e["col"] // 10 * 10] += 1
            stage["lexer" if e["stage"] == "lexer" else "parser"] += 1
            if e["stage"] == "lexer":
                o = offending(msg)
                shape = lexer_shape(o)
                lex[shape] += 1
                if len(lex_ex[shape]) < args.max_examples:
                    src = read_source_line(f["path"], e["line"])
                    lex_ex[shape].append({"path": os.path.relpath(f["path"], root) if root else f["path"],
                                          "line": e["line"], "text": o, "src": (src or "")[:70]})
            else:
                key = normalize_expected(msg)
                par[key] += 1
                if len(par_ex[key]) < args.max_examples:
                    src = read_source_line(f["path"], e["line"])
                    par_ex[key].append({"path": os.path.relpath(f["path"], root) if root else f["path"],
                                        "line": e["line"], "tok": offending(msg),
                                        "src": (src or "")[:70]})
                    if src:
                        op = opcode_from_source(src)
                        if op:
                            op_hist[op] += 1
                            if op not in ILE_OPCODES:
                                op_not_ile[op] += 1

    # change-tag lines (BEAM / 11.30 in sequence columns)
    for f in report["files"]:
        if EXCLUDE_RE and EXCLUDE_RE.search(f["path"]):
            continue
        for e in f["errors"]:
            o = offending(e["message"])
            if CHANGE_TAG_RE.match(o.strip()):
                tag_files[os.path.basename(f["path"])] += 1

    total_err = sum(lex.values()) + sum(par.values())

    # ---- markdown worksheet ----
    md = []
    md.append("# RPG/400 vs ILE 差异清单（rpgleparser 覆盖缺口）\n")
    md.append(f"- 来源报告: `{os.path.basename(args.report)}`")
    md.append(f"- 文件: {files_n} 个，行数: {lines_n:,}，错误总数: {total_err:,}"
              f"（lexer {stage['lexer']:,} / parser {stage['parser']:,}）\n")

    md.append("## 1. 语法修复项（Parser，按 expecting 签名聚类）\n")
    md.append("| # | 错误数 | expecting 签名 | 典型源行 |")
    md.append("|---|--------|----------------|----------|")
    for i, (key, n) in enumerate(par.most_common(20), 1):
        ex = par_ex.get(key, [{}])[0]
        src = ex.get("src", "").replace("|", "\\|")
        loc = f"`{ex.get('path','?')}:{ex.get('line','?')}`" if ex else ""
        md.append(f"| P{i} | {n} | `{key}` | `{src}` {loc} |")

    md.append("\n## 2. 词法修复项（Lexer，按 offending 文本形态聚类）\n")
    md.append("| # | 错误数 | 形态 | 样例 |")
    md.append("|---|--------|------|------|")
    for i, (shape, n) in enumerate(lex.most_common(15), 1):
        exs = lex_ex.get(shape, [])
        sample = next((x for x in exs if x["text"].strip()), {})
        sample_text = (sample.get("src") or sample.get("text", "")).replace("|", "\\|")
        md.append(f"| L{i} | {n} | {shape} | `{sample_text}` |")

    md.append("\n## 3. 操作码差距（失败行中出现的非 ILE 操作码）\n")
    md.append("| 操作码 | 出现次数 | ILE 中存在 | 说明 |")
    md.append("|--------|----------|-----------|------|")
    desc = {"DSPLY": "显示终端消息", "GOTO/TAG": "跳转（Java 用异常/循环改写）",
            "KLIST/KFLD": "键列表声明", "SETON/SETOFF": "指示器赋值", "Z-ADD/Z-SUB": "数值赋值",
            "EXCPT": "O-spec 异常输出", "MOVE/MOVEL/MOVEA": "字符传送（列位差异）"}
    for i, (op, n) in enumerate(op_not_ile.most_common(20), 1):
        md.append(f"| `{op}` | {n} | {'是(列位问题)' if in_ile(op) else '否(缺失)'} | {desc.get(op, '')} |")

    md.append("\n## 4. 错误列位分布（定位规范结构差异）\n")
    md.append("| 列区间 | 错误数 |")
    md.append("|--------|--------|")
    for col in sorted(col_hist):
        md.append(f"| {col:3d}-{col + 9:3d} | {col_hist[col]:,} |")

    md.append("\n## 5. 建议工作项（按依赖顺序）\n")
    if op_not_ile:
        md.append("1. **序号列变更标签清理**（BEAM/11.30）—— 预处理规则，不需要改 grammar")
        md.append("2. **Lexer 列位窗口**：F/C 规范的 device/opcode/factor 列位与 ILE 不同，需加 RPG/400 模式")
        md.append("3. **操作码补齐**：上表非 ILE 操作码")
        md.append("4. **Factor/Result 语义**：RPG/400 指示器式 C 规范（IFxx/DOWxx/CABxx）")
    else:
        md.append("1. **序号列变更标签清理**（`  .  C`/`8.23C`/`TG824 *---` 等）—— 预处理规则，不需要改 grammar")
        md.append("   证据：P7/P14/P19/P20、列位分布 0-9 区间峰值 14,035")
        md.append("2. **F 规范列位差异**（RPG/400: name 7-14, type 15, format 17, keylen/recordaddr 更靠左，"
                  "`K DISK`/`KRENAME` 起始列与 ILE 不同）—— 证据：P1/P6/P8/P13（合计 ~7,000）")
        md.append("3. **C 规范列位窗口**：RPG/400 opcode 在 28-32 列且 factor2 紧随其后（无 ILE 的 10 列宽窗口），"
                  "需新增 RPG/400 的 CS_Operation_* 谓词窗口。证据：P3/P4/P5/P9/P11/P12（合计 ~8,300）")
        md.append("4. **I 规范**（P10, IS_Number）与 **END 块语句**（P2, `no viable alternative` at `C END`）")
        md.append("5. 每完成一项跑 `CoverageScanner` 观察干净率变化，用本脚本回归对比\n")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(md) + "\n")

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump({
                "files": files_n, "lines": lines_n, "errors": total_err,
                "lexer": dict(lex.most_common(30)),
                "parser": dict(par.most_common(30)),
                "opcodes_non_ile": dict(op_not_ile.most_common(30)),
                "col_hist": {str(k): v for k, v in sorted(col_hist.items())},
            }, fh, ensure_ascii=False, indent=1)

    print(f"worksheet: {args.out}")
    if args.json_out:
        print(f"json:      {args.json_out}")
    print(f"files={files_n} lines={lines_n} errors={total_err} "
          f"(lexer={stage['lexer']} parser={stage['parser']})")


if __name__ == "__main__":
    main()
