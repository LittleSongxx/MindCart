<template>
  <div class="voice-panel">
    <!-- 语音导购主交互区 -->
    <div class="voice-card card">
      <div class="section-heading">
        <div>
          <span>VOICE MODE</span>
          <h2>对着麦克风说出需求</h2>
        </div>
        <div class="online-dot">{{ statusText }}</div>
      </div>

      <div class="mic-row">
        <el-button
          :type="recording ? 'danger' : 'primary'"
          circle
          class="mic-btn"
          :loading="state.status === 'CONNECTING'"
          @click="toggleMic"
        >
          <el-icon :size="24"><Microphone /></el-icon>
        </el-button>
        <div class="mic-side">
          <strong class="mic-action">{{ recording ? '再点一次结束' : '点击开始说话' }}</strong>
          <el-progress :percentage="Math.round(state.level * 100)" :show-text="false" :stroke-width="6" class="meter" />
        </div>
      </div>

      <!-- 示例话术：给第一次使用的人一个"照着说"的起点，也平衡卡片内的留白 -->
      <div class="voice-examples">
        <span class="examples-label">可以这样说</span>
        <div class="examples-list">
          <span v-for="t in examples" :key="t">{{ t }}</span>
        </div>
      </div>

      <el-alert v-if="state.error" :title="state.error" type="error" show-icon :closable="false" />

      <div v-if="state.asrText" class="asr-line">你说：{{ state.asrText }}</div>
      <div v-if="state.caption" class="caption">{{ state.caption }}</div>

      <div v-if="state.products.length" class="products">
        <div v-for="(it, idx) in state.products" :key="it.productId" class="product-card"
             @click="goProduct(it.productId)">
          <b>{{ idx + 1 }}. {{ it.name }}</b>
          <span class="price">¥{{ it.price }}</span>
        </div>
      </div>

      <el-alert v-if="orderExec.text" :title="orderExec.text" :type="orderExec.type"
                show-icon :closable="false" class="order-exec" />

      <el-collapse class="log-panel">
        <el-collapse-item title="运行日志" name="log">
          <div v-for="(l, i) in logs" :key="i" class="log-line">{{ l }}</div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- 我的语音记录（C 端可见自己的会话与语音下单） -->
    <div class="voice-history card">
      <div class="section-heading">
        <div>
          <span>MY VOICE HISTORY</span>
          <h2>我的语音记录</h2>
        </div>
        <el-button text :loading="history.loading" @click="loadHistory">刷新</el-button>
      </div>

      <div v-if="!history.list.length" class="empty">还没有语音会话，点上面的麦克风说一句试试。</div>
      <div v-for="row in history.list" :key="row.session_id" class="voice-history-item" @click="openHistory(row)">
        <div class="vh-main">
          <span class="vh-dot" :class="{ done: Number(row.order_count) > 0 }"></span>
          <div class="vh-text">
            <strong>{{ row.first_ask || '（未记录开场）' }}</strong>
            <small>{{ row.started_at }} · {{ row.turns }} 轮 · {{ channelName(row.channel) }}</small>
          </div>
          <b v-if="Number(row.order_count) > 0" class="vh-amount">成交 ¥{{ row.order_amount }}</b>
          <b v-else class="vh-none">{{ row.outcome === 'ABANDONED' ? '未下单' : '进行中' }}</b>
        </div>
      </div>
    </div>

    <!-- 会话回放 -->
    <el-dialog title="语音会话回放" v-model="history.detailVisible" width="760px">
      <div v-if="!history.detail?.messages?.length" class="empty">该会话没有可回放的对话</div>
      <div v-for="m in history.detail?.messages || []" :key="m.turn" class="msg-row">
        <el-tag :type="m.role === 'USER' ? 'primary' : 'success'" size="small">{{ m.role === 'USER' ? '你' : '导购' }}</el-tag>
        <span class="msg-intent">{{ m.intent || '' }}</span>
        <div class="msg-text">{{ m.content_text }}</div>
      </div>
      <template v-if="history.detail?.orders?.length">
        <h4 style="margin-top: 14px">本次语音下单</h4>
        <el-table :data="history.detail.orders" size="small">
          <el-table-column prop="action" label="动作" width="80">
            <template #default="scope">{{ scope.row.action === 'CANCEL' ? '取消' : '下单' }}</template>
          </el-table-column>
          <el-table-column prop="order_no" label="订单号" width="190" />
          <el-table-column prop="product_name" label="商品" show-overflow-tooltip />
          <el-table-column prop="total_amount" label="金额" width="90" />
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Microphone } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { createVoiceSession, VoiceState } from '@/utils/voiceSession'

const router = useRouter()
const user = JSON.parse(localStorage.getItem('sys-user') || '{}')
const sessionId = 'voice-' + (user.id || 0) + '-' + Date.now()
const logs = ref([])
const orderExec = reactive({ text: '', type: 'info' })
const history = reactive({ list: [], loading: false, detailVisible: false, detail: null })

