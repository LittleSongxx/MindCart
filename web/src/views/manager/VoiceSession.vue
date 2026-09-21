<template>
  <div>
    <div class="page-heading">
      <div>
        <h2>语音会话与归因</h2>
        <p>语音导购的会话流水、成交归因与转化指标：每条会话可回放"用户说了什么 → AI 答了什么 → 下了哪一单"。</p>
      </div>
    </div>

    <!-- 运营汇总 -->
    <div class="card stats-row">
      <div class="stat"><span>会话总数</span><b>{{ data.stats.sessions ?? '—' }}</b></div>
      <div class="stat"><span>成交会话</span><b>{{ data.stats.orderedSessions ?? '—' }}</b></div>
      <div class="stat"><span>转化率</span><b>{{ conversion }}</b></div>
      <div class="stat"><span>消息条数</span><b>{{ data.stats.messages ?? '—' }}</b></div>
      <div class="stat"><span>派发单数</span><b>{{ data.stats.dispatched ?? '—' }}</b></div>
      <div class="stat"><span>成交单数</span><b>{{ data.stats.succeeded ?? '—' }}</b></div>
      <div class="stat"><span>语音 GMV</span><b>¥{{ data.stats.gmv ?? '—' }}</b></div>
      <div class="stat"><span>近 24h 会话</span><b>{{ data.stats.last24hSessions ?? '—' }}</b></div>
    </div>

    <div class="card search-card">
      <el-input v-model="data.userId" clearable @keyup.enter="load" class="search-item" placeholder="按用户 ID 过滤"></el-input>
      <el-select v-model="data.outcome" clearable placeholder="会话结果" class="search-item" @change="load">
        <el-option label="已成交" value="ORDERED"></el-option>
        <el-option label="未成交" value="ABANDONED"></el-option>
      </el-select>
      <el-button type="primary" plain @click="load">查询</el-button>
      <el-button plain @click="reset">重置</el-button>
    </div>

    <div class="card">
      <el-table stripe :data="data.tableData">
        <el-table-column prop="session_id" label="会话 ID" width="220" show-overflow-tooltip />
        <el-table-column prop="user_id" label="用户" width="80" />
        <el-table-column prop="channel" label="入口" width="120">
          <template #default="scope">{{ channelName(scope.row.channel) }}</template>
        </el-table-column>
        <el-table-column prop="phase" label="阶段" width="130" />
        <el-table-column prop="msg_count" label="消息" width="80" />
        <el-table-column prop="order_count" label="成交单" width="90" />
        <el-table-column prop="order_amount" label="成交额" width="110">
          <template #default="scope">
            <span v-if="Number(scope.row.order_amount) > 0" style="color:#f56c6c">¥{{ scope.row.order_amount }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="outcome" label="结果" width="110">
          <template #default="scope">
            <el-tag v-if="scope.row.outcome === 'ORDERED'" type="success">已成交</el-tag>
            <el-tag v-else-if="scope.row.outcome === 'ABANDONED'" type="info">未成交</el-tag>
            <el-tag v-else type="warning">进行中</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="started_at" label="开始时间" width="180" />
        <el-table-column prop="last_active_at" label="最后活跃" width="180" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="scope">
            <el-button type="info" circle :icon="View" @click="openDetail(scope.row)"></el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <div class="card" v-if="data.total">
      <el-pagination @current-change="load" background layout="prev, pager, next"
                     :page-size="data.pageSize" v-model:current-page="data.pageNum" :total="data.total" />
    </div>

    <!-- 会话详情：对话流水 + 归因订单 -->
    <el-dialog title="语音会话详情" v-model="data.detailVisible" width="920px">
      <h4>对话流水</h4>
      <div v-if="!data.messages.length" class="empty">暂无消息（该会话尚未产生对话）</div>
      <div v-for="m in data.messages" :key="m.turn + '-' + m.created_at" class="msg-row">
        <el-tag :type="roleType(m.role)" size="small" class="msg-role">{{ roleName(m.role) }}</el-tag>
        <span class="msg-intent">{{ m.intent || '' }}</span>
        <div class="msg-text">{{ m.content_text }}</div>
      </div>

      <h4 style="margin-top: 16px">归因订单</h4>
      <div v-if="!data.orders.length" class="empty">该会话没有下单动作</div>
      <el-table v-else :data="data.orders" size="small">
        <el-table-column prop="action" label="动作" width="90">
          <template #default="scope">{{ scope.row.action === 'CANCEL' ? '取消' : '下单' }}</template>
        </el-table-column>
        <el-table-column prop="order_no" label="订单号" width="200" />
        <el-table-column prop="product_name" label="商品" show-overflow-tooltip />
        <el-table-column prop="quantity" label="数量" width="70" />
        <el-table-column prop="total_amount" label="金额" width="100" />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="scope">
            <el-tag :type="orderStatusType(scope.row.status)" size="small">{{ orderStatusName(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="request_id" label="幂等键" width="220" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive } from 'vue'
import request from '@/utils/request.js'
import { View } from '@element-plus/icons-vue'

const data = reactive({
  stats: {},
  tableData: [],
  total: 0,
  pageNum: 1,
  pageSize: 10,
  userId: null,
  outcome: null,
  detailVisible: false,
  messages: [],
  orders: [],
  current: null
})

const conversion = computed(() => {
  const s = Number(data.stats.sessions || 0)
  const o = Number(data.stats.orderedSessions || 0)
  return s === 0 ? '—' : ((o / s) * 100).toFixed(1) + '%'
})

const channelName = (c) => ({ HOME_ENTRY: '首页入口', PRODUCT_PAGE: '商品详情页', SEARCH_FALLBACK: '搜索兜底' }[c] || c)
const roleName = (r) => ({ USER: '用户', ASSISTANT: '导购', SYSTEM: '系统' }[r] || r)
const roleType = (r) => ({ USER: 'primary', ASSISTANT: 'success', SYSTEM: 'warning' }[r] || 'info')
const orderStatusName = (s) => ({ SUCCEEDED: '已成交', FAILED: '失败', DISPATCHED: '已派发' }[s] || s)
const orderStatusType = (s) => ({ SUCCEEDED: 'success', FAILED: 'danger', DISPATCHED: 'warning' }[s] || 'info')

const loadStats = () => {
  request.get('/voice/admin/stats').then(res => {
    if (res.code === '200') data.stats = res.data || {}
  })
}

const load = () => {
  request.get('/voice/admin/sessions', {
    params: {
      page: data.pageNum, size: data.pageSize,
      userId: data.userId || undefined, outcome: data.outcome || undefined
    }
  }).then(res => {
    if (res.code === '200') {
      data.tableData = res.data?.list || []
      data.total = res.data?.total || 0
    }
  })
}

const reset = () => {
  data.userId = null
  data.outcome = null
  data.pageNum = 1
  load()
}

const openDetail = (row) => {
  data.current = row
  request.get('/voice/admin/sessions/' + encodeURIComponent(row.session_id)).then(res => {
    if (res.code === '200') {
      data.messages = res.data?.messages || []
      data.orders = res.data?.orders || []
      data.detailVisible = true
    }
  })
}

loadStats()
load()
</script>

<style scoped>
.stats-row {
  display: flex;
  gap: 28px;
  flex-wrap: wrap;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat span {
  color: #909399;
  font-size: 13px;
}
.stat b {
  font-size: 20px;
  color: #303133;
}
.search-item {
  width: 200px;
  margin-right: 10px;
}
.msg-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
}
.msg-role {
  flex: 0 0 auto;
}
.msg-intent {
  color: #909399;
  font-size: 12px;
  flex: 0 0 auto;
}
.msg-text {
  white-space: pre-wrap;
  word-break: break-all;
}
.empty {
  color: #909399;
  font-size: 13px;
  padding: 8px 0;
}
</style>
