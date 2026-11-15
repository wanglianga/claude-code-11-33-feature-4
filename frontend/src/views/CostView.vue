<template>
  <div>
    <h2 class="page-title">召回处置成本证据归因与会计期间一致性台</h2>
    <p class="page-desc">
      以<b>凭证行、归因依据、期间判断</b>共同构成成本事实：相似金额不自动并单，无分摊依据不平均分摊，
      贷项未配对不冲减，汇率不可验证本币留空。本台不产生付款、报销、销毁、退运、调拨或审批动作。
    </p>

    <!-- 创建工作区 -->
    <div class="card">
      <h3>新建成本分析工作区（关闭期间 + 已发布范围 + 会计规则）</h3>
      <div class="form-row">
        <label class="field">关闭会计期间
          <select v-model="form.periodId">
            <option v-for="p in periods" :key="p.id" :value="p.id">
              {{ p.periodCode }}（{{ p.status === 'CLOSED' ? '已关账' : '未关账' }}，{{ p.periodStart }} ~ {{ p.periodEnd }}）
            </option>
          </select>
        </label>
        <label class="field">会计规则
          <select v-model="form.accountingRuleCode">
            <option v-for="r in accountingRules" :key="r.ruleCode" :value="r.ruleCode">
              {{ r.ruleCode }} · {{ r.ruleName }}（跨期应计 {{ r.allowsCrossPeriod ? '允许' : '不允许' }}）
            </option>
          </select>
        </label>
        <label class="field">归因规则册版本
          <select v-model="form.ruleBookVersion">
            <option v-for="b in ruleBooks" :key="b.ruleBookVersion" :value="b.ruleBookVersion">
              {{ b.ruleBookVersion }}（{{ b.status }}）
            </option>
          </select>
        </label>
        <label class="field">名称
          <input v-model="form.name" style="width:220px" />
        </label>
      </div>
      <div class="form-row" style="margin-top:12px">
        <fieldset style="border:1px solid var(--line);border-radius:8px;padding:10px 14px">
          <legend class="small">选择已发布范围（可多选）</legend>
          <label v-for="r in releases" :key="r.id" style="margin-right:18px">
            <input type="checkbox" :value="r.releaseCode" v-model="form.releaseCodes" />
            <span class="mono">{{ r.releaseCode }}</span>
            <span class="small">（V{{ r.versionNo }}，{{ r.itemCount }} 项，{{ r.status }}）</span>
          </label>
        </fieldset>
      </div>
      <div class="form-row" style="margin-top:12px">
        <button class="btn" @click="createWorkspace">创建工作区</button>
        <button class="btn secondary" @click="loadDemo">载入六类验收演示场景</button>
        <span v-if="error" class="small" style="color:var(--red)">{{ error }}</span>
      </div>
    </div>

    <!-- 工作区列表 -->
    <div class="card">
      <h3>成本工作区（{{ workspaces.length }}）</h3>
      <table>
        <thead><tr>
          <th>编号</th><th>名称</th><th>关闭期间</th><th>会计规则</th><th>归因口径</th>
          <th>选定范围</th><th>状态</th><th>编译时间</th><th></th>
        </tr></thead>
        <tbody>
          <tr v-for="w in workspaces" :key="w.id" :style="w.id===currentId ? 'background:rgba(79,140,255,.08)' : ''">
            <td class="mono">{{ w.workspaceCode }}</td>
            <td>{{ w.name }}</td>
            <td class="mono">{{ w.periodCode }}</td>
            <td class="mono small">{{ w.accountingRuleCode }}</td>
            <td class="mono">{{ w.ruleBookVersion }}</td>
            <td class="small mono">{{ (w.selectedReleaseCodes || []).join(', ') }}</td>
            <td><span :class="['tag', wsTag(w.status)]">{{ wsLabel(w.status) }}</span></td>
            <td class="small">{{ fmt(w.compiledAt) }}</td>
            <td><a href="#" @click.prevent="open(w.id)">打开 →</a></td>
          </tr>
        </tbody>
      </table>
    </div>

    <template v-if="current">
      <!-- 工作区操作条 -->
      <div class="card">
        <h3>{{ current.workspaceCode }} · {{ current.name }}
          <span :class="['tag', wsTag(current.status)]" style="margin-left:8px">{{ wsLabel(current.status) }}</span>
          <span v-if="current.predecessorWorkspaceId" class="tag purple" style="margin-left:8px">
            修订自 CW-{{ String(current.predecessorWorkspaceId).padStart(3, '0') }}
          </span>
        </h3>
        <p class="small" style="margin:0 0 12px">
          关闭期间 <b class="mono">{{ current.periodCode }}</b>（{{ current.periodStart }} ~ {{ current.periodEnd }}，
          {{ current.periodStatus === 'CLOSED' ? '已关账' : '未关账' }}）·
          会计规则 <b class="mono">{{ current.accountingRuleCode }}</b> ·
          归因口径 <b class="mono">{{ current.ruleBookVersion }}</b>
          <span v-if="current.revisionNote"> · 修订说明：{{ current.revisionNote }}</span>
        </p>
        <div class="form-row">
          <button class="btn" :disabled="working || locked" @click="compile">{{ working ? '编译中…' : '运行归因/期间/重复/事实编译' }}</button>
          <button class="btn" :disabled="locked || current.status !== 'COMPILED'" @click="publish">发布并冻结事实</button>
          <button class="btn secondary" :disabled="locked" @click="showRevise = !showRevise">
            {{ showRevise ? '取消口径修订' : '期末后口径修订（新建分析视图）' }}
          </button>
        </div>
        <div v-if="showRevise" class="form-row" style="margin-top:12px">
          <label class="field">新归因规则册版本
            <select v-model="reviseVersion">
              <option v-for="b in ruleBooks.filter(x => x.ruleBookVersion !== current.ruleBookVersion)"
                      :key="b.ruleBookVersion" :value="b.ruleBookVersion">{{ b.ruleBookVersion }}</option>
            </select>
          </label>
          <button class="btn" @click="revise">创建修订工作区（旧口径事实只读保留）</button>
        </div>
        <div v-if="summary" class="grid-3" style="margin-top:16px">
          <div class="stat"><div class="num" style="color:var(--green)">{{ summary.confirmedCount }}</div><div class="lbl">已证实成本事实 / 本币合计 {{ summary.confirmedBaseTotal }}</div></div>
          <div class="stat"><div class="num" style="color:var(--amber)">{{ summary.pendingCount }}</div><div class="lbl">待核金额（不进正式事实）</div></div>
          <div class="stat"><div class="num" style="color:var(--red)">{{ summary.openFindingCount }}</div><div class="lbl">未解决重复/冲突结论</div></div>
        </div>
      </div>

      <div class="tabs">
        <button v-for="t in tabs" :key="t.key" :class="{active: tab===t.key}" @click="switchTab(t.key)">{{ t.label }}</button>
      </div>

      <!-- 成本事实 -->
      <div v-if="tab==='facts'" class="card">
        <h3>成本事实（CONFIRMED 须同时满足归因+期间+无重复占用+汇率可验证）</h3>
        <table>
          <thead><tr>
            <th>事实编号</th><th>凭证/行</th><th>类型</th><th>分类</th><th>批次</th>
            <th>币种</th><th class="right">原币净额</th><th class="right">本币净额</th>
            <th>应计/入账期间</th><th>期间差异</th><th>质量状态</th><th>待核原因</th><th></th>
          </tr></thead>
          <tbody>
            <tr v-for="f in facts" :key="f.id">
              <td class="mono">{{ f.factCode }}</td>
              <td class="small mono">{{ f.voucherCode }}<br/>{{ f.externalDocNo }} / {{ f.lineItemNo }}</td>
              <td><span class="tag gray">{{ voucherTypeLabel(f.voucherType) }}</span></td>
              <td><span class="tag cyan">{{ classLabel(f.classification) }}</span></td>
              <td class="mono">{{ f.batchCode || '—' }}</td>
              <td>{{ f.currency }}</td>
              <td class="right">{{ f.originalNetAmount }}</td>
              <td class="right">{{ f.baseNetAmount == null ? '（留空·待核）' : f.baseNetAmount }}</td>
              <td class="small">{{ f.accrualPeriod || '—' }} / {{ f.postingPeriod || '—' }}</td>
              <td><span v-if="f.periodDiffFlag" class="tag purple">跨期差异</span><span v-else class="muted">否</span></td>
              <td><span :class="['tag', f.qualityStatus==='CONFIRMED' ? 'green' : 'amber']">
                {{ f.qualityStatus === 'CONFIRMED' ? '已证实' : '待核' }}</span></td>
              <td class="small">
                <span v-for="r in f.pendingReasons" :key="r" class="tag red" style="margin:0 4px 4px 0">{{ reasonLabel(r) }}</span>
                <span v-if="!f.pendingReasons.length" class="muted">—</span>
              </td>
              <td><a href="#" @click.prevent="openAudit(f.id)">审计展开 →</a></td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 归因结果 -->
      <div v-if="tab==='attr'" class="card">
        <h3>归因规则册判断（{{ current.ruleBookVersion }}）</h3>
        <table>
          <thead><tr><th>凭证</th><th>命中规则</th><th>分类</th><th>批次</th><th class="right">分摊本币</th><th>状态</th><th>依据说明</th></tr></thead>
          <tbody>
            <tr v-for="a in attributions" :key="a.id">
              <td class="mono small">{{ a.voucherCode }}</td>
              <td class="mono">{{ a.ruleCode || '—' }}</td>
              <td><span class="tag cyan">{{ classLabel(a.classification) }}</span></td>
              <td class="mono">{{ a.batchCode || '—' }}</td>
              <td class="right">{{ a.allocatedBaseAmount == null ? '—' : a.allocatedBaseAmount }}</td>
              <td><span :class="['tag', a.attributionStatus==='ATTRIBUTED' ? 'green' : 'amber']">
                {{ a.attributionStatus === 'ATTRIBUTED' ? '可归因' : '不可归因' }}</span></td>
              <td class="small">{{ a.reason }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 期间校验 -->
      <div v-if="tab==='period'" class="card">
        <h3>期间校验器结论</h3>
        <table>
          <thead><tr><th>凭证</th><th>应计期间</th><th>入账期间</th><th>期间差异</th><th>合法跨期</th><th>状态</th><th>归属依据</th></tr></thead>
          <tbody>
            <tr v-for="p in periodRows" :key="p.id">
              <td class="mono small">{{ p.voucherCode }}</td>
              <td class="mono">{{ p.accrualPeriod || '—' }}</td>
              <td class="mono">{{ p.postingPeriod || '—' }}</td>
              <td><span v-if="p.periodDiffFlag" class="tag purple">是</span><span v-else class="muted">否</span></td>
              <td><span v-if="p.crossPeriodLegal" class="tag green">合法跨期应计</span><span v-else class="muted">—</span></td>
              <td><span :class="['tag', p.assessmentStatus==='OK' ? 'green' : 'amber']">{{ p.assessmentStatus === 'OK' ? '依据完整' : '待核' }}</span></td>
              <td class="small">{{ p.reason }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 重复计量 -->
      <div v-if="tab==='dup'" class="card">
        <h3>重复计量检测器结论（{{ findings.length }}）</h3>
        <div v-for="x in findings" :key="x.id" :class="['gap-item', x.status==='RESOLVED' ? 'warning' : '']">
          <div>
            <span :class="['tag', x.status==='CONFLICT' ? 'red' : x.status==='OPEN' ? 'amber' : 'green']">{{ findingLabel(x.findingType) }}</span>
            <span class="tag gray" style="margin-left:8px">{{ x.status === 'OPEN' ? '待人工配对/核对' : x.status === 'CONFLICT' ? '冲突保留' : '已配对解决' }}</span>
            <span class="mono muted small" style="margin-left:8px">{{ x.dedupGroup }} · 凭证 ID {{ (x.voucherIds || []).join(', ') }}</span>
          </div>
          <div class="small" style="margin-top:6px">{{ x.description }}</div>
        </div>
      </div>

      <!-- 身份簿 -->
      <div v-if="tab==='identity'" class="card">
        <h3>凭证身份簿（外部单号 | 行项目 | 合同号 | 文件指纹；相似金额不作为同一笔）</h3>
        <table>
          <thead><tr><th>凭证</th><th>外部单号/行</th><th>身份键</th><th>去重状态</th><th>去重组</th></tr></thead>
          <tbody>
            <tr v-for="i in identities" :key="i.id">
              <td class="mono small">{{ i.voucherCode }}</td>
              <td class="mono small">{{ i.externalDocNo }} / {{ i.lineItemNo }}</td>
              <td class="mono small">{{ i.identityKey }}</td>
              <td><span :class="['tag', i.dedupStatus==='UNIQUE' ? 'green' : 'red']">
                {{ i.dedupStatus === 'UNIQUE' ? '唯一' : '重复候选' }}</span></td>
              <td class="mono">{{ i.dedupGroup || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 凭证登记 -->
      <div v-if="tab==='voucher'" class="card">
        <h3>成本受理台 · 登记原始凭证（{{ vouchers.length }}）</h3>
        <div class="grid-2">
          <label class="field">凭证类型
            <select v-model="vForm.voucherType">
              <option v-for="t in voucherTypes" :key="t" :value="t">{{ voucherTypeLabel(t) }}</option>
            </select>
          </label>
          <label class="field">凭证编号
            <input v-model="vForm.voucherCode" placeholder="CV-XXX" />
          </label>
          <label class="field">外部单号 / 行项目
            <input v-model="vForm.externalDocNo" placeholder="外部单号" style="margin-bottom:8px" />
            <input v-model="vForm.lineItemNo" placeholder="行项目号" />
          </label>
          <label class="field">合同号
            <input v-model="vForm.contractNo" placeholder="可空；直接归因需要合同条款" />
          </label>
          <label class="field">币种 / 净额 / 税额
            <input v-model="vForm.currency" placeholder="CNY/USD/EUR" style="margin-bottom:8px" />
            <input v-model.number="vForm.netAmount" placeholder="净额" type="number" style="margin-bottom:8px" />
            <input v-model.number="vForm.taxAmount" placeholder="税额" type="number" />
          </label>
          <label class="field">服务/验收/入账日期
            <input v-model="vForm.serviceDate" type="date" style="margin-bottom:8px" />
            <input v-model="vForm.acceptanceDate" type="date" style="margin-bottom:8px" />
            <input v-model="vForm.postingDate" type="date" />
          </label>
          <label class="field">服务对象批次（逗号分隔，需在选定范围内）
            <input v-model="targetText" placeholder="FG-C02,FG-C03" />
          </label>
          <label class="field">证据等级 / 应计依据
            <select v-model="vForm.evidenceGrade" style="margin-bottom:8px">
              <option>GRADE-A</option><option>GRADE-B</option><option>GRADE-C</option>
            </select>
            <label><input type="checkbox" v-model="vForm.accrualBasisVerified" /> 应计依据已证实</label>
          </label>
          <label class="field">外币汇率（来源/日期/汇率，可验证才填）
            <input v-model="vForm.fxRateSource" placeholder="如 PBoC_MIDDLE_RATE" style="margin-bottom:8px" />
            <input v-model="vForm.fxRateDate" type="date" style="margin-bottom:8px" />
            <input v-model.number="vForm.exchangeRate" placeholder="汇率" type="number" />
          </label>
          <label class="field">分摊条款 JSON（多批次且有重量/箱数/合同比例时填写）
            <input v-model="allocationText" placeholder='{"basis":"WEIGHT","shares":[...]}' />
          </label>
        </div>
        <div class="form-row" style="margin-top:12px">
          <button class="btn" :disabled="locked" @click="registerVoucher">登记凭证</button>
          <span v-if="voucherMsg" class="small" style="color:var(--green)">{{ voucherMsg }}</span>
        </div>
      </div>
    </template>

    <!-- 审计下钻抽屉 -->
    <div v-if="audit" class="audit-mask" @click.self="audit=null">
      <div class="audit-panel">
        <h3>审计投影 · <span class="mono">{{ audit.fact.factCode }}</span>
          <button class="btn secondary" style="float:right" @click="audit=null">关闭</button>
        </h3>
        <div class="small" style="margin-bottom:10px">
          质量状态 <b>{{ audit.fact.qualityStatus }}</b> · 口径版本
          <b class="mono">{{ audit.fact.attributionRuleVersion }}</b> · 会计规则
          <b class="mono">{{ audit.fact.accountingRuleCode }}</b>
        </div>
        <h4>原始凭证行（含文件指纹）</h4>
        <pre class="payload">{{ JSON.stringify({
          voucherCode: audit.voucher.voucherCode, type: audit.voucher.voucherType,
          externalDocNo: audit.voucher.externalDocNo, lineItemNo: audit.voucher.lineItemNo,
          contractNo: audit.voucher.contractNo, sourceSystem: audit.voucher.sourceSystem,
          fileHash: audit.voucher.fileHash, currency: audit.voucher.currency,
          netAmount: audit.voucher.netAmount, fxVerified: audit.voucher.fxVerified,
          fxRateSource: audit.voucher.fxRateSource, fxRateDate: audit.voucher.fxRateDate
        }, null, 2) }}</pre>
        <h4>归因规则版本与依据</h4>
        <pre class="payload">{{ JSON.stringify(audit.attribution, null, 2) }}</pre>
        <h4>期间判断</h4>
        <pre class="payload">{{ JSON.stringify(audit.periodAssessment, null, 2) }}</pre>
        <h4>重复计量结论</h4>
        <pre class="payload">{{ JSON.stringify(audit.duplicateFindings, null, 2) }}</pre>
        <h4>历史分析视图（口径修订后多版本并存）</h4>
        <div v-for="p in audit.auditProjections" :key="p.id" class="small" style="margin-bottom:6px">
          <span class="tag purple">{{ p.viewVersion }}</span>
          <span class="muted"> 生成于 {{ fmt(p.createdAt) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { api } from '../api.js'

const periods = ref([])
const accountingRules = ref([])
const ruleBooks = ref([])
const releases = ref([])
const workspaces = ref([])
const currentId = ref(null)
const current = ref(null)
const facts = ref([])
const attributions = ref([])
const periodRows = ref([])
const findings = ref([])
const identities = ref([])
const vouchers = ref([])
const audit = ref(null)
const tab = ref('facts')
const working = ref(false)
const error = ref('')
const showRevise = ref(false)
const reviseVersion = ref('')
const voucherMsg = ref('')
const targetText = ref('')
const allocationText = ref('')

const tabs = [
  { key: 'facts', label: '成本事实' },
  { key: 'attr', label: '归因判断' },
  { key: 'period', label: '期间校验' },
  { key: 'dup', label: '重复计量' },
  { key: 'identity', label: '凭证身份簿' },
  { key: 'voucher', label: '凭证登记' }
]
const voucherTypes = ['SUPPLIER_INVOICE', 'INSPECTION_ORDER', 'TRANSPORT_BILL', 'WAREHOUSE_BILL',
  'SERVICE_ACCEPTANCE', 'ACCOUNTING_VOUCHER', 'DISPOSAL_BILL', 'THIRD_PARTY_SERVICE',
  'CREDIT_NOTICE', 'REVERSAL_VOUCHER']
const form = ref({
  periodId: null, accountingRuleCode: 'AR-ACCRUAL-V1', ruleBookVersion: 'ARB-V1',
  name: '成本归因工作区', releaseCodes: []
})
const vForm = ref({
  voucherType: 'TRANSPORT_BILL', voucherCode: '', externalDocNo: '', lineItemNo: 'L1', contractNo: '',
  currency: 'CNY', netAmount: null, taxAmount: 0, serviceDate: '', acceptanceDate: '', postingDate: '',
  evidenceGrade: 'GRADE-A', accrualBasisVerified: true, fxRateSource: '', fxRateDate: '', exchangeRate: null
})

const summary = computed(() => current.value?.summary || null)
const locked = computed(() => ['PUBLISHED', 'SUPERSEDED'].includes(current.value?.status))
const fmt = (t) => t ? t.replace('T', ' ').slice(0, 16) : ''
const wsLabel = (s) => ({ DRAFT: '草稿', COMPILED: '已编译', PUBLISHED: '已发布冻结', SUPERSEDED: '已被替代' }[s] || s)
const wsTag = (s) => s === 'PUBLISHED' ? 'green' : s === 'COMPILED' ? 'cyan' : s === 'SUPERSEDED' ? 'gray' : 'amber'
const voucherTypeLabel = (t) => ({
  SUPPLIER_INVOICE: '供应商发票', INSPECTION_ORDER: '检测单', TRANSPORT_BILL: '运输账单',
  WAREHOUSE_BILL: '仓储计费单', SERVICE_ACCEPTANCE: '服务验收单', ACCOUNTING_VOUCHER: '会计凭证',
  DISPOSAL_BILL: '报废处理单', THIRD_PARTY_SERVICE: '第三方服务凭证',
  CREDIT_NOTICE: '贷项通知', REVERSAL_VOUCHER: '冲销凭证'
}[t] || t)
const classLabel = (c) => ({ DIRECT: '直接归因', ALLOCATED: '按规则分摊', RELATED_ONLY: '仅相关不可归因' }[c] || c)
const reasonLabel = (r) => ({
  ATTRIBUTION_INSUFFICIENT: '归属依据不足', DUPLICATE_UNRESOLVED: '重复关系未解',
  VOUCHER_MISSING: '关键凭证缺失', ACCRUAL_BASIS_MISSING: '应计依据缺失',
  FX_MISSING: '汇率不可验证', PAYMENT_CONFLICT: '支付状态冲突'
}[r] || r)
const findingLabel = (f) => ({
  EXACT_DUPLICATE: '精确重复（身份键一致）', CREDIT_NOTICE_UNPAIRED: '未配对贷项通知',
  REVERSAL_UNPAIRED: '未配对冲销凭证', MULTI_PARTY_TRANSFER: '同一服务多方转付',
  SPLIT_BILL: '拆分账单', PAYMENT_CONFLICT: '双来源支付冲突'
}[f] || f)

async function loadMeta() {
  periods.value = await api.get('/api/cost/meta/periods')
  accountingRules.value = await api.get('/api/cost/meta/accounting-rules')
  ruleBooks.value = await api.get('/api/cost/meta/rule-books')
  releases.value = await api.get('/api/cost/meta/releases')
  if (!form.value.periodId && periods.value.length) form.value.periodId = periods.value[0].id
  workspaces.value = await api.get('/api/cost/workspaces')
}

async function createWorkspace() {
  error.value = ''
  try {
    const r = await api.post('/api/cost/workspaces', form.value)
    await loadMeta()
    await open(r.workspaceId)
  } catch (e) { error.value = e.message }
}

async function loadDemo() {
  error.value = ''
  try {
    const r = await api.post('/api/cost/demo-scenario', {})
    await loadMeta()
    await open(r.workspaceId)
  } catch (e) { error.value = e.message }
}

async function open(id) {
  currentId.value = id
  current.value = await api.get('/api/cost/workspaces/' + id)
  await switchTab(tab.value)
}

async function switchTab(key) {
  tab.value = key
  if (!currentId.value) return
  const id = currentId.value
  if (key === 'facts') facts.value = await api.get(`/api/cost/workspaces/${id}/facts`)
  if (key === 'attr') attributions.value = await api.get(`/api/cost/workspaces/${id}/attributions`)
  if (key === 'period') periodRows.value = await api.get(`/api/cost/workspaces/${id}/periods`)
  if (key === 'dup') findings.value = await api.get(`/api/cost/workspaces/${id}/findings`)
  if (key === 'identity') identities.value = await api.get(`/api/cost/workspaces/${id}/identities`)
  if (key === 'voucher') vouchers.value = await api.get(`/api/cost/workspaces/${id}/vouchers`)
}

async function compile() {
  working.value = true
  try {
    await api.post(`/api/cost/workspaces/${currentId.value}/compile`, {})
    await open(currentId.value)
  } finally { working.value = false }
}

async function publish() {
  await api.post(`/api/cost/workspaces/${currentId.value}/publish`, {})
  await loadMeta()
  await open(currentId.value)
}

async function revise() {
  const r = await api.post(`/api/cost/workspaces/${currentId.value}/revise`,
    { ruleBookVersion: reviseVersion.value, note: '期末后口径修订，原始凭证不变' })
  showRevise.value = false
  await loadMeta()
  await open(r.workspaceId)
}

async function registerVoucher() {
  voucherMsg.value = ''
  error.value = ''
  const body = { ...vForm.value }
  body.targetBatchCodes = targetText.value.split(',').map(s => s.trim()).filter(Boolean)
  body.grossAmount = (vForm.value.netAmount || 0) + (vForm.value.taxAmount || 0)
  body.fileHash = 'sha256-' + (vForm.value.voucherCode || 'manual').toLowerCase()
  body.fileName = (vForm.value.voucherCode || 'voucher') + '.pdf'
  body.sourceSystem = 'ERP'
  if (allocationText.value) {
    try { body.allocationTerms = JSON.parse(allocationText.value) } catch { error.value = '分摊条款 JSON 格式错误'; return }
  }
  if (vForm.value.currency === 'CNY') {
    body.baseNetAmount = vForm.value.netAmount
  } else if (vForm.value.fxRateSource && vForm.value.fxRateDate && vForm.value.exchangeRate) {
    body.fxVerified = true
    body.baseNetAmount = null
  } else {
    body.fxVerified = false
    body.baseNetAmount = null
  }
  try {
    await api.post(`/api/cost/workspaces/${currentId.value}/vouchers`, body)
    voucherMsg.value = '凭证已登记，重新编译后进入事实判断'
    await switchTab('voucher')
  } catch (e) { error.value = e.message }
}

async function openAudit(factId) {
  audit.value = await api.get(`/api/cost/workspaces/${currentId.value}/facts/${factId}/audit`)
}

onMounted(loadMeta)
</script>

<style scoped>
.audit-mask {
  position: fixed; inset: 0; background: rgba(5, 10, 18, .65);
  z-index: 100; display: flex; justify-content: flex-end;
}
.audit-panel {
  width: 720px; max-width: 92vw; height: 100%; overflow: auto;
  background: var(--panel); border-left: 1px solid var(--line);
  padding: 22px 24px;
}
fieldset label { font-size: 13px; color: var(--text); }
</style>
