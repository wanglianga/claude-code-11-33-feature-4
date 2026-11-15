# 药品批次谱系数据质量、召回影响与处置成本证据归因服务

面向药品质量与财务控制团队的两套关联数据产品：

1. **批次谱系数据质量与召回影响核验**：检验结果更正后，回答截至某个发布时点哪些成品批次可能使用受影响原料、数量能确认到什么程度、哪些节点有可验证流转证据、每个结论缺什么证据。
2. **召回处置成本证据归因与会计期间一致性**：对已发布批次范围关联的检测、仓储、运输、报废处理和第三方服务凭证，回答每笔金额能否归因到哪个批次范围、属于哪个会计期间、是否被重复计入，以及哪些金额只能作为待核估计而不能进入正式成本事实。

技术栈限定：**Vue 3 + Spring Boot 3.5 + PostgreSQL 18**。

> 服务边界：本系统只做数据质量核验、范围发布与成本证据归因，**不决定是否销毁药品、不生成补偿金额、不向付款系统发指令，不创建付款、报销、销毁、退运、调拨或审批动作，不重建批次谱系、不改变召回范围**。

---

## 第一部分：批次谱系数据质量与召回影响核验

### 1.1 来源断言与发布范围分别版本化

系统不覆盖旧事实，而是把“来源断言”和“发布范围”分别版本化，使三类变化可被区分和复现：

| 变化类型 | 识别方式 | 系统行为 |
| --- | --- | --- |
| 检验结论变了 `INSPECTION_CHANGED` | 核验选择的检验更正版本不同 | 生成新发布版本，保留旧版本输入依据与受影响结论 |
| 新物流/机构凭证补到 `NEW_LOGISTICS_EVIDENCE` | 新快照中出现旧快照没有的物流/机构包 | 迟到回报只进新版本，不改变历史答复 |
| 解析规则修订 `PARSE_RULE_REVISED` | 包哈希有效但解析版本落后且字段语义受影响 | 标记 `PARSE_DIFF`，要求重新审阅关系边，不回填旧范围 |

每个来源包均保留**原始载荷、接收时间、事件时间、来源修订号、文件哈希（SHA-256）、解析规则版本、解析状态**。
每次核验在 `verification_run.input_snapshot` 中锁定截止时间前的来源包清单，后续补录无法混入。

### 1.2 六条强制规则的实现位置

| 业务规则 | 实现 |
| --- | --- |
| 同一内部批号对应两个供应商批号且无可验证凭证 → 保留双候选、不合并、扩大上界 | `IdentityResolutionService` + `QuantityQualityEngine`（缺口 `IDENTITY_AMBIGUITY`） |
| 拆分投料合计超过原料可用量 → 冻结整条分配链确认量，展示下界与超额差额 | `QuantityQualityEngine`（缺口 `OVER_FEEDING`，边状态 `FROZEN`，冻结沿 BFS 传播到全部下游代际） |
| 机构回报与物流签收不一致且双凭证在截止前有效 → 数量冲突，任一来源不得覆盖另一来源 | `QuantityQualityEngine.attachNodeQuantities`（缺口 `QUANTITY_CONFLICT`） |
| 检验更正发生在发布之后 → 新候选版本，旧版本只读保留 | `ReleaseService`（旧版本置 `SUPERSEDED`，记录 `diff_type` 与 `affected_conclusions`） |
| 箱码在两条互不相连运输记录中重复 → 重复载体证据，两条边都不得证明链路完整 | `LineageCompiler`（缺口 `DUPLICATE_BOX`，两条运输边降级 `SUSPECTED`） |
| 哈希通过但解析规则升级导致字段含义变化 → 解析差异，重新审阅关系边，不回填旧范围 | `DemoSeeder` 播种解析状态 + `LineageCompiler`（仅降级受影响事实类型的边） |

另有 `MISSING_FEEDING_EVIDENCE`（缺失投料凭证）、`UNMATCHED_BATCH`（无法匹配批号）两类缺口。
数量恒等式：`确认下界 + 待核 = 可能上界`；超额部分另列 `unattributed_delta`。

---

## 第二部分：召回处置成本证据归因与会计期间一致性

### 2.1 设计立场：凭证行 + 归因依据 + 期间判断共同构成成本事实

若仅按批次编号或发票备注汇总金额，报表很快，但会把同一运输服务的主账单与补充账单重复计入，也会把事后入账的旧服务错放到当前期间。
本产品因此以**凭证行、归因依据、期间判断**共同构成成本事实，清晰说明一笔金额**为何进入、为何排除、为何仍待核**，而不把成本数据伪装成已结算的业务结果。

七个协同组件：

