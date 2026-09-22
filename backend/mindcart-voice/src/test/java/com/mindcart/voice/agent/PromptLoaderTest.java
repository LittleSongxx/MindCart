package com.mindcart.voice.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * classpath 资源必须流式读取。
 * 回归背景：曾用 ClassPathResource.getFile()，IDE 里正常、Spring Boot 嵌套 jar 里
 * 抛 FileNotFoundException（getFile 要求资源位于真实文件系统）。
 */
class PromptLoaderTest {

    private final PromptLoader loader = new PromptLoader();

    @Test
    // 加载意图Prompt_内容非空
    void case33() {
        String prompt = loader.load("prompts/intent.txt");
        assertNotNull(prompt);
        assertTrue(prompt.length() > 50, "意图 prompt 应该是一段完整指令，实际长度=" + prompt.length());
        assertTrue(prompt.contains("JSON") || prompt.contains("json"), "意图 prompt 应约束 JSON 输出");
    }

    @Test
    // 加载全部业务Prompt_一个都不能少
    void case34() {
        for (String p : new String[]{
                "prompts/intent.txt", "prompts/clarify.txt",
                "prompts/recommend-reason.txt", "prompts/emotion-merged.txt"}) {
            assertTrue(loader.load(p).length() > 20, p + " 加载结果异常");
        }
    }

    @Test
    // 中文内容不乱码
    void case35() {
        String prompt = loader.load("prompts/emotion-merged.txt");
        assertTrue(prompt.contains("语音导购"), "UTF-8 读取应保留中文");
    }

    @Test
    // 不存在的文件抛出带路径的异常
    void case36() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> loader.load("prompts/not-exist.txt"));
        assertTrue(e.getMessage().contains("not-exist"));
    }
}