// 渠道归因：入口页通过 props 传入（首页 HOME_ENTRY / 商详页 PRODUCT_PAGE / 搜索兜底 SEARCH_FALLBACK），
// productId 仅在商详页进入时有值，服务端据此把会话归到具体商品
const props = defineProps({
  channel: { type: String, default: 'HOME_ENTRY' },
  productId: { type: [String, Number], default: null }
})

const appendLog = (msg) => {
  logs.value.push(new Date().toLocaleTimeString() + ' ' + msg)
  if (logs.value.length > 50) logs.value.shift()
}

const { state, start, stop, cleanup, sendOrderResult } = createVoiceSession({
  sessionId,
  channel: props.channel,
  productId: props.productId,
  onLog: appendLog,
  onOrderAction: executeOrderAction
})

let restoreSelection = null

const recording = computed(() => state.status === VoiceState.RECORDING)
const statusText = computed(() => ({
  IDLE: 'AI 在线 · 点击麦克风开始', CONNECTING: '连接中…', RECORDING: '录音中，再点一次结束',
  WAITING: '正在为你处理…', CLOSING: '收尾中…'
}[state.status] || state.status))

const toggleMic = () => {
  if (state.status === VoiceState.IDLE) start()
  else if (state.status === VoiceState.RECORDING) stop()
}

const goProduct = (id) => router.push(`/front/product/${id}`)
const channelName = (c) => ({ HOME_ENTRY: '首页入口', PRODUCT_PAGE: '商品详情页', SEARCH_FALLBACK: '搜索兜底' }[c] || c)

// 示例话术：覆盖四条主要能力（推荐 / 澄清补充 / 下单 / 查订单）
const examples = ['帮我推荐一款六千以内的轻薄笔记本', '要续航好一点的', '就第一款，确认下单', '我的订单到哪了']

const loadHistory = () => {
  history.loading = true
  request.get('/voice/my/sessions', { params: { limit: 10 } }).then(res => {
    if (res.code === '200') history.list = res.data || []
  }).finally(() => { history.loading = false })
}

const openHistory = (row) => {
  request.get('/voice/my/sessions/' + encodeURIComponent(row.session_id)).then(res => {
    if (res.code === '200') {
      history.detail = res.data || {}
      history.detailVisible = true
    }
  })
}

/** 语音下单/取消的前端代执行：全部走用户自己的登录态与现成的购物车/下单 Saga 接口 */
async function executeOrderAction(frame) {
  if (frame.action === 'create') await doCreate(frame)
  else if (frame.action === 'cancel') await doCancel(frame)
}

async function doCreate(frame) {
  orderExec.text = `正在下单：${frame.productName} ×${frame.quantity}…`
  orderExec.type = 'info'
  try {
    const addrRes = await request.get(`/userAddress/selectByUserId/${user.id}`)
    const addresses = (addrRes && addrRes.data) || []
    if (!addresses.length) throw new Error('你还没有收货地址，请先到个人中心添加')
    const addr = addresses.find(a => a.isDefault === 1) || addresses[0]
    const receiverAddress = [addr.province, addr.city, addr.district, addr.detailAddress]
      .filter(Boolean).join(' ')

    // 下单 Saga 只取购物车 selected=1 的行：先清零并记住，下单后恢复用户原有勾选
    //（服务端 updateById 校验 id + quantity>0，必须传完整行字段）
    const cartRes = await request.get('/shoppingCart/selectAll', { params: { userId: user.id } })
    const selected = ((cartRes && cartRes.data) || []).filter(it => it.selected === 1)
    const restoreList = selected.map(it => ({
      id: it.id, productId: it.productId, quantity: it.quantity, selected: 1
    }))
    for (const it of selected) {
      await request.put('/shoppingCart/update', {
        id: it.id, productId: it.productId, quantity: it.quantity, selected: 0
      })
    }
    restoreSelection = async () => {
      for (const it of restoreList) await request.put('/shoppingCart/update', it).catch(() => {})
    }

    const addRes = await request.post('/shoppingCart/add', {
      productId: frame.productId, quantity: frame.quantity, selected: 1
    })
    if (addRes.code !== '200') throw new Error(addRes.msg || '加购失败')

    const createRes = await request.post('/shopOrder/create', {
      requestId: frame.requestId,
      receiverName: addr.receiverName,
      receiverPhone: addr.receiverPhone,
      receiverAddress
    })
    if (createRes.code !== '200' || !createRes.data) throw new Error(createRes.msg || '下单失败')
    orderExec.text = `下单成功，订单号 ${createRes.data.orderNo}`
    orderExec.type = 'success'
    sendOrderResult({ requestId: frame.requestId, success: true, orderNo: createRes.data.orderNo })
  } catch (e) {
    orderExec.text = '下单失败：' + ((e && e.message) || e)
    orderExec.type = 'error'
    sendOrderResult({ requestId: frame.requestId, success: false, error: orderExec.text })
  } finally {
    if (restoreSelection) {
      await restoreSelection().catch(() => {})
      restoreSelection = null
    }
    loadHistory()
  }
}