| 组件 | 职责 | 主要实现 |
| --- | --- | --- |
| 成本受理台 | 登记发票/检测单/运输账单/仓储计费单/服务验收单/会计凭证的原始文件、币种、税额、服务期间、入账日期、来源系统与附件哈希 | `CostVoucherService` + `cost_voucher` |
| 凭证身份簿 | 外部单号+行项目+合同号+文件指纹组成去重候选；**相似金额绝不自动视为同一笔** | `voucher_identity` |
| 归因规则册 | 维护可归入批次范围的证据条件（服务对象、时间窗、合同条款、证据等级），版本化，区分直接归因/按规则分摊/仅相关不可归因 | `AttributionService` + `attribution_rule_book` |
| 期间校验器 | 依据服务发生日、验收日、入账日、关账状态与会计规则，确定应计期间、入账期间及归属依据 | `PeriodAssessmentService` |
| 重复计量检测器 | 从贷项通知、冲销凭证、多方转付、拆分账单识别可能重复的金额集合 | `DuplicateDetectionService` |
| 成本事实编译器 | 仅发布已证实、期间归属完成且未被重复占用的成本行；其余进入待核 | `CostFactCompiler` + `cost_fact` |
| 审计投影 | 每项成本可回查原始凭证、归因规则版本、期间判断、重复计量结论与质量状态 | `audit_view_version` |

### 2.2 强制业务规则与落点

1. **无分摊依据的多范围账单**：一张运输账单关联多个批次范围但没有重量、箱数或合同分摊条款时，归因规则册标为 `RELATED_ONLY`（仅相关不可归因），编译器**绝不按批次数平均分摊**。
2. **未配对贷项通知**：供应商先开正数发票、后提交未关联原发票行的贷项通知时，检测器把贷项纳入**待人工配对集合（OPEN）**，配对完成前不能将贷项任意冲减某个批次范围。
3. **合法跨期应计**：服务发生日在已关闭期间、验收日在当前期间且会计规则允许跨期应计，只要服务对象、金额归因、应计依据已证实，期间校验器同时给出应计期间与入账期间，编译器可发布并携带 `period_diff_flag`；若应计依据、服务对象或归因规则缺失，则列为待核，**不能用当前入账日覆盖服务事实**。
4. **双来源支付冲突**：同一检测服务由实验室账单与机构回报都声明已支付，但币种、税额或服务编号不一致时，保留两个独立声明并标记 `PAYMENT_CONFLICT`，**不认定任何一方为真实付款**。
5. **口径修订**：归因规则版本在期末后修订且只改变分类口径、不改变原始凭证时，审计投影生成新分析视图并保留旧口径成本事实，**不改写已关闭期间的发布记录**（旧工作区 `PUBLISHED` 只读，重新编译返回 409）。
6. **外币与缺失汇率**：外币凭证按所选会计规则核验汇率来源与适用日期；缺少可验证依据或不符合规则时，**本币金额强制留空并列 `FX_MISSING` 待核，绝不使用系统当前汇率补齐**。

待核原因枚举：`ATTRIBUTION_INSUFFICIENT`（归属依据不足）、`DUPLICATE_UNRESOLVED`（重复关系未解）、`VOUCHER_MISSING`（关键凭证缺失）、`ACCRUAL_BASIS_MISSING`（应计依据缺失）、`FX_MISSING`（汇率不可验证）、`PAYMENT_CONFLICT`（支付状态冲突）。

### 2.3 六类验收演示样例

调用 `POST /api/cost/demo-scenario` 一键生成覆盖全部验收规则的工作区 `CW-001`（基于已发布范围 REL-001/REL-002，关闭期间 AP-2026-10，会计规则 AR-ACCRUAL-V1，归因口径 ARB-V1），内含 13 张凭证：

| 凭证 | 场景 | 编译结果 |
| --- | --- | --- |
| CV-MULTI-01 | 多范围运输账单无分摊条款 | PENDING / RELATED_ONLY，不平均分摊 |
| CV-CROSS-01 | 服务在 10 月、入账在 11 月，三要素齐备 | CONFIRMED，应计 2026-10、入账 2026-11，跨期差异+合法跨期 |
| CV-NOBASIS-01 | 跨期但 GRADE-C、应计依据未证实 | PENDING，入账期间保留但不覆盖服务事实 |
| CV-CN-041 | 未关联原发票行的贷项通知 | PENDING，`CREDIT_NOTICE_UNPAIRED` 待人工配对 |
| CV-LAB-PAY / CV-INST-PAY | 同一检测服务双来源已支付、币种税额不一致 | 两条均 PENDING，`PAYMENT_CONFLICT` 双声明并存 |
| CV-FX-MISSING | 美元凭证无可验证汇率 | PENDING，本币金额留空 `FX_MISSING` |
| CV-FX-OK | 欧元凭证汇率来源/日期可验证 | CONFIRMED，本币 7800 |
| CV-DUP-1 / CV-DUP-2 | 身份键完全一致的两张发票 | 两条均 PENDING，`EXACT_DUPLICATE` |
| CV-ALLOC-01 | 多范围但有重量分摊条款 60/40 | 两条 CONFIRMED / ALLOCATED，600 与 400（非平均 500/500） |
| CV-GRADEB-01 | 单一对象 GRADE-B | ARB-V1 下 CONFIRMED；修订到 ARB-V2（仅 GRADE-A）后变 PENDING，旧事实不变 |

