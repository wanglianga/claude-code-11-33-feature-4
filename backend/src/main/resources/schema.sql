-- 药品批次谱系数据质量与召回影响服务 · 审计级 schema
-- 所有事实均保留：原始载荷、接收时间、事件时间、来源修订号、文件哈希、解析版本

CREATE TABLE IF NOT EXISTS source_package (
    id                       BIGSERIAL,
    package_code             TEXT NOT NULL UNIQUE,
    source_system            TEXT NOT NULL,            -- QC_LAB / MES / WMS / LOGISTICS / INSTITUTION / MDM
    source_revision          TEXT NOT NULL,            -- 来源修订号
    payload_hash             TEXT NOT NULL,            -- 原始载荷 SHA-256
    parse_rule_version       TEXT NOT NULL,            -- 接收时解析规则版本
    parse_status             TEXT NOT NULL,            -- PARSED / PARSE_DIFF / FAILED
    received_at              TIMESTAMPTZ NOT NULL,     -- 接收时间
    event_time               TIMESTAMPTZ NOT NULL,     -- 事件时间
    supersedes_package_code  TEXT,
    payload                  JSONB NOT NULL,           -- 原始载荷（内部可见，含敏感字段）
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS parse_rule (
    version        TEXT PRIMARY KEY,
    effective_at   TIMESTAMPTZ NOT NULL,
    description    TEXT NOT NULL,
    affected_types TEXT NOT NULL                       -- 受影响事实类型，逗号分隔
);

CREATE TABLE IF NOT EXISTS raw_fact (
    id            BIGSERIAL PRIMARY KEY,
    package_id    BIGINT NOT NULL REFERENCES source_package(id),
    fact_type     TEXT NOT NULL,                       -- INSPECTION/FEEDING/RELEASE/SPLIT/BLEND/LOGISTICS/INSTITUTION_REPORT/MASTER_DATA
    fact_code     TEXT NOT NULL UNIQUE,
    event_time    TIMESTAMPTZ NOT NULL,
    payload       JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS batch_master (
    batch_code    TEXT PRIMARY KEY,                    -- 内部批号
    batch_type    TEXT NOT NULL,                       -- RAW / INTERMEDIATE / FINISHED
    product_code  TEXT,
    product_name  TEXT,
    unit          TEXT NOT NULL DEFAULT 'kg',
    quantity      NUMERIC(14,3),
    package_id    BIGINT REFERENCES source_package(id),
    attributes    JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- 核验请求（一次范围核验）：锁定检验更正版本 + 截止时间 + 输入快照
CREATE TABLE IF NOT EXISTS verification_run (
    id                           BIGSERIAL PRIMARY KEY,
    run_code                     TEXT NOT NULL UNIQUE,
    inspection_correction_version TEXT NOT NULL,       -- 选定的检验更正版本
    cutoff_at                    TIMESTAMPTZ NOT NULL, -- 截止时间
    rule_version                 TEXT NOT NULL,
    status                       TEXT NOT NULL,        -- LOCKED / PUBLISHED / SUPERSEDED
    input_snapshot               JSONB NOT NULL,       -- 锁定的来源包、哈希、解析状态
    created_by                   TEXT NOT NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 身份观测（供应商批号/内部批号/箱码/生产批号）
CREATE TABLE IF NOT EXISTS identity_record (
    id                   BIGSERIAL PRIMARY KEY,
    run_id               BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    code_type            TEXT NOT NULL,                -- SUPPLIER_LOT / INTERNAL_LOT / BOX_CODE / PRODUCTION_LOT
    code_value           TEXT NOT NULL,
    observed_batch_code  TEXT,                         -- 观测指向的内部批次（NULL=未匹配）
    match_status         TEXT NOT NULL,                -- MATCHED / AMBIGUOUS / UNMATCHED
    UNIQUE (run_id, code_type, code_value)
);

CREATE TABLE IF NOT EXISTS identity_candidate (
    id                   BIGSERIAL PRIMARY KEY,
    identity_record_id   BIGINT NOT NULL REFERENCES identity_record(id) ON DELETE CASCADE,
    candidate_batch_code TEXT NOT NULL,
    confidence           TEXT NOT NULL,                -- HIGH / MEDIUM / LOW
    ambiguous            BOOLEAN NOT NULL DEFAULT false,
    evidence             JSONB NOT NULL DEFAULT '[]'::jsonb
);

-- 谱系边（每次核验独立编译，不可变快照）
CREATE TABLE IF NOT EXISTS lineage_edge (
    id                   BIGSERIAL PRIMARY KEY,
    run_id               BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    edge_code            TEXT NOT NULL,
    upstream_batch       TEXT,                          -- 未匹配运输事实允许为空
    downstream_batch     TEXT NOT NULL,
    relation_type        TEXT NOT NULL,                -- FEEDING/SPLIT/BLEND/RELEASE/SHIPMENT
    source_fact_code     TEXT NOT NULL,
    source_package_code  TEXT NOT NULL,
    valid_from           TIMESTAMPTZ NOT NULL,
    valid_to             TIMESTAMPTZ,
    confidence_status    TEXT NOT NULL,                -- CONFIRMED / SUSPECTED / FROZEN
    review_required      BOOLEAN NOT NULL DEFAULT false,
    block_reason         TEXT,
    parse_rule_version   TEXT NOT NULL,
    quantity             NUMERIC(14,3),
    unit                 TEXT,
    edge_detail          JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- 批次数量质量状态（每批次每核验）
CREATE TABLE IF NOT EXISTS quantity_state (
    run_id              BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    batch_code          TEXT NOT NULL,
    affected_flag       BOOLEAN NOT NULL DEFAULT false,
    confirmed_lower     NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 确认量下界
    pending_qty         NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 待核量
    possible_upper      NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 可能受影响量上界
    unattributed_delta  NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 未能归因的数量差
    unit                TEXT NOT NULL,
    detail              JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (run_id, batch_code)
);

-- 证据缺口工作台
CREATE TABLE IF NOT EXISTS evidence_gap (
    id                 BIGSERIAL PRIMARY KEY,
    run_id             BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    gap_type           TEXT NOT NULL,  -- MISSING_FEEDING_EVIDENCE/DUPLICATE_BOX/QUANTITY_CONFLICT/UNMATCHED_BATCH/IDENTITY_AMBIGUITY/OVER_FEEDING/PARSE_DIFF
    severity           TEXT NOT NULL,  -- BLOCKER / WARNING
    subject_code       TEXT NOT NULL,
    node_name          TEXT,
    description        TEXT NOT NULL,
    blocks_conclusion  TEXT NOT NULL,  -- 阻断了哪个谱系结论
    evidence           JSONB NOT NULL DEFAULT '[]'::jsonb,
    status             TEXT NOT NULL DEFAULT 'OPEN',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 发布范围版本（来源断言与发布范围分别版本化）
CREATE TABLE IF NOT EXISTS release_version (
    id                        BIGSERIAL PRIMARY KEY,
    release_code              TEXT NOT NULL UNIQUE,
    version_no                INTEGER NOT NULL,
    run_id                    BIGINT NOT NULL REFERENCES verification_run(id),
    status                    TEXT NOT NULL,           -- PUBLISHED / SUPERSEDED
    predecessor_release_code  TEXT,
    diff_type                 TEXT,                    -- NONE/INSPECTION_CHANGED/NEW_LOGISTICS_EVIDENCE/PARSE_RULE_REVISED/MIXED
    diff_detail               JSONB NOT NULL DEFAULT '[]'::jsonb,
    basis                     JSONB NOT NULL,          -- 输入依据：包/哈希/解析版本/规则版本
    affected_conclusions      JSONB NOT NULL DEFAULT '[]'::jsonb,
    published_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 对外发布项（仅批次标识、机构/节点、数量区间、证据等级、可追溯链接）
CREATE TABLE IF NOT EXISTS release_item (
    id              BIGSERIAL PRIMARY KEY,
    release_id      BIGINT NOT NULL REFERENCES release_version(id) ON DELETE CASCADE,
    batch_code      TEXT NOT NULL,
    node_name       TEXT,
    confirmed_qty   NUMERIC(14,3) NOT NULL DEFAULT 0,
    pending_qty     NUMERIC(14,3) NOT NULL DEFAULT 0,
    possible_qty    NUMERIC(14,3) NOT NULL DEFAULT 0,
    lower_bound     NUMERIC(14,3) NOT NULL DEFAULT 0,
    upper_bound     NUMERIC(14,3) NOT NULL DEFAULT 0,
    unit            TEXT NOT NULL,
    evidence_grade  TEXT NOT NULL,                     -- CONFIRMED / PENDING / POSSIBLE
    trace_link      TEXT NOT NULL,
    conflict_flag   BOOLEAN NOT NULL DEFAULT false
);

-- 外部处置系统凭证
CREATE TABLE IF NOT EXISTS api_client (
    api_key   TEXT PRIMARY KEY,
    name      TEXT NOT NULL,
    enabled   BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX IF NOT EXISTS idx_fact_package ON raw_fact(package_id);
CREATE INDEX IF NOT EXISTS idx_edge_run ON lineage_edge(run_id);
CREATE INDEX IF NOT EXISTS idx_gap_run ON evidence_gap(run_id);
CREATE INDEX IF NOT EXISTS idx_candidate_record ON identity_candidate(identity_record_id);

-- ============================================================================
-- 召回处置成本证据归因与会计期间一致性数据产品
-- 设计原则：凭证行、归因依据、期间判断共同构成成本事实；相似金额绝不自动并单；
-- 归属依据不足/重复未解/关键凭证缺失/汇率不可验证 → 待核，不进入正式成本事实。
-- 本域不创建付款、报销、销毁、退运、调拨或审批动作。
-- ============================================================================

-- 会计期间（分析员选择的关闭期间）
CREATE TABLE IF NOT EXISTS accounting_period (
    id            BIGSERIAL PRIMARY KEY,
    period_code   TEXT NOT NULL UNIQUE,          -- 如 AP-2026-10
    period_name   TEXT NOT NULL,
    period_start  DATE NOT NULL,
    period_end    DATE NOT NULL,
    closed_at     TIMESTAMPTZ,                   -- NULL=尚未关账
    status        TEXT NOT NULL DEFAULT 'OPEN',  -- OPEN / CLOSED
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 会计规则（版本化）：是否允许跨期应计、外币汇率来源与适用日期政策
CREATE TABLE IF NOT EXISTS accounting_rule (
    rule_code                TEXT PRIMARY KEY,   -- 如 AR-ACCRUAL-V1
    rule_name                TEXT NOT NULL,
    allows_cross_period      BOOLEAN NOT NULL,
    fx_policy                JSONB NOT NULL DEFAULT '{}'::jsonb,  -- 汇率来源优先级、适用日期、必须可验证
    period_basis             TEXT NOT NULL,      -- ACCRUAL 权责发生制
    effective_at             TIMESTAMPTZ NOT NULL,
    supersedes_rule_code     TEXT,
    status                   TEXT NOT NULL DEFAULT 'ACTIVE'
);

-- 归因规则册（版本化；修订只改分类口径，不改写原始凭证与已关闭期间发布记录）
CREATE TABLE IF NOT EXISTS attribution_rule_book (
    id                 BIGSERIAL PRIMARY KEY,
    rule_book_version  TEXT NOT NULL UNIQUE,     -- 如 ARB-V1 / ARB-V2
    effective_at       TIMESTAMPTZ NOT NULL,
    status             TEXT NOT NULL,            -- ACTIVE / SUPERSEDED
    description        TEXT NOT NULL,
    rules              JSONB NOT NULL            -- 规则条目：服务对象/时间窗/合同条款/证据等级 → 分类
);

-- 成本分析工作区：一次“关闭期间 + 一组已发布范围 + 会计规则 + 归因规则版本”的分析
CREATE TABLE IF NOT EXISTS cost_workspace (
    id                        BIGSERIAL PRIMARY KEY,
    workspace_code            TEXT NOT NULL UNIQUE,
    name                      TEXT NOT NULL,
    period_id                 BIGINT NOT NULL REFERENCES accounting_period(id),
    accounting_rule_code      TEXT NOT NULL REFERENCES accounting_rule(rule_code),
    rule_book_version         TEXT NOT NULL,
    selected_release_codes    JSONB NOT NULL,    -- 选定的已发布范围编号集合
    status                    TEXT NOT NULL DEFAULT 'DRAFT', -- DRAFT/COMPILED/PUBLISHED/SUPERSEDED
    predecessor_workspace_id  BIGINT REFERENCES cost_workspace(id), -- 口径修订链路
    revision_note             TEXT,
    created_by                TEXT NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    compiled_at               TIMESTAMPTZ,
    summary                   JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- 成本受理台登记的原始凭证（发票/检测单/运输账单/仓储计费单/服务验收单/会计凭证/贷项/冲销）
CREATE TABLE IF NOT EXISTS cost_voucher (
    id                     BIGSERIAL PRIMARY KEY,
    workspace_id           BIGINT NOT NULL REFERENCES cost_workspace(id) ON DELETE CASCADE,
    voucher_code           TEXT NOT NULL,
    voucher_type           TEXT NOT NULL,  -- SUPPLIER_INVOICE/INSPECTION_ORDER/TRANSPORT_BILL/WAREHOUSE_BILL/SERVICE_ACCEPTANCE/ACCOUNTING_VOUCHER/CREDIT_NOTICE/REVERSAL_VOUCHER
    external_doc_no        TEXT NOT NULL,
    line_item_no           TEXT NOT NULL,
    contract_no            TEXT,
    source_system          TEXT NOT NULL,
    file_name              TEXT,
    file_hash              TEXT NOT NULL,  -- 附件指纹 SHA-256
    currency               TEXT NOT NULL,  -- CNY/USD/EUR...
    gross_amount           NUMERIC(18,4) NOT NULL,
    tax_amount             NUMERIC(18,4) NOT NULL DEFAULT 0,
    net_amount             NUMERIC(18,4) NOT NULL,
    exchange_rate          NUMERIC(18,6),  -- 可验证汇率；缺失则本币金额必须留空
    fx_rate_source         TEXT,
    fx_rate_date           DATE,
    fx_verified            BOOLEAN NOT NULL DEFAULT false,
    base_net_amount        NUMERIC(18,4),  -- 本币净额；汇率不可验证时为 NULL
    service_date           DATE,           -- 服务发生日
    acceptance_date        DATE,           -- 验收日
    posting_date          DATE,           -- 入账日
    service_period_start   DATE,
    service_period_end     DATE,
    target_batch_codes     JSONB NOT NULL DEFAULT '[]'::jsonb, -- 服务对象标识（批次，可多个）
    target_node            TEXT,
    evidence_grade         TEXT NOT NULL DEFAULT 'GRADE-B',    -- GRADE-A/GRADE-B/GRADE-C
    allocation_terms       JSONB NOT NULL DEFAULT '{}'::jsonb, -- 重量/箱数/合同分摊条款；为空即无分摊依据
    accrual_basis_verified BOOLEAN NOT NULL DEFAULT false,     -- 应计依据是否已证实
    referenced_doc_no      TEXT,           -- 贷项/冲销关联的原发票号（未关联则为空 → 人工配对集合）
    referenced_line_no     TEXT,
    service_no             TEXT,           -- 服务编号（双来源支付冲突比对用）
    payment_claim          JSONB NOT NULL DEFAULT '{}'::jsonb, -- 支付声明（来源、币种、税额、服务编号）
    extra                  JSONB NOT NULL DEFAULT '{}'::jsonb,
    registered_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, voucher_code)
);

-- 凭证身份簿：外部单号+行项目+合同号+文件指纹组成去重候选
CREATE TABLE IF NOT EXISTS voucher_identity (
    id              BIGSERIAL PRIMARY KEY,
    workspace_id    BIGINT NOT NULL REFERENCES cost_workspace(id) ON DELETE CASCADE,
    voucher_id      BIGINT NOT NULL REFERENCES cost_voucher(id) ON DELETE CASCADE,
    identity_key    TEXT NOT NULL,         -- externalDocNo|lineItem|contract|fileHash
    dedup_status    TEXT NOT NULL,         -- UNIQUE / DUPLICATE_CANDIDATE
    dedup_group     TEXT,
    note            TEXT,
    UNIQUE (workspace_id, voucher_id)
);

-- 归因结果：每条凭证行的直接归因/按规则分摊/仅相关不可归因判断
CREATE TABLE IF NOT EXISTS attribution_result (
    id                   BIGSERIAL PRIMARY KEY,
    workspace_id         BIGINT NOT NULL REFERENCES cost_workspace(id) ON DELETE CASCADE,
    voucher_id           BIGINT NOT NULL REFERENCES cost_voucher(id) ON DELETE CASCADE,
    rule_book_version    TEXT NOT NULL,
    rule_code            TEXT,
    classification       TEXT NOT NULL,     -- DIRECT / ALLOCATED / RELATED_ONLY
    batch_code           TEXT,              -- RELATED_ONLY 时为空
    allocated_base_amount NUMERIC(18,4),
    attribution_status   TEXT NOT NULL,     -- ATTRIBUTED / UNATTRIBUTABLE
    reason               TEXT,
    basis                JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- 期间校验器结论：应计期间、入账期间、期间差异标记与归属依据
CREATE TABLE IF NOT EXISTS period_assessment (
    id                   BIGSERIAL PRIMARY KEY,
    workspace_id         BIGINT NOT NULL REFERENCES cost_workspace(id) ON DELETE CASCADE,
    voucher_id           BIGINT NOT NULL REFERENCES cost_voucher(id) ON DELETE CASCADE,
    accounting_rule_code TEXT NOT NULL,
    accrual_period       TEXT,              -- 应计期间 yyyy-MM，依据不足时为空
    posting_period       TEXT,              -- 入账期间 yyyy-MM
    period_diff_flag     BOOLEAN NOT NULL DEFAULT false,
    cross_period_legal   BOOLEAN NOT NULL DEFAULT false,
    assessment_status    TEXT NOT NULL,     -- OK / PENDING
    reason               TEXT,
    basis                JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- 重复计量检测器结论：贷项/冲销/多方转付/拆分账单/双来源支付冲突
CREATE TABLE IF NOT EXISTS duplicate_finding (
    id              BIGSERIAL PRIMARY KEY,
    workspace_id    BIGINT NOT NULL REFERENCES cost_workspace(id) ON DELETE CASCADE,
    finding_type    TEXT NOT NULL,  -- EXACT_DUPLICATE/CREDIT_NOTICE_UNPAIRED/REVERSAL_UNPAIRED/MULTI_PARTY_TRANSFER/SPLIT_BILL/PAYMENT_CONFLICT
    dedup_group     TEXT,
    voucher_ids     JSONB NOT NULL,
    status          TEXT NOT NULL DEFAULT 'OPEN', -- OPEN（待人工配对）/RESOLVED/CONFLICT
    description     TEXT NOT NULL,
    resolution      JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 成本事实编译器发布行：已证实+期间归属完成+未被重复占用方可 CONFIRMED
CREATE TABLE IF NOT EXISTS cost_fact (
    id                        BIGSERIAL PRIMARY KEY,
    workspace_id              BIGINT NOT NULL REFERENCES cost_workspace(id) ON DELETE CASCADE,
    fact_code                 TEXT NOT NULL,
    voucher_id                BIGINT NOT NULL REFERENCES cost_voucher(id),
    batch_code                TEXT,           -- 仅相关不可归因/待核时为空
    classification            TEXT NOT NULL,
    voucher_type              TEXT NOT NULL,
    currency                  TEXT NOT NULL,
    original_net_amount       NUMERIC(18,4) NOT NULL,
    tax_amount                NUMERIC(18,4) NOT NULL,
    base_net_amount           NUMERIC(18,4),   -- 汇率不可验证时必须为 NULL
    fx_rate_source            TEXT,
    accrual_period            TEXT,
    posting_period            TEXT,
    period_diff_flag          BOOLEAN NOT NULL DEFAULT false,
    quality_status            TEXT NOT NULL,   -- CONFIRMED / PENDING
    pending_reasons           JSONB NOT NULL DEFAULT '[]'::jsonb, -- ATTRIBUTION_INSUFFICIENT/DUPLICATE_UNRESOLVED/VOUCHER_MISSING/ACCRUAL_BASIS_MISSING/FX_MISSING/PAYMENT_CONFLICT
    attribution_rule_version  TEXT NOT NULL,
    accounting_rule_code      TEXT NOT NULL,
    duplicate_finding_ids     JSONB NOT NULL DEFAULT '[]'::jsonb,
    published_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, fact_code)
);

-- 审计投影版本：口径修订生成新分析视图，旧口径事实只读保留
CREATE TABLE IF NOT EXISTS audit_view_version (
    id                     BIGSERIAL PRIMARY KEY,
    workspace_id           BIGINT NOT NULL REFERENCES cost_workspace(id) ON DELETE CASCADE,
    fact_id                BIGINT NOT NULL REFERENCES cost_fact(id) ON DELETE CASCADE,
    view_version           TEXT NOT NULL,      -- 出具该视图时的归因规则册版本
    projection             JSONB NOT NULL,     -- 凭证行/归因规则版本/期间判断/重复结论/质量状态快照
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_voucher_ws ON cost_voucher(workspace_id);
CREATE INDEX IF NOT EXISTS idx_fact_ws ON cost_fact(workspace_id);
CREATE INDEX IF NOT EXISTS idx_identity_ws ON voucher_identity(workspace_id);
CREATE INDEX IF NOT EXISTS idx_finding_ws ON duplicate_finding(workspace_id);
