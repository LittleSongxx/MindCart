package com.mindcart.voice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class RecommendExecutorConfig {

    /**
     * 推荐链路并行加载专用线程池（画像加载 ∥ 向量召回都是 IO 密集任务）。
     * 不能用 ForkJoinPool.commonPool：supplyAsync 默认走它，IO 阻塞会占满
     * CPU 核数个并行度，拖垮同进程里真正的 CPU 并行计算（并行流/其它 CompletableFuture）。
     */
    @Bean("recommendExecutor")
    public ThreadPoolTaskExecutor recommendExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("rec-para-");
        // 队列满时由调用线程自己执行，任务不丢、起到天然背压
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：关闭时等待在跑的推荐任务完成（配合 server.shutdown=graceful 的 15s 窗口）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
