package com.mindcart.voice.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * PCM 重采样与分帧工具。
 * omni 实时会话的音频输出固定 24kHz（会话协商不接受 16k），而整条语音管道
 * （ASR 输入、前端播放）统一是 16kHz/16bit/mono，所以在 TTS 出口做 24k→16k 降采样。
 * 3:2 线性插值，支持跨 chunk 的流式处理。
 */
public final class PcmResampler {

    private PcmResampler() {
    }

    /** 流式 24k→16k 重采样器：跨 chunk 维持相位连续，一次合成会话用一个实例。 */
    public static class Streaming24to16 {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private long consumedIn = 0;   // buf 中已丢弃的输入样本数（buf 起点的全局样本序号）
        private long nextOut = 0;      // 下一个待产出的输出样本序号

        public synchronized byte[] resample(byte[] chunk) {
            buf.write(chunk, 0, chunk.length);
            long totalIn = consumedIn + buf.size() / 2L;
            ByteBuffer ib = ByteBuffer.wrap(buf.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                double pos = nextOut * 1.5;      // 输出样本 o 对应输入位置 o * 24000/16000
                int i = (int) pos;
                if (i + 1 >= totalIn) break;     // 插值需要 i 和 i+1 两个输入样本，等下一个 chunk
                double frac = pos - i;
                int s0 = ib.getShort((int) ((i - consumedIn) * 2));
                int s1 = ib.getShort((int) ((i + 1 - consumedIn) * 2));
                short v = (short) Math.round(s0 + (s1 - s0) * frac);
                out.write(v & 0xFF);
                out.write((v >> 8) & 0xFF);
                nextOut++;
            }
            long keepFrom = (long) Math.floor(nextOut * 1.5);   // 之后仍可能被插值引用，保留
            if (keepFrom > consumedIn) {
                byte[] all = buf.toByteArray();
                int drop = (int) (keepFrom - consumedIn) * 2;
                buf.reset();
                buf.write(all, drop, all.length - drop);
                consumedIn = keepFrom;
            }
            return out.toByteArray();
        }
    }

    /** 按固定帧大小切片（3200B = 16kHz/16bit/mono 的 100ms），平滑前端播放节奏。 */
    public static List<ByteBuffer> split(byte[] pcm) {
        List<ByteBuffer> frames = new ArrayList<>();
        for (int i = 0; i < pcm.length; i += 3200) {
            int len = Math.min(3200, pcm.length - i);
            byte[] f = new byte[len];
            System.arraycopy(pcm, i, f, 0, len);
            frames.add(ByteBuffer.wrap(f));
        }
        return frames;
    }
}
