/**
 * 语音会话 composable：对接 mindcart-voice 的 /voice/ws 双向流。
 * 移植自 voice-test.html 的 7 个逻辑单元：
 * ① WS 生命周期（query-token 握手，浏览器 WS 无法自定义请求头）
 * ② AudioWorklet 内联降采样采集（任意采样率 → 16k Int16 分片）
 * ③ 停止时尾部垫静音（防 ASR 吞尾词）
 * ④ 消息协议分发（asr/caption/recommendation/order_action/二进制音频）
 * ⑤ PCM 播放队列（playbackTime 调度无缝拼接）
 * ⑥ 流式静默自动关闭（连续 N ms 无帧 → 关 WS）
 * ⑦ 录音状态机（IDLE/CONNECTING/RECORDING/WAITING/CLOSING）
 *
 * 交易动作帧（order_action）不在这里执行：经 onOrderAction 回调交给页面，
 * 页面用用户自己的登录态走 /shoppingCart + /shopOrder 现成链路，
 * 结果由 sendOrderResult 回传给服务端口播。
 */
import { reactive } from 'vue'

const TARGET_RATE = 16000
const CHUNK_MS = 100
const MIN_RECORD_MS = 800
const SILENCE_TAIL_MS = 800
const HARD_CLOSE_MS = 20000
const STREAM_SILENCE_MS = 3500

export const VoiceState = Object.freeze({
  IDLE: 'IDLE', CONNECTING: 'CONNECTING', RECORDING: 'RECORDING',
  WAITING: 'WAITING', CLOSING: 'CLOSING'
})

const WORKLET_SRC = `
class PcmCollector extends AudioWorkletProcessor {
    constructor(options) {
        super();
        this.inputRate = options.processorOptions.inputRate;
        this.targetRate = ${TARGET_RATE};
        this.ratio = this.inputRate / this.targetRate;
        this.samplesPerChunk = this.targetRate * ${CHUNK_MS} / 1000;
        this.buf = new Int16Array(this.samplesPerChunk);
        this.cursor = 0;
        this.srcIdx = 0;
    }
    process(inputs) {
        const input = inputs[0][0];
        if (!input) return true;
        let sum = 0;
        for (let i = 0; i < input.length; i++) sum += input[i] * input[i];
        this.port.postMessage({ type: 'level', rms: Math.sqrt(sum / input.length) });
        while (this.srcIdx < input.length) {
            const i0 = Math.floor(this.srcIdx);
            const frac = this.srcIdx - i0;
            const s0 = input[i0] || 0;
            const s1 = input[i0 + 1] || s0;
            const s  = Math.max(-1, Math.min(1, s0 + (s1 - s0) * frac));
            this.buf[this.cursor++] = s < 0 ? s * 0x8000 : s * 0x7fff;
            if (this.cursor === this.buf.length) {
                this.port.postMessage({ type: 'pcm', data: this.buf.buffer.slice(0) });
                this.cursor = 0;
            }
            this.srcIdx += this.ratio;
        }
        this.srcIdx -= input.length;
        return true;
    }
}
registerProcessor('pcm-collector', PcmCollector);
`