async function doCancel(frame) {
  orderExec.text = `正在取消订单 ${frame.orderNo}…`
  orderExec.type = 'info'
  try {
    const res = await request.get('/shopOrder/selectAll', { params: { orderNo: frame.orderNo } })
    const order = ((res && res.data) || [])[0]
    if (!order) throw new Error('找不到这张订单')
    const cancelRes = await request.put(`/shopOrder/cancel/${order.id}`)
    if (cancelRes.code !== '200') throw new Error(cancelRes.msg || '取消失败')
    orderExec.text = `订单 ${frame.orderNo} 已取消`
    orderExec.type = 'success'
    sendOrderResult({ requestId: frame.requestId, success: true, orderNo: frame.orderNo })
  } catch (e) {
    orderExec.text = '取消失败：' + ((e && e.message) || e)
    orderExec.type = 'error'
    sendOrderResult({ requestId: frame.requestId, success: false, error: orderExec.text })
  } finally {
    loadHistory()
  }
}

onMounted(loadHistory)
onBeforeUnmount(() => {
  cleanup()
})
</script>

<style scoped lang="scss">
.card { border: 1px solid #e3e9ef; border-radius: 14px; background: #fff; padding: 18px 20px;
  box-shadow: 0 8px 25px rgba(29, 50, 75, .045); }
.section-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px;
  span { color: #9ba4af; font-size: 11px; letter-spacing: 1px; }
  h2 { margin: 4px 0 0; font-size: 18px; color: #2b3a4a; } }
.online-dot { color: #168f9f; font-size: 12px; background: #eaf9fa; border-radius: 999px; padding: 4px 10px; }
.mic-row { display: flex; align-items: center; gap: 16px; }
.mic-btn { width: 56px; height: 56px; flex: 0 0 auto; }
.mic-side { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.mic-action { color: #2b3a4a; font-size: 14px; }
.meter { max-width: 280px; }
.voice-examples { display: flex; align-items: center; gap: 10px; margin: 12px 0 2px;
  padding-top: 12px; border-top: 1px dashed #edf1f4; }
.examples-label { color: #9ba4af; font-size: 12px; white-space: nowrap; }
.examples-list { display: flex; flex-wrap: wrap; gap: 8px; }
.examples-list span { padding: 5px 11px; border: 1px solid #e3e9ef; border-radius: 999px;
  color: #5b6b7a; background: #fafcfd; font-size: 12px; }
.asr-line { color: #909399; font-size: 13px; margin: 12px 0 4px; }
.caption { font-size: 15px; line-height: 1.85; margin: 8px 0 14px; white-space: pre-wrap; color: #2b3a4a; }
.products { display: grid; gap: 8px; margin-bottom: 12px; }
.product-card { display: flex; justify-content: space-between; padding: 10px 14px;
  border: 1px solid #ebeef5; border-radius: 8px; cursor: pointer;
  &:hover { border-color: #71c9d3; background: #f5fcfd; }
  .price { color: #f56c6c; font-weight: 600; } }
.order-exec { margin: 10px 0; }
.log-panel { margin-top: 10px; }
.log-line { font-size: 12px; color: #909399; font-family: monospace; }

.voice-history { margin-top: 12px; }
.empty { color: #9ba4af; font-size: 13px; padding: 6px 0; }
.voice-history-item { padding: 10px 12px; border: 1px solid #e7ebef; border-radius: 9px; margin-bottom: 8px; cursor: pointer;
  &:hover { border-color: #71c9d3; background: #f5fcfd; } }
.vh-main { display: grid; grid-template-columns: 8px 1fr auto; align-items: center; gap: 10px; min-width: 0; }
.vh-dot { width: 7px; height: 7px; border-radius: 50%; background: #e4a83a; }
.vh-dot.done { background: #24ad8e; }
.vh-text { min-width: 0;
  strong { display: block; overflow: hidden; color: #354252; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
  small { display: block; margin-top: 3px; color: #9ba4af; font-size: 11px; } }
.vh-amount { color: #24ad8e; font-size: 12px; white-space: nowrap; }
.vh-none { color: #9ba4af; font-size: 12px; white-space: nowrap; }

.msg-row { display: flex; align-items: baseline; gap: 8px; padding: 6px 0; border-bottom: 1px dashed #ebeef5; }
.msg-intent { color: #909399; font-size: 12px; }
.msg-text { white-space: pre-wrap; word-break: break-all; }
</style>
