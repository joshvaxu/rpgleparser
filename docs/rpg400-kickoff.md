# RPG/400 Grammar 扩展开工指南

> 新会话直接说：**「读 docs/rpg400-kickoff.md，开工 RPG/400 grammar 扩展」**

## 项目背景

- 目标：把一个日系 RPG 系统移植到 Java。方案：用 rpgleparser（本仓库）做静态分析，
  构建 **RPG Semantic IR**（程序结构/调用关系/DS 字段/文件/SQL 事实库），再逐程序移植。
- 代码库现状（`RPG/` 目录，SJIS + CRLF 编码）：
  - `RPG/RTJ`：9 个 ILE RPG 程序，10,334 行 — ✅ 100% 解析
  - `RPG/DHPN/DDS`：35 个 DDS 表定义 — ✅ DdsExtractor 已提取（表/字段/键/LF→PF）
  - `RPG/DHPN` 根目录：**9 个 RPG/400 (RPG III) 程序，21,965 行 — ❌ 当前 0% 干净，51,483 个错误** ← 本任务
  - `RPG/DRPG`：3 个 RPG/400 手写样例（可做单元验证样本）

## 本任务目标

让 rpgleparser 能解析 DHPN 的 9 个 RPG/400 程序，使 FactExtractor 能产出与 ILE 相同
结构的 IR 事实（files/declarations/procedures/calls/plists）。

**已确认的关键结论**（来自 `docs/rpg400-diff-worksheet.md`，先读它）：
- **操作码 gap 为空**：失败行中出现的所有操作码（IFxx/DOWxx/DSPLY/GOTO/TAG/KLIST…）
  grammar 里全部已有，**不需要新增操作码规则**。
- 缺口全在**列位/结构差异**，按工作量排序：
  1. 序号列(1-6)变更标签（`. C`、`8.23C`、`TG824 *---`）→ 错误 ~14k，列位分布 0-9 峰值
  2. F 规范列位（RPG/400: 名称 7-14、类型 15、格式 17，`K DISK`/`KRENAME` 起始列不同）→ P1/P6/P8/P13 ~7k
  3. C 规范列位窗口（RPG/400 opcode 在 28-32 列，factor2 紧随其后）→ P3/P4/P5/P9/P11/P12 ~8.3k
  4. I 规范（P10 `IS_Number`）与 `C END` 块结束（P2 no viable alternative 2.3k）

## 已有资产（全部已验证可用）

| 工具 | 用途 | 命令 |
|---|---|---|
| CoverageScanner | 覆盖率扫描 | `java -cp target/uber-rpgleparser-1.0.0.jar org.rpgleparser.tools.CoverageScanner <dir> -o rep.json --ext txt` |
| FactExtractor | ILE 事实提取 | `java -cp ... FactExtractor <dir> -o ir.json --ext txt` |
| DdsExtractor | DDS schema 提取 | `java -cp ... DdsExtractor <dir> -o ir-dds.json --ext txt` |
| analyze_rpg400_diffs.py | 差异聚类回归 | `python3 scripts/analyze_rpg400_diffs.py coverage-report-dhpn.json` |

- 解析调用姿势（照抄 `src/test/java/org/rpgleparser/integration/TestFiles.java`）：
  `ANTLRInputStream(FixedWidthBufferedReader) → RpgLexer → PreprocessTokenSource → CommonTokenStream → RpgParser → parser.r()`
- ILE 提取器参考实现在 `src/main/java/org/rpgleparser/tools/FactExtractor.java`
  （visitor 模式、csBEGSR/csPLIST factor1 取名、super.visitXxx 保持遍历等经验都在里面）

## 建议实施顺序（先预处理后 grammar，每步可量化）

1. **预处理清标签**：在 PreprocessTokenSource 或扫描器入口，把序号列（1-6 列）中的
   变更标签归一化为空格（判定：col6 非规范字母且行首非空格）。跑扫描器看错误 51k→?
2. **Lexer：RPG/400 列位窗口**：RpgLexer.g4 的 `CS_Operation_*`、`FS_*` 谓词目前要求
   ILE 列位（如 opcode `>=30 && <36`）。为 RPG/400 增加窗口变体（opcode 28-32 列、
   F 规范 K/DISK/KRENAME 起始列），注意与 ILE 窗口不冲突（ILE opcode 允许 10 字符、
   RPG/400 5 字符——可用 factor1 是否为空区分分支模式）
3. **Parser：END 块 / I 规范**：`C END`（RPG/400 用 END 结束 IF/DOW 而非 ENDIF/ENDDO）、
   I 规范位置差异
4. 每步跑：`CoverageScanner RPG/DHPN --ext txt`（排除 DDS 子目录不影响，DDS 在子目录）
   + `python3 scripts/analyze_rpg400_diffs.py` 对比错误聚类变化
5. 干净率达标后，给 FactExtractor 补 RPG/400 事实提取（MOVEL/MOVE/Z-ADD 的 result 列、
   DSPLY/EXFMT 设备 IO 等），跑通 DHPN 9 程序产出 ir-dhpn.json

## 已知坑（前几轮踩过的）

- **pom 里 surefire `<skipTests>false</skipTests>` 硬编码**：`-DskipTests` 无效，跳测试用
  `-Dmaven.test.skip=true`。上游测试 TestFiles[166]（string.continuation）本来就有
  vocabulary 漂移失败，与本任务无关，别去修它
- **RpgLexer.g4 曾有 13 处调试 println**（已删）：新增 lexer 规则不要加打印
- **增量编译陷阱**：maven-compiler 有时不重编 tools 包，改完 grammar/工具类后确认 jar
  里类已更新（`unzip -p target/uber-rpgleparser-1.0.0.jar <class> | grep xxx`），必要时
  `rm -rf target/classes/org/rpgleparser/tools target/maven-status`
- **编码**：RPG/DHPN 是 Windows-31j（SJIS），stray 0x85=NEL 残留（别当行尾替换）；
  Python 侧用 `shift_jis`（`cp943`/`windows-31j` 编解码器不存在）
- **测试语料**：`src/test/resources/org/rpgleparser/tests` 260 文件必须保持 100% 通过
  （回归基线），每次 grammar 改动后跑一遍 CoverageScanner 对比

## 验收标准

- `CoverageScanner` 对 `RPG/DHPN`（排除 DDS）干净率 ≥ 95%
- `FactExtractor` 对 DHPN 9 程序产出 files/declarations/procedures/calls 事实
- `src/test/resources` 260 个测试文件仍然 100% CLEAN
- `docs/rpg400-diff-worksheet.md` 用新报告重新生成，错误聚类明显收敛
