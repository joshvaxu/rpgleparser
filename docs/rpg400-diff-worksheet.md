# RPG/400 vs ILE 差异清单（rpgleparser 覆盖缺口）

- 来源报告: `coverage-report-dhpn.json`
- 文件: 9 个，行数: 21,965，错误总数: 51,483（lexer 40,554 / parser 10,929）

## 1. 语法修复项（Parser，按 expecting 签名聚类）

| # | 错误数 | expecting 签名 | 典型源行 |
|---|--------|----------------|----------|
| P1 | 6765 | `expect{**=,*=,+,+=,-,-=…}` | `     F            TRREC                             KRENAMETRREC2` `../EOL3410R.TXT:23` |
| P2 | 2327 | `no viable alternative` | `     C                     END` `../EOL3410R.TXT:810` |
| P3 | 679 | `expect{COLON,CS_FieldLength}` | `     C                     PARM           P@PARM200         ＰＡＲＭ` `../EOL3410R.TXT:422` |
| P4 | 381 | `expect{),CS_OperationAndExtender}` | `     C                     MOVELP@PARM    W@PARM` `../EOL3410R.TXT:511` |
| P5 | 297 | `expect{              ,CS_FactorContent,DateLiteralStart,GraphicLiteralStart,HexLiteralStart,SPLAT_ALL…}` | `     C                     EXSR #INIT` `../EOL3410R.TXT:499` |
| P6 | 87 | `mismatched input '\n' expecting FS_LengthOfKey` | `     FDTZ1L06 IF  E           K        DISK` `../EOL3410R.TXT:18` |
| P7 | 87 | `expect{(,+,-,BIF_ABS,BIF_ADDR,BIF_ALLOC…}` | ` 8.23C*******************  MOVEL*BLANK    P@SOK` `../LLN0121R.TXT:767` |
| P8 | 60 | `mismatched input '     ' expecting FS_EndOfFile` | `     F            TRREC                             KRENAMETRREC2` `../EOL3410R.TXT:23` |
| P9 | 46 | `expect{              ,COLON,CS_FactorContent}` | `     C                     MOVE *ALL'-'   SEN1` `../EOL3410R.TXT:520` |
| P10 | 45 | `mismatched input '\n' expecting IS_Number` | `     INYREC       82` `../EOL3410R.TXT:48` |
| P11 | 34 | `expect{              ,CS_FactorContent}` | `     C           P@010     IFEQ *BLANK` `../EOL3410R.TXT:569` |
| P12 | 28 | `expect{PlusOrMinus,StringContent,StringEscapedQuote,StringLiteralEnd}` | `     C                     MOVELHTBNDC    KMMSC     P` `../EOL3410R.TXT:937` |
| P13 | 12 | `mismatched input '\r\n' expecting FS_LengthOfKey` | `A1   FDRU1L09 IF  E           K        DISK                           ` `../ZAD0040R.TXT:38` |
| P14 | 9 | `missing FREE_SEMI at '-'` | `TG824 *-------------------*` `../LLN0121R.TXT:2210` |
| P15 | 8 | `extraneous input '              ' expecting CS_FieldLength` | `     C                     END` `../EOL3410R.TXT:1252` |
| P16 | 7 | `expect{<EOF>,EOL,KEYWORD_ALIAS,KEYWORD_BLOCK,KEYWORD_COMMIT,KEYWORD_DATFMT…}` | `     F                                              KINFDS WSINF` `../HAP0011R.TXT:17` |
| P17 | 4 | `missing FREE_SEMI at 'Z'` | `     C                     Z-ADDLDNEND    SKNEND            年度` `../LLN0121R.TXT:483` |
| P18 | 4 | `missing FREE_SEMI at 'F'` | `      *  \| PROCESS :  G1 (KEY) のチェックを行う` `../LLN0121R.TXT:777` |
| P19 | 4 | `missing FREE_SEMI at 'MOVELSKSKK4'` | `  .  C                     MOVELSKSKK4    W@BUK2            集計 KEY(4)` `../LLN0121R.TXT:2187` |
| P20 | 4 | `missing FREE_SEMI at 'MOVELSKBRK2'` | `  .  C                     MOVELSKBRK2    W@BUK3            分類 KEY(2)` `../LLN0121R.TXT:2188` |

## 2. 词法修复项（Lexer，按 offending 文本形态聚类）

| # | 错误数 | 形态 | 样例 |
|---|--------|------|------|
| L1 | 11161 | bare-word | `     FDTZ1L06 IF  E           K        DISK` |
| L2 | 10669 | other | `     C                     PARM           P@PARM200         ＰＡＲＭ` |
| L3 | 10444 | blanks/blank-run | `` |
| L4 | 8074 | word+blanks mix | `     FDTZ1L06 IF  E           K        DISK` |
| L5 | 161 | change-tag in seq/indicator cols (BEAM/11.30) | `     E************        @INF    1   1 26               : ｺﾏﾝﾄﾞ 説明` |
| L6 | 24 | word+trailing-blanks (keyword column drift) | `     E                    NO          9  1 0             : 番号` |
| L7 | 21 | bare number | `     H            Y/                                    1` |

## 3. 操作码差距（失败行中出现的非 ILE 操作码）

| 操作码 | 出现次数 | ILE 中存在 | 说明 |
|--------|----------|-----------|------|

## 4. 错误列位分布（定位规范结构差异）

| 列区间 | 错误数 |
|--------|--------|
|   0-  9 | 14,035 |
|  10- 19 | 2,918 |
|  20- 29 | 3,832 |
|  30- 39 | 3,978 |
|  40- 49 | 4,413 |
|  50- 59 | 2,166 |
|  60- 69 | 11,767 |
|  70- 79 | 3,907 |
|  80- 89 | 1,900 |
|  90- 99 | 2,243 |
| 100-109 | 324 |

## 5. 建议工作项（按依赖顺序）

1. **序号列变更标签清理**（`  .  C`/`8.23C`/`TG824 *---` 等）—— 预处理规则，不需要改 grammar
   证据：P7/P14/P19/P20、列位分布 0-9 区间峰值 14,035
2. **F 规范列位差异**（RPG/400: name 7-14, type 15, format 17, keylen/recordaddr 更靠左，`K DISK`/`KRENAME` 起始列与 ILE 不同）—— 证据：P1/P6/P8/P13（合计 ~7,000）
3. **C 规范列位窗口**：RPG/400 opcode 在 28-32 列且 factor2 紧随其后（无 ILE 的 10 列宽窗口），需新增 RPG/400 的 CS_Operation_* 谓词窗口。证据：P3/P4/P5/P9/P11/P12（合计 ~8,300）
4. **I 规范**（P10, IS_Number）与 **END 块语句**（P2, `no viable alternative` at `C END`）
5. 每完成一项跑 `CoverageScanner` 观察干净率变化，用本脚本回归对比