---

## 3. 目录结构

```
.
├── backend/                 # Spring Boot 服务（JDK 25，编译目标 21）
│   ├── src/main/java/com/trace/recall/
│   │   ├── common/          # JSONB 归一化、JDBC 支持、全局异常
│   │   ├── config/          # CORS、外部 API Key 拦截器
│   │   ├── ingestion/       # 接入登记台 + 演示样例 Seeder
│   │   ├── identity/        # 批次身份解析（歧义保留）
│   │   ├── lineage/         # 谱系编译器（有向关系边）
│   │   ├── quality/         # 数量质量引擎 + 证据缺口
│   │   ├── verification/    # 核验编排（快照锁定）
│   │   ├── release/         # 范围发布器（版本化、脱敏）
│   │   ├── cost/            # 成本受理台/身份簿/规则册/期间/重复/事实编译/审计投影
│   │   └── web/             # 内部 /api 与外部 /external 控制器
│   ├── src/main/resources/  # application.yml、schema.sql（含成本域 11 张表）
│   └── src/test/            # 端到端测试（独立 recall_test schema）
├── frontend/                # Vue 3 + Vite + vue-router
│   └── src/views/           # 核验台/接入台/核验详情/批次钻取/发布版本/成本归因台/外部接口
├── docker-compose.yml       # 一键启动 postgres + backend + frontend
└── README.md
```

---

## 4. 本地启动

### 方式 A：Docker Compose 一键启动（推荐交付方式）

```bash
docker compose up --build
```

- 前端（Nginx）：http://localhost:8081
- 后端 API：http://localhost:8080
- PostgreSQL：localhost:5432（库/用户/密码 `ccdev`）
- 后端首次启动自动建表并播种演示数据（来源包、批次、会计期间、会计规则、归因规则册）。

### 方式 B：本地开发模式（使用容器内预装运行时）

前置：PostgreSQL 已在 127.0.0.1:5432 运行，库/用户/密码为 `ccdev`。

```bash
# 1) 后端（终端 1）
cd backend
/opt/maven/bin/mvn spring-boot:run
# 启动后自动执行 schema.sql 并播种 8 个来源包、13 个批次主数据、2 个会计期间与 2 版归因规则册

# 2) 前端（终端 2）
cd frontend
/opt/node-22/bin/npm install
/opt/node-22/bin/npm run dev
# 打开 http://localhost:8081 （Vite 已配置 /api 与 /external 代理到 8080）
```

### 测试账号 / 凭证

| 用途 | 凭证 |
| --- | --- |
| 内部质量与成本工作台 | 无需登录（内网工作台），默认发起人 `质量分析员-张某` / `成本分析员-王某` |
| 外部处置系统 API Key（请求头 `X-API-Key`） | `ext-trace-key-2026`（可用环境变量 `EXTERNAL_API_KEY` 覆盖） |
| 数据库 | 库/用户/密码 `ccdev` |

---

## 5. 页面操作路径

**召回影响部分**：范围核验台选择检验版本与截止时间发起核验 → 核验详情查看数量区间、谱系图、身份候选、证据缺口、输入快照 → 批次「展开证据」下钻原始载荷 → 发布范围新版本 → 发布版本管理查看 SUPERSEDED 与三类差异 → 外部接口页验证脱敏与 401。

**成本归因部分**：打开「处置成本归因台」→ 点击「载入六类验收演示场景」（或手动选择关闭期间+已发布范围+会计规则+归因口径新建工作区）→「运行归因/期间/重复/事实编译」→ 在成本事实/归因判断/期间校验/重复计量/凭证身份簿页签核对 → 点击任一事实行「审计展开」查看凭证行、规则版本、期间判断、重复结论与历史视图 →「发布并冻结事实」→「期末后口径修订」生成 ARB-V2 新分析视图并对比旧口径事实。也可在「凭证登记」页签手工登记凭证后重新编译。

---

## 6. HTTP 接口摘要

### 内部工作台（`/api`，无 Key，可查看原始载荷）

