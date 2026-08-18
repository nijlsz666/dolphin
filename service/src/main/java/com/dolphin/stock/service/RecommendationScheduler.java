package com.dolphin.stock.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RecommendationScheduler {
    private final StockPoolService stockPoolService;
    private final NewsIngestionService newsIngestionService;
    private final SystemInitializationStore systemInitializationStore;
    private final DatabaseSchemaInitializer databaseSchemaInitializer;

    public RecommendationScheduler(StockPoolService stockPoolService, NewsIngestionService newsIngestionService,
                                   SystemInitializationStore systemInitializationStore,
                                   DatabaseSchemaInitializer databaseSchemaInitializer) {
        this.stockPoolService = stockPoolService;
        this.newsIngestionService = newsIngestionService;
        this.systemInitializationStore = systemInitializationStore;
        this.databaseSchemaInitializer = databaseSchemaInitializer;
    }

    public LocalDateTime lastInitializationAt() {
        return systemInitializationStore.load();
    }

    /** Runs the work normally performed by the scheduled jobs once, immediately. */
    public synchronized Map<String, Object> initializeNow(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : asOf;
        long started = System.nanoTime();
        databaseSchemaInitializer.initialize();
        Map<String, Object> news = newsIngestionService.refreshNow();
        Map<String, Object> ai = stockPoolService.refreshAiModelSnapshots(date);
        StockPoolService.StrategyConfigView strategy = stockPoolService.initializationStrategy();
        stockPoolService.refreshRecommendations(date, strategy.minPrice(), strategy.maxPrice(), "INITIALIZATION", "初始化");
        LocalDateTime initializedAt = systemInitializationStore.save(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("initializedAt", initializedAt);
        result.put("news", news);
        result.put("ai", ai);
        result.put("status", ai.containsKey("error") ? "PARTIAL" : "SUCCESS");
        result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000L);
        return result;
    }

    /**
     * 每分钟检查一次当前应完成的推荐时点；跳过每小时为新闻、模型和数据任务预留的
     * 00、05、15 分钟，成功后不会重复执行，失败则最多每 5 分钟自动重试 3 次。
     */
    @Scheduled(cron = "0 1-4,6-14,16-59 9-15 * * MON-FRI", zone = "Asia/Shanghai")
    public void ensureRecommendationSnapshot() {
        stockPoolService.ensureScheduledRecommendation();
    }

    /** 每小时第 00 分钟从网络采集新闻，AI分析后写回新闻表。 */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Shanghai")
    public void refreshNewsCache() {
        newsIngestionService.refreshNow();
    }

    /** 每小时第 05 分钟单独治理并生成模型快照。 */
    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Shanghai")
    public void refreshModels() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        stockPoolService.refreshScoringModelNow(date);
        stockPoolService.refreshAiModelSnapshots(date);
    }

    /** 每小时第 15 分钟只读取当前模型快照，生成推荐和股票池业务数据。 */
    @Scheduled(cron = "0 15 * * * *", zone = "Asia/Shanghai")
    public void refreshModelData() {
        stockPoolService.refreshModelDataNow(java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }
}
