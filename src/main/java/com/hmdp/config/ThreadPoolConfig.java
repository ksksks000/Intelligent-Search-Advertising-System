package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 全局线程池配置：整个应用只创建一个线程池 Bean，各处通过注入复用。
 * 使用 Spring 的 ThreadPoolTaskExecutor，随容器启动/关闭，避免手动管理线程生命周期。
 * <p>
 * 线程池参数说明：
 * <ul>
 *     <li>核心线程数：线程池常驻线程数量，即使空闲也不会被回收（默认）</li>
 *     <li>最大线程数：线程池允许创建的最大线程数</li>
 *     <li>队列容量：核心线程都在忙时，新任务先进入队列排队</li>
 *     <li>存活时间：超出核心线程数的那部分空闲线程，超过该时间会被回收</li>
 * </ul>
 * 任务提交后的执行顺序：核心线程 → 任务队列 → 扩容到最大线程 → 拒绝策略。
 */
@Configuration
public class ThreadPoolConfig {

    /** 核心线程数：CPU 核心数 * 2，适配 IO 密集型任务 */
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;

    /** 最大线程数 */
    private static final int MAX_POOL_SIZE = CORE_POOL_SIZE * 2;

    /** 任务队列容量 */
    private static final int QUEUE_CAPACITY = 1000;

    /** 空闲线程存活时间（秒） */
    private static final int KEEP_ALIVE_SECONDS = 60;

    /**
     * 全局线程池。
     * <p>
     * 命名为 taskExecutor：既是注入名称，也是 Spring @Async 的默认执行器名称，
     * 后续需要异步方法时可直接搭配 @EnableAsync + @Async 使用。
     * <p>
     * 拒绝策略使用 CallerRunsPolicy：队列满时由提交任务的线程自己执行，
     * 保证任务不丢失（秒杀等场景下丢任务代价较高）。
     */
    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：常驻线程，负责处理日常任务
        executor.setCorePoolSize(CORE_POOL_SIZE);
        // 最大线程数：任务高峰期（核心线程 + 队列都满）时最多能扩容到的线程数
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        // 任务队列容量：核心线程忙时任务先排队，避免频繁创建线程
        executor.setQueueCapacity(QUEUE_CAPACITY);
        // 非核心线程空闲超过该时间会被回收，释放系统资源
        executor.setKeepAliveSeconds(KEEP_ALIVE_SECONDS);
        // 线程名前缀：日志中可通过 hmdp-task- 快速定位线程池相关线程
        executor.setThreadNamePrefix("hmdp-task-");
        // 拒绝策略：队列和线程都满时，由提交任务的线程自己执行，保证任务不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 容器关闭时等待已提交任务执行完，避免异步任务被直接中断
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 关闭时最多等待 60 秒，超时后强制结束，防止应用无法正常退出
        executor.setAwaitTerminationSeconds(60);
        // 初始化线程池（非 Spring 管理场景下需要手动调用）
        executor.initialize();
        return executor;
    }

    /*
     * ==================== 后续用法示例 ====================
     *
     * 1. 直接注入复用（全项目共用同一个线程池）
     *
     *    @Resource
     *    private ThreadPoolTaskExecutor taskExecutor;
     *
     *    // 提交无返回值的任务
     *    taskExecutor.execute(() -> {
     *        // 业务逻辑
     *    });
     *
     *    // 提交有返回值的任务，通过 Future 获取结果
     *    Future<String> future = taskExecutor.submit(() -> "result");
     *    // String result = future.get(); // 会阻塞直到任务完成
     *
     * 2. 配合 @Async 使用（需要先开启异步支持）
     *
     *    在配置类或启动类上加 @EnableAsync，
     *    然后在方法上指定执行器名称：
     *
     *    @Async("taskExecutor")
     *    public void asyncTask() {
     *        // 业务逻辑，由线程池异步执行
     *    }
     *
     * 3. 需要 JDK 原生 API 时，可声明为 Executor 接口注入
     *
     *    @Resource
     *    private Executor executor;
     *
     *    （注意：ThreadPoolTaskExecutor 实现的是 Executor，不是 ExecutorService，
     *     如需 shutdown/awaitTermination 等能力，可直接注入 ThreadPoolTaskExecutor
     *     并调用 getThreadPoolExecutor() 获取底层线程池）
     *
     * 4. 无需手动关闭：Spring 容器关闭时会自动调用 shutdown，
     *    并等待已提交任务执行完（最长 60 秒）。
     */
}
