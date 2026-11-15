<template>
  <div>
    <h2 class="page-title">接入登记台</h2>
    <p class="page-desc">每个来源包保留原始载荷、接收时间、事件时间、来源修订号、文件哈希与解析版本；哈希通过但解析规则升级会标记为解析差异。</p>

    <div class="card">
      <h3>解析规则版本</h3>
      <table>
        <thead><tr><th>规则版本</th><th>生效时间</th><th>影响事实类型</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="r in rules" :key="r.version">
            <td class="mono">{{ r.version }}</td>
            <td class="nowrap">{{ fmt(r.effectiveAt) }}</td>
            <td><span v-for="t in r.affectedTypes.split(',')" :key="t" class="tag cyan" style="margin-right:5px">{{ t }}</span></td>
            <td class="muted">{{ r.description }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h3>来源包登记（{{ packages.length }}）</h3>
      <table>
        <thead><tr>
          <th>包编号</th><th>来源系统</th><th>修订号</th><th>解析版本</th><th>解析状态</th>
          <th>接收时间</th><th>事件时间</th><th>哈希（SHA-256）</th><th></th>
        </tr></thead>
        <tbody>
          <tr v-for="p in packages" :key="p.packageCode">
            <td class="mono">{{ p.packageCode }}</td>
            <td><span class="tag gray">{{ p.sourceSystem }}</span></td>
            <td class="mono">{{ p.sourceRevision }}</td>
            <td class="mono">{{ p.parseRuleVersion }}</td>
            <td><span :class="['tag', parseTag(p.parseStatus)]">{{ parseLabel(p.parseStatus) }}</span></td>
            <td class="nowrap">{{ fmt(p.receivedAt) }}</td>
            <td class="nowrap">{{ fmt(p.eventTime) }}</td>
            <td class="mono small">{{ (p.payloadHash || '').slice(0, 16) }}…</td>
            <td><a @click.prevent="open(p.packageCode)" href="#">原始载荷</a></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="current" class="card">
      <h3>原始载荷 · <span class="mono">{{ current.packageCode }}</span>
        <span class="small" style="margin-left:10px">hash={{ current.payloadHash }}</span>
      </h3>
      <pre class="payload">{{ JSON.stringify(current.payload, null, 2) }}</pre>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const packages = ref([])
const rules = ref([])
const current = ref(null)

const fmt = (t) => t ? t.replace('T', ' ').slice(0, 16) : ''
const parseLabel = (s) => ({ PARSED: '已解析', PARSE_DIFF: '解析差异', FAILED: '解析失败' }[s] || s)
const parseTag = (s) => s === 'PARSE_DIFF' ? 'amber' : s === 'FAILED' ? 'red' : 'green'

async function open(code) {
  current.value = await api.get('/api/source-packages/' + code)
}

onMounted(async () => {
  packages.value = await api.get('/api/source-packages')
  rules.value = await api.get('/api/meta/parse-rules')
})
</script>
