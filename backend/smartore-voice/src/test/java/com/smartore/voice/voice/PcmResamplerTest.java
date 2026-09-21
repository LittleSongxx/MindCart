package com.smartore.voice.voice;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 24k→16k 流式重采样：长度比例、正确性、跨 chunk 相位连续。 */
class PcmResamplerTest {

    /** 最小 ByteBuffer 工具：小端 16bit 读单采样 */
    static final class ByteBuffer2 {
        private final byte[] data;
        ByteBuffer2(byte[] data) { this.data = data; }
        short get(int sampleIdx) {
            int i = sampleIdx * 2;
            return (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
        }
        int sampleCount() { return data.length / 2; }
    }

    private static byte[] pcm(short[] samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            b[i * 2] = (byte) samples[i];
            b[i * 2 + 1] = (byte) (samples[i] >> 8);
        }
        return b;
    }

    @Test
    // 输出长度符合2比3比例
    void case08() {
        PcmResampler.Streaming24to16 r = new PcmResampler.Streaming24to16();
        byte[] out = r.resample(pcm(new short[3000]));   // 3000 输入样本
        assertEquals(2000, out.length / 2);              // 期望 2000 输出样本
    }

    @Test
    // 常量信号重采样后不变
    void case09() {
        PcmResampler.Streaming24to16 r = new PcmResampler.Streaming24to16();
        short[] in = new short[999];
        java.util.Arrays.fill(in, (short) 12345);
        byte[] out = r.resample(pcm(in));
        ByteBuffer2 b = new ByteBuffer2(out);
        for (int i = 0; i < b.sampleCount(); i++) {
            assertEquals(12345, b.get(i), "常量信号插值后必须仍是常量, i=" + i);
        }
    }

    @Test
    // 跨chunk流式与一次性重采样逐样本一致_相位连续
    void case10() {
        Random rand = new Random(42);
        short[] in = new short[100_000];
        for (int i = 0; i < in.length; i++) in[i] = (short) rand.nextInt();

        // 一次性参考：全量输入
        PcmResampler.Streaming24to16 whole = new PcmResampler.Streaming24to16();
        byte[] expected = whole.resample(pcm(in));

        // 流式：随机大小 chunk 喂入
        PcmResampler.Streaming24to16 streaming = new PcmResampler.Streaming24to16();
        java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
        int i = 0;
        while (i < in.length) {
            int n = 1 + rand.nextInt(5000);
            int end = Math.min(i + n, in.length);
            byte[] part = streaming.resample(pcm(java.util.Arrays.copyOfRange(in, i, end)));
            acc.write(part, 0, part.length);
            i = end;
        }
        assertArrayEquals(expected, acc.toByteArray(), "流式分块结果必须与一次性重采样完全一致");
    }

    @Test
    // 分帧_每帧3200字节_末帧为余量
    void case11() {
        List<java.nio.ByteBuffer> frames = PcmResampler.split(new byte[7000]);
        assertEquals(3, frames.size());
        assertEquals(3200, frames.get(0).remaining());
        assertEquals(3200, frames.get(1).remaining());
        assertEquals(600, frames.get(2).remaining());
    }
}