召回影响：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/source-packages` / `/{code}` | 来源包清单与原始载荷 |
| GET | `/api/meta/inspection-versions` / `/api/meta/parse-rules` | 检验版本与解析规则 |
| GET | `/api/batches` | 批次主数据 |
| POST | `/api/runs` | 发起核验 |
| GET | `/api/runs/{id}` | 核验详情 |
| GET | `/api/runs/{id}/batches/{batchCode}/trace` | 批次全链路证据钻取 |
| POST | `/api/runs/{id}/publish` | 发布范围新版本 |
| GET | `/api/releases` | 发布版本 |

成本归因：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/cost/meta/periods` `/accounting-rules` `/rule-books` `/releases` | 期间、会计规则、归因规则册、可选范围 |
| POST | `/api/cost/demo-scenario` | 一键生成六类验收演示工作区 |
| GET/POST | `/api/cost/workspaces` | 工作区列表/创建 |
| GET | `/api/cost/workspaces/{id}` | 工作区详情 |
| GET/POST | `/api/cost/workspaces/{id}/vouchers` | 凭证列表/登记 |
| GET | `/api/cost/workspaces/{id}/identities` | 凭证身份簿去重候选 |
| POST | `/api/cost/workspaces/{id}/compile` | 运行归因+期间+重复+事实编译 |
| POST | `/api/cost/workspaces/{id}/publish` | 发布并冻结事实 |
| POST | `/api/cost/workspaces/{id}/revise` | 期末后口径修订（新建分析视图，旧事实只读） |
| GET | `/api/cost/workspaces/{id}/facts` `/attributions` `/periods` `/findings` | 事实/归因/期间/重复结果 |
| GET | `/api/cost/workspaces/{id}/facts/{factId}/audit` | 单事实审计投影下钻 |

### 外部处置系统（`/external`，必须 `X-API-Key`，仅脱敏摘要）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/external/releases` | 可访问发布版本列表 |
| GET | `/external/releases/{code}` | 范围摘要（白名单字段，无原始载荷与敏感联系人） |

外部响应字段白名单：`batchCode, nodeName, confirmedQty, pendingQty, possibleQty, lowerBound, upperBound, unit, evidenceGrade, traceLink, conflictFlag`；**原始 payload、患者/联系人在任何情况下都不会出现**。成本域不提供外部接口，避免成本估计被误读为结算结果。

---

## 7. 测试与验证方式

### 自动化测试

```bash
cd backend
/opt/maven/bin/mvn test
```

测试在独立 schema `recall_test` 中执行（`test-schema-prep.sql` 每次重建），每个测试类前清空并重新播种：

- `RecallServiceE2ETest`：身份歧义双候选、RM-A04 超额 15kg 与冻结传播、HOSP-乙 50/46 双数字冲突、BOX-9 双运输边降级、PARSE_DIFF 不牵连 MES、快照 6/8 与 MIXED 三类差异、外部接口 401 与脱敏白名单。
- `CostE2ETest`：无分摊多范围账单不平均分摊、未配对贷项待人工配对、合法跨期应计与依据缺失待核、双来源支付冲突双声明并存、缺失汇率本币留空、精确重复双双挂起、按条款 600/400 分摊、审计投影七段完整性、口径修订后 GRADE-B 由确认变待核而旧事实只读。

### 已实际执行的验证（本工作区）

- `mvn test`：**Tests run: 2, Failures: 0, Errors: 0 — BUILD SUCCESS**；
- `mvn clean package -DskipTests`：产出 `target/recall-service.jar`，以 `java -jar` 启动后两套端到端冒烟全部通过——
  - 召回：两次核验快照 6/8、RM-A04 确认下界 60/待核 15/未归因差 15、六类缺口齐全、REL-001 SUPERSEDED、REL-002 `MIXED:INSPECTION_CHANGED+NEW_LOGISTICS_EVIDENCE+PARSE_RULE_REVISED`、外部无 Key/错 Key 均 401、脱敏响应无敏感字段；
  - 成本：演示工作区 13 张凭证编译出 14 条事实（6 CONFIRMED / 8 PENDING，已证实本币合计 23300），4 项重复/冲突结论（精确重复 [10,11]、未配对贷项 [5]、多方转付与支付冲突 [6,7]）；跨期事实同时保留应计 2026-10 与入账 2026-11 并标记差异；缺失汇率事实本币金额为 NULL；发布后重新编译返回 409；ARB-V2 修订后 CV-GRADEB-01 降为 PENDING 而旧工作区该事实保持 CONFIRMED；
- 前端 `npm install` 与 `npm run build` 成功（Vite 35 模块，无报错）；`npm run dev` 启动后页面 200，`/api`、`/api/cost`、`/external` 代理联通。

> 说明：当前容器未安装 docker 二进制，`docker-compose.yml` 与两个 Dockerfile 已按标准 Compose / 多阶段构建规范编写但未在本容器内实际构建；其等价步骤（Maven 打包、npm 构建、jar 启动、Nginx 反代配置）均已在本地实际执行通过。