export function createVoiceSession({ sessionId, channel, productId, onOrderAction, onLog } = {}) {
  const state = reactive({
    status: VoiceState.IDLE,
    caption: '',          // 当前一轮的话术文本（流式拼接）
    asrText: '',          // 最近的 ASR 识别文本
    products: [],         // 推荐商品卡片
    level: 0,             // 麦克风电平 0~1
    error: ''
  })

  let ws = null, audioCtx = null, workletNode = null, micStream = null
  let recordStartAt = 0, playbackTime = 0
  let userStopped = false
  let hardCloseTimer = null
  let streamSilenceTimer = null

  const log = (msg) => { if (onLog) onLog(msg) }

  function wsUrl() {
    const user = JSON.parse(localStorage.getItem('sys-user') || '{}')
    const token = user.token || ''
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    // vite proxy /api → 网关（ws:true）；token 走 query（网关 /voice/ws 白名单特批）；
    // channel/productId 供服务端做渠道归因（商详页进入 = PRODUCT_PAGE）
    // 前缀与 request.js 同源（VITE_BASE_URL），改前缀时 HTTP/WS 不会分叉
    const base = (import.meta.env.VITE_BASE_URL || '/api').replace(/\/$/, '')
    let url = `${proto}://${location.host}${base}/voice/ws?token=${encodeURIComponent(token)}`
      + `&sessionId=${encodeURIComponent(sessionId || ('voice-' + Date.now()))}`
    if (channel) url += `&channel=${encodeURIComponent(channel)}`
    if (productId != null) url += `&productId=${encodeURIComponent(productId)}`
    return url
  }

  async function start() {
    if (state.status !== VoiceState.IDLE) return
    state.status = VoiceState.CONNECTING
    state.error = ''
    try {
      await cleanup()
      userStopped = false
      playbackTime = 0
      state.caption = ''
      state.products = []

      ws = new WebSocket(wsUrl())
      ws.binaryType = 'arraybuffer'
      const session = ws
      ws.onopen = () => log('WebSocket 已连接')
      ws.onerror = () => { state.error = 'WebSocket 通信异常'; log('WebSocket 通信异常') }
      ws.onclose = () => { log('WebSocket 已关闭'); if (session === ws) state.status = VoiceState.IDLE }
      ws.onmessage = (e) => onMessage(session, e)
      await waitOpen(ws)

      audioCtx = new AudioContext()
      const blob = new Blob([WORKLET_SRC], { type: 'application/javascript' })
      await audioCtx.audioWorklet.addModule(URL.createObjectURL(blob))

      micStream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: false, autoGainControl: true }
      })

      const src = audioCtx.createMediaStreamSource(micStream)
      workletNode = new AudioWorkletNode(audioCtx, 'pcm-collector', {
        processorOptions: { inputRate: audioCtx.sampleRate }
      })
      workletNode.port.onmessage = (ev) => {
        const m = ev.data
        if (m.type === 'pcm') {
          if (session === ws && ws.readyState === WebSocket.OPEN) ws.send(m.data)
        } else if (m.type === 'level') {
          state.level = Math.min(1, m.rms * 4)
        }
      }
      src.connect(workletNode)

      recordStartAt = Date.now()
      state.status = VoiceState.RECORDING
    } catch (e) {
      state.error = '启动失败：' + ((e && e.message) || e)
      await cleanup()
      state.status = VoiceState.IDLE
    }
  }

  async function stop() {
    if (state.status !== VoiceState.RECORDING) return
    const duration = Date.now() - recordStartAt
    if (duration < MIN_RECORD_MS) log(`录音时长 ${duration}ms，建议至少 1 秒`)

    if (workletNode) { try { workletNode.disconnect() } catch (_) {} workletNode = null }
    if (micStream) { micStream.getTracks().forEach(t => t.stop()); micStream = null }
    state.level = 0

    // 尾部垫静音：防 ASR 吞掉最后几个字
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(new Int16Array(TARGET_RATE * SILENCE_TAIL_MS / 1000).buffer)
    }
    userStopped = true
    state.status = VoiceState.WAITING

    const session = ws
    hardCloseTimer = setTimeout(() => {
      if (session && session.readyState === WebSocket.OPEN) {
        log('兜底超时，主动关闭 WebSocket')
        session.close()
      }
    }, HARD_CLOSE_MS)
  }

  function onMessage(session, e) {
    if (session !== ws) return   // 旧会话残响丢弃
    if (typeof e.data === 'string') {
      let msg
      try { msg = JSON.parse(e.data) } catch (_) { return }
      switch (msg.type) {
        case 'asr':
          state.asrText = msg.text || ''
          if (msg.final && userStopped) { state.caption = ''; state.products = [] }
          break
        case 'caption':
          state.caption += msg.text || ''
          bumpStreamSilence(session)
          break
        case 'recommendation':
          state.products = Array.isArray(msg.items) ? msg.items : []
          bumpStreamSilence(session)
          break
        case 'order_action':
          // 交易动作帧交给页面执行（用户自己的登录态），结果 sendOrderResult 回传
          if (onOrderAction) onOrderAction(msg)
          bumpStreamSilence(session)
          break
        default:
          log(`未知文本帧 type=${msg.type}`)
      }
    } else {
      playPcm(e.data)
      bumpStreamSilence(session)
    }
  }

  /** 前端代执行结果回传（下单/取消），服务端据此口播收口 */
  function sendOrderResult({ requestId, success, orderNo, error }) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'order_result', requestId, success, orderNo, error }))
    }
  }

  function bumpStreamSilence(session) {
    if (streamSilenceTimer) clearTimeout(streamSilenceTimer)
    streamSilenceTimer = setTimeout(() => {
      if (session === ws && ws && ws.readyState === WebSocket.OPEN &&
          (state.status === VoiceState.WAITING || state.status === VoiceState.RECORDING)) {
        log(`流式静默 ${STREAM_SILENCE_MS}ms，关闭 WebSocket`)
        state.status = VoiceState.CLOSING
        try { session.close() } catch (_) {}
      }
    }, STREAM_SILENCE_MS)
  }

  /** PCM 播放：playbackTime 调度，自动顺序拼接无缝播放 */
  function playPcm(arrayBuf) {
    if (!audioCtx) return
    const view = new DataView(arrayBuf)
    const f32 = new Float32Array(view.byteLength / 2)
    for (let i = 0; i < f32.length; i++) {
      const s = view.getInt16(i * 2, true)
      f32[i] = s < 0 ? s / 0x8000 : s / 0x7fff
    }
    const buf = audioCtx.createBuffer(1, f32.length, TARGET_RATE)
    buf.getChannelData(0).set(f32)
    const src = audioCtx.createBufferSource()
    src.buffer = buf
    src.connect(audioCtx.destination)
    const startAt = Math.max(audioCtx.currentTime, playbackTime)
    src.start(startAt)
    playbackTime = startAt + buf.duration
  }

  function waitOpen(socket) {
    return new Promise((resolve, reject) => {
      if (socket.readyState === WebSocket.OPEN) return resolve()
      socket.addEventListener('open', () => resolve(), { once: true })
      socket.addEventListener('error', () => reject(new Error('WebSocket 连接失败')), { once: true })
    })
  }

  async function cleanup() {
    if (hardCloseTimer) { clearTimeout(hardCloseTimer); hardCloseTimer = null }
    if (streamSilenceTimer) { clearTimeout(streamSilenceTimer); streamSilenceTimer = null }
    if (workletNode) { try { workletNode.disconnect() } catch (_) {} workletNode = null }
    if (micStream) { micStream.getTracks().forEach(t => t.stop()); micStream = null }
    if (audioCtx) { try { await audioCtx.close() } catch (_) {} audioCtx = null }
    if (ws) { try { ws.close() } catch (_) {} ws = null }
    state.level = 0
  }

  return { state, VoiceState, start, stop, cleanup, sendOrderResult }
}
