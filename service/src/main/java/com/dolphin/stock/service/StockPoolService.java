package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@Service
public class StockPoolService {
    private static final Logger log = LoggerFactory.getLogger(StockPoolService.class);
    private static final BigDecimal MODEL_CONFIDENCE_THRESHOLD = new BigDecimal("0.80");
    private static final int MODEL_REGENERATION_ATTEMPTS = 3;
    private static final int RECOMMENDATION_TECHNICAL_CANDIDATE_LIMIT = 80;
    private static final int RECOMMENDATION_AI_CANDIDATE_LIMIT = 20;
    private static final int MAX_SCHEDULED_RECOMMENDATION_RETRIES = 3;
    private static final BigDecimal LOW_SUCCESS_PROBABILITY_THRESHOLD = new BigDecimal("50");
    private static final String PORTFOLIO_ANALYSIS_PENDING_MESSAGE = "持仓分析正在后台生成，请稍候，页面会自动更新";
    private final DemoMarketDataProvider provider;
    private final RealtimeQuoteClient realtimeQuoteClient;
    private final RecommendationSnapshotStore recommendationSnapshotStore;
    private final MarketUniverseClient universeClient;
    private final HistoricalKlineClient historicalKlineClient;
    private final MarketContextService marketContextService;
    private final AiAnalysisService aiAnalysisService;
    private final NewsIngestionService newsIngestionService;
    private final AiRealtimeCacheStore aiRealtimeCacheStore;
    private final AnalysisDataSnapshotStore analysisDataSnapshotStore;
    private final PortfolioAnalysisStore portfolioAnalysisStore;
    private final ScoringModelStore scoringModelStore;
    private final TradeModelStore tradeModelStore;
    private final ModelAuditStore modelAuditStore;
    private final StrategyConfigStore configStore;
    private final StockPoolStore poolStore;
    private final PositionStore positionStore;
    private final PlannedOrderStore plannedOrderStore;
    private final TradeExecutionStore tradeExecutionStore;
    private final CompanyProfileStore companyProfileStore;
    private final AccountAssetStore accountAssetStore;
    private StrategyConfig config;
    private ScoringModel scoringModel;
    private boolean scoringModelPersisted;
    private int ruleModelVersion;
    private final Map<String, PoolMembership> managedPool = new LinkedHashMap<>();
    private final ConcurrentMap<LocalDate, CompletableFuture<PortfolioAnalysis>> portfolioAnalysisTasks = new ConcurrentHashMap<>();
    private final ExecutorService portfolioAnalysisExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "portfolio-analysis");
        thread.setDaemon(true);
        return thread;
    });
    private String scheduledRetryKey;
    private LocalDateTime scheduledRetryAt;
    private int scheduledRetryCount;

    private record PoolMembership(LocalDate addedAt, String addedBy, String name, String industry) {}

    private record PlanDecision(String decision, String reason) {}

    private record PlanProbability(BigDecimal probability, String reason) {}
    private record ModuleScore(int value, int maximum) {
        private static ModuleScore unavailable() { return new ModuleScore(0, 0); }
        private static ModuleScore full(int value, int maximum) { return new ModuleScore(Math.max(0, value), Math.max(0, maximum)); }
    }
    private record PoolAnalysisWork(String code, PoolMembership membership, StockMarket market) {}

    public StockPoolService(DemoMarketDataProvider provider, RealtimeQuoteClient realtimeQuoteClient,
                            RecommendationSnapshotStore recommendationSnapshotStore, MarketUniverseClient universeClient,
                            HistoricalKlineClient historicalKlineClient, MarketContextService marketContextService,
                            AiAnalysisService aiAnalysisService,
                            NewsIngestionService newsIngestionService,
                            AiRealtimeCacheStore aiRealtimeCacheStore, AnalysisDataSnapshotStore analysisDataSnapshotStore,
                            PortfolioAnalysisStore portfolioAnalysisStore,
                            ScoringModelStore scoringModelStore, TradeModelStore tradeModelStore,
                            ModelAuditStore modelAuditStore,
                            StrategyConfigStore configStore, StockPoolStore poolStore,
                            PositionStore positionStore, PlannedOrderStore plannedOrderStore,
                            TradeExecutionStore tradeExecutionStore, CompanyProfileStore companyProfileStore,
                            AccountAssetStore accountAssetStore) {
        this.provider = provider;
        this.realtimeQuoteClient = realtimeQuoteClient;
        this.recommendationSnapshotStore = recommendationSnapshotStore;
        this.universeClient = universeClient;
        this.historicalKlineClient = historicalKlineClient;
        this.marketContextService = marketContextService;
        this.aiAnalysisService = aiAnalysisService;
        this.newsIngestionService = newsIngestionService;
        this.aiRealtimeCacheStore = aiRealtimeCacheStore;
        this.analysisDataSnapshotStore = analysisDataSnapshotStore;
        this.portfolioAnalysisStore = portfolioAnalysisStore;
        this.scoringModelStore = scoringModelStore;
        this.tradeModelStore = tradeModelStore;
        this.modelAuditStore = modelAuditStore;
        this.configStore = configStore;
        this.poolStore = poolStore;
        this.positionStore = positionStore;
        this.plannedOrderStore = plannedOrderStore;
        this.tradeExecutionStore = tradeExecutionStore;
        this.companyProfileStore = companyProfileStore;
        this.accountAssetStore = accountAssetStore;
        this.config = configStore.load(defaultConfig());
        Optional<ScoringModel> storedScoringModel = scoringModelStore.loadLatest();
        this.scoringModelPersisted = storedScoringModel.isPresent();
        this.scoringModel = storedScoringModel.orElse(ScoringModel.defaultModel());
        this.ruleModelVersion = modelAuditStore.latestVersion("position-risk");
        poolStore.loadActive().forEach(member -> managedPool.put(member.code(),
                new PoolMembership(member.addedAt(), member.addedBy(), member.name(), member.industry())));
    }

    public synchronized StrategyConfig config() {
        refreshLatestRuntimeModels();
        return config;
    }

    public synchronized StrategyConfigView initializationStrategy() {
        refreshLatestRuntimeModels();
        return new StrategyConfigView(config.minPrice(), config.maxPrice());
    }

    public record StrategyConfigView(BigDecimal minPrice, BigDecimal maxPrice) {}

    public synchronized ScoringModel scoringModel() {
        refreshLatestRuntimeModels();
        return scoringModel;
    }

    public synchronized List<ModelStatus> modelStatuses() {
        return modelAuditStore.loadStatuses(managedPool.size());
    }

    /** Refresh persisted runtime models before a calculation consumes them. */
    private synchronized void refreshLatestRuntimeModels() {
        Optional<ScoringModel> latestScoringModel = scoringModelStore.loadLatest();
        if (latestScoringModel.isPresent()) {
            scoringModel = latestScoringModel.get();
            scoringModelPersisted = true;
        } else if (!scoringModelPersisted) {
            scoringModel = ScoringModel.defaultModel();
        }
        StrategyConfig latestConfig = configStore.load(config);
        if (latestConfig != null) config = latestConfig;
        int latestRuleModelVersion = modelAuditStore.latestVersion("position-risk");
        if (latestRuleModelVersion > 0) ruleModelVersion = latestRuleModelVersion;
    }

    public synchronized StrategyConfig updateConfig(StrategyConfig incoming) {
        if (incoming == null) throw new IllegalArgumentException("策略配置不能为空");
        config = incoming;
        configStore.save(config);
        refreshLatestRuntimeModels();
        return config;
    }

    public AccountAssetSummary accountAssets() {
        AccountAssetStore.Assets assets = accountAssetStore.load();
        return assets == null ? new AccountAssetSummary(null, null)
                : new AccountAssetSummary(assets.totalAssets(), assets.updatedAt());
    }

    public synchronized AccountAssetSummary updateAccountAssets(AccountAssetRequest request) {
        if (request == null || request.totalAssets() == null || request.totalAssets().signum() <= 0) {
            throw new IllegalArgumentException("账户总资产必须大于 0");
        }
        AccountAssetStore.Assets assets = accountAssetStore.save(request.totalAssets());
        return new AccountAssetSummary(assets.totalAssets(), assets.updatedAt());
    }

    public StockPoolResponse scan(LocalDate asOf) {
        return scan(asOf, null, null);
    }

    public StockPoolResponse scan(LocalDate asOf, BigDecimal recommendationMaxPrice) {
        return scan(asOf, null, recommendationMaxPrice);
    }

    public StockPoolResponse scan(LocalDate asOf, BigDecimal recommendationMinPrice, BigDecimal recommendationMaxPrice) {
        refreshLatestRuntimeModels();
        long scanStarted = System.nanoTime();
        LocalDate date = asOf == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : asOf;
        BigDecimal minPrice = recommendationMinPrice == null ? config.minPrice() : recommendationMinPrice;
        BigDecimal maxPrice = recommendationMaxPrice == null ? config.maxPrice() : recommendationMaxPrice;
        if (minPrice.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("最低价格必须大于 0");
        if (maxPrice.compareTo(minPrice) < 0) throw new IllegalArgumentException("最高价格不能低于最低价格");
        if (maxPrice.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("价格上限必须大于 0");
        log.info("[推荐中心] 开始分析：日期={}，价格区间={}~{}", date, minPrice, maxPrice);
        // 推荐中心先扫描实时行情源返回的沪深主板全量，再按价格区间过滤；不使用样例股票。
        long stageStarted = System.nanoTime();
        List<StockMarket> quoteUniverse = universeClient.fetch(date);
        log.info("[推荐中心] 第1步 实时行情完成：沪深全量={} 只，耗时={} ms", quoteUniverse.size(), elapsedMs(stageStarted));
        stageStarted = System.nanoTime();
        List<StockMarket> universe = quoteUniverse.stream()
                .filter(this::isSupportedMainBoardA)
                .filter(stock -> stock.price() != null && stock.price().compareTo(minPrice) >= 0 && stock.price().compareTo(maxPrice) <= 0)
                .toList();
        List<StockMarket> recommendableUniverse = universe.stream()
                .filter(stock -> !managedPool.containsKey(stock.code()))
                .toList();
        log.info("[推荐中心] 第2步 硬过滤预筛完成：进入技术分析={} 只，排除股票池={} 只，过滤耗时={} ms",
                recommendableUniverse.size(), universe.size() - recommendableUniverse.size(), elapsedMs(stageStarted));
        // 历史K线逐只请求成本较高：先完成价格/板块/股票池硬过滤，再按真实成交额取流动性靠前的候选。
        stageStarted = System.nanoTime();
        List<StockMarket> technicalCandidates = recommendableUniverse.stream()
                .sorted(Comparator.comparing(StockMarket::turnover, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RECOMMENDATION_TECHNICAL_CANDIDATE_LIMIT)
                .parallel()
                .map(historicalKlineClient::enrich)
                .toList();
        log.info("[推荐中心] 第3步 历史技术数据完成：请求={} 只，耗时={} ms", technicalCandidates.size(), elapsedMs(stageStarted));
        stageStarted = System.nanoTime();
        MarketContext marketContext = marketContextService.evaluate(quoteUniverse);
        List<NewsHotspotStore.News> recentNews = marketContextService.recentNews();
        List<NewsHotspotStore.News> importantEvents = marketContextService.importantEvents();
        int marketAdjustment = marketContext.marketAdjustment().add(marketContext.newsAdjustment()).intValue();
        log.info("[推荐中心] 第4步 市场/新闻数据完成：新闻={} 条，重大事件={} 条，耗时={} ms", recentNews.size(), importantEvents.size(), elapsedMs(stageStarted));
        stageStarted = System.nanoTime();
        // 完整硬过滤必须先于 AI。未通过的股票不会进入 AI 请求，也不会进入后续评分。
        List<StockPoolItem> hardPassed = technicalCandidates.stream().map(stock -> {
            StockMarket eventStock = marketContextService.attachMajorEvent(stock, importantEvents);
            HardFilterResult filter = hardFilter(eventStock, minPrice, maxPrice);
            return new StockPoolItem(eventStock, filter, emptyScore(), "待AI补齐", List.of(), null, AiCompanyAnalysis.unavailable());
        }).toList();
        hardPassed = hardPassed.stream().filter(item -> item.hardFilter().passed()).toList();
        int hardPassedCount = hardPassed.size();
        log.info("[推荐中心] 第5步 完整硬过滤完成：检查={} 只，硬过滤通过={} 只，耗时={} ms",
                technicalCandidates.size(), hardPassedCount, elapsedMs(stageStarted));

        // AI 只处理本轮硬过滤通过的、按流动性去重后的前20只；同一轮不再重新扫描。
        List<StockPoolItem> aiCandidates = hardPassed.stream()
                .collect(java.util.stream.Collectors.toMap(item -> item.stock().code(), item -> item,
                        (left, right) -> left, LinkedHashMap::new))
                .values().stream()
                .limit(RECOMMENDATION_AI_CANDIDATE_LIMIT)
                .toList();
        stageStarted = System.nanoTime();
        fillRecommendationAiCache(date, aiCandidates.stream().map(StockPoolItem::stock).toList(), recentNews);
        log.info("[推荐中心] 第6步 AI补齐完成：候选={} 只，仅执行一次补齐，耗时={} ms",
                aiCandidates.size(), elapsedMs(stageStarted));

        stageStarted = System.nanoTime();
        List<StockPoolItem> rankedCandidates = aiCandidates.stream()
                .map(item -> {
                    AiRealtimeCacheStore.Cached cached = aiRealtimeCacheStore.load(item.stock().code(), date)
                            .orElse(AiRealtimeCacheStore.Cached.unavailable(item.stock().code(), date));
                    auditAiCacheUsage(item.stock().code(), cached);
                    AiCompanyAnalysis companyAnalysis = aiAnalysisService.ensureCompanyAnalysis(item.stock(),
                            cached.companyAnalysis());
                    AiTradeAdvice priceAdvice = aiAnalysisService.ensureTradeAdvice(item.stock(), cached.priceAdvice());
                    StockContext stockContext = marketContextService.evaluateStock(item.stock(), recentNews,
                            priceAdvice);
                    AiAnalysisService.AiFactorScores factorScores = aiAnalysisService.ensureFactorScores(item.stock(),
                            cached.factorScores(), recentNews);
                    FactorScores scores = hasTechnicalData(item.stock())
                            ? score(item.stock(), stockContext, factorScores) : emptyScore();
                    int contextAdjustment = marketAdjustment + stockContext.sentimentAdjustment().intValue();
                    int adjustedScore = scores.finalScore() + contextAdjustment;
                    String action = adjustedScore >= recommendationThreshold(item.stock()) ? "候选" : "观察";
                    List<String> reasons = new ArrayList<>(recommendationReasons(item.stock(), item.hardFilter(), scores, action));
                    reasons.addAll(stockContext.highlights());
                    if ("候选".equals(action)) {
                        reasons.add("总体市场调整 " + (marketAdjustment >= 0 ? "+" : "") + marketAdjustment
                                + " 分，个股调整 " + (stockContext.sentimentAdjustment().intValue() >= 0 ? "+" : "")
                                + stockContext.sentimentAdjustment().intValue() + " 分");
                    }
                    return new StockPoolItem(item.stock(), item.hardFilter(), scores, action, reasons, stockContext,
                            companyAnalysis);
                })
                .sorted(Comparator.comparingInt((StockPoolItem i) -> i.scores().finalScore()).reversed())
                .limit(20)
                .toList();
        // 推荐中心至少给出 5 条可研究结果；不足候选阈值时补充最高分的“观察”项，避免页面空白。
        List<StockPoolItem> finalCandidates = new ArrayList<>(rankedCandidates.stream()
                .filter(item -> "候选".equals(item.action()))
                .limit(20)
                .toList());
        if (finalCandidates.size() < 5) {
            rankedCandidates.stream()
                    .filter(item -> !finalCandidates.contains(item))
                    .limit(Math.max(0, 5 - finalCandidates.size()))
                    .forEach(finalCandidates::add);
        }
        log.info("[推荐中心] 第7步 AI数据分析及候选排序完成：最终候选={} 只，耗时={} ms", finalCandidates.size(), elapsedMs(stageStarted));
        List<StockPoolItem> items = finalCandidates;
        StockPoolResponse response = new StockPoolResponse(date, config, recommendableUniverse.size(), hardPassedCount, items,
                marketContext, null, null);
        RecommendationSnapshotStore.AiAnalysisStatus aiStatus = recommendationSnapshotStore.inspect(response);
        log.info("[推荐中心] AI结果检查：总数={}，公司分析可用={}，价格分析可用={}，完整={}",
                items.size(), aiStatus.total() - aiStatus.missingCompanyAnalysis().size(),
                aiStatus.total() - aiStatus.missingPriceAdvice().size(), aiStatus.completeCount());
        stageStarted = System.nanoTime();
        List<MarketIndexQuote> marketIndices = realtimeQuoteClient.fetchIndices();
        log.info("[推荐中心] 第8步 指数行情完成：{} 条，耗时={} ms", marketIndices.size(), elapsedMs(stageStarted));
        log.info("[推荐中心] 分析完成：推荐={} 只，硬过滤通过={} 只，总耗时={} ms", items.size(), hardPassedCount, elapsedMs(scanStarted));
        return new StockPoolResponse(response.asOf(), response.config(), response.universeCount(), response.hardPassedCount(),
                response.items(), response.marketContext(), marketIndices, response.snapshot());
    }

    private void fillRecommendationAiCache(LocalDate date, List<StockMarket> stocks,
                                           List<NewsHotspotStore.News> recentNews) {
        Map<String, StockMarket> uniqueStocks = stocks == null ? Map.of() : stocks.stream()
                .filter(stock -> stock != null && stock.code() != null && !stock.code().isBlank())
                .collect(java.util.stream.Collectors.toMap(StockMarket::code, stock -> stock,
                        (left, right) -> left, LinkedHashMap::new));
        List<StockMarket> missing = uniqueStocks.values().stream()
                .filter(stock -> !recommendationAiComplete(date, stock.code()))
                .toList();
        if (missing.isEmpty()) {
            log.info("[推荐中心] AI补齐跳过：本轮候选均已有完整缓存");
            return;
        }
        try {
            log.info("[推荐中心] AI补齐开始：{} 家公司，发起 1 次批量请求", missing.size());
            AiAnalysisService.BatchCompanyAnalysis batch = marketContextService.analyzeCompanies(missing, recentNews);
            for (StockMarket stock : missing) {
                String code = stock.code();
                AiCompanyAnalysis companyAnalysis = aiAnalysisService.ensureCompanyAnalysis(stock,
                        batch.companyAnalyses().get(code));
                AiTradeAdvice priceAdvice = aiAnalysisService.ensureTradeAdvice(stock,
                        batch.priceAdvices().get(code));
                AiAnalysisService.AiFactorScores factorScores = aiAnalysisService.ensureFactorScores(stock,
                        batch.factorScores().get(code), recentNews);
                aiRealtimeCacheStore.save(date, code, companyAnalysis, priceAdvice, factorScores);
                analysisDataSnapshotStore.save(date, stock, companyAnalysis, factorScores, recentNews,
                        "实时行情源+历史K线+新闻公告表+AI公司/价值模型");
                tradeModelStore.saveSuccessRateModel(aiAnalysisService.ensureSuccessRateModel(stock,
                        batch.successRateModels().get(code)));
                tradeModelStore.saveAnalysisModel(aiAnalysisService.ensureTradeAnalysisModel(stock,
                        batch.analysisModels().get(code), priceAdvice));
            }
            log.info("[推荐中心] AI补齐完成：{} 家公司；本轮不重新扫描、不重复请求", missing.size());
        } catch (Exception ex) {
            // 本轮只允许一次远程补齐；失败后逐只写入规则兜底，不通过重新 scan
            // 反复触发 AI，避免请求递归和重复处理。
            log.warn("[推荐中心] AI补齐失败，改用逐只规则兜底：{}", ex.getMessage());
            for (StockMarket stock : missing) {
                String code = stock.code();
                aiRealtimeCacheStore.save(date, code,
                        aiAnalysisService.ensureCompanyAnalysis(stock, null),
                        aiAnalysisService.ensureTradeAdvice(stock, null), null);
            }
        }
    }

    private boolean recommendationAiComplete(LocalDate date, String stockCode) {
        return aiRealtimeCacheStore.load(stockCode, date)
                .map(cached -> cached.companyAnalysis() != null && cached.companyAnalysis().available()
                        && cached.priceAdvice() != null && cached.priceAdvice().available()
                        && cached.factorScores() != null)
                .orElse(false);
    }

    public StockPoolResponse recommendations(LocalDate asOf, BigDecimal recommendationMinPrice, BigDecimal recommendationMaxPrice) {
        refreshLatestRuntimeModels();
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        BigDecimal minPrice = recommendationMinPrice == null ? config.minPrice() : recommendationMinPrice;
        BigDecimal maxPrice = recommendationMaxPrice == null ? config.maxPrice() : recommendationMaxPrice;
        // A stored snapshot can predate the news cache. Attempt retrieval before
        // returning it so a read-only page request does not surface a stale
        // "no news" state.
        newsIngestionService.ensureNewsAvailable();
        StockPoolResponse response = recommendationSnapshotStore.latest(date, minPrice, maxPrice)
                .map(this::refreshSnapshotStatus)
                .orElseGet(() -> emptyRecommendationResponse(date));
        return refreshNewsOnRead(response);
    }

    private StockPoolResponse refreshNewsOnRead(StockPoolResponse response) {
        if (response == null) return null;
        MarketContext marketContext = response.marketContext();
        MarketContext refreshedMarketContext = marketContext == null || marketContext.newsAvailable()
                ? marketContext : marketContextService.refreshNewsContext(marketContext);
        List<StockPoolItem> items = response.items() == null ? List.of() : response.items();
        List<NewsHotspotStore.News> recentNews = null;
        List<StockPoolItem> refreshedItems = new ArrayList<>(items);
        for (int i = 0; i < items.size(); i++) {
            StockPoolItem item = items.get(i);
            if (item == null) continue;
            StockContext current = item.stockContext();
            AiTradeAdvice effectiveAdvice = aiAnalysisService.ensureTradeAdvice(item.stock(),
                    current == null ? null : current.priceAdvice());
            AiCompanyAnalysis effectiveCompany = aiAnalysisService.ensureCompanyAnalysis(item.stock(),
                    item.companyAnalysis());
            if (recentNews == null && (current == null || !current.newsAvailable())) recentNews = marketContextService.recentNews();
            StockContext refreshed = current;
            if (current == null || !current.newsAvailable()) {
                refreshed = marketContextService.evaluateStock(item.stock(), recentNews, effectiveAdvice);
            } else if (!effectiveAdvice.equals(current.priceAdvice())) {
                refreshed = marketContextService.evaluateStock(item.stock(), recentNews, effectiveAdvice);
            }
            if (refreshed != current || !effectiveCompany.equals(item.companyAnalysis())) {
                refreshedItems.set(i, new StockPoolItem(item.stock(), item.hardFilter(), item.scores(), item.action(),
                        item.recommendationReasons(), refreshed, effectiveCompany));
            }
        }
        if (refreshedMarketContext == marketContext && refreshedItems.equals(items)) return response;
        return new StockPoolResponse(response.asOf(), response.config(), response.universeCount(),
                response.hardPassedCount(), refreshedItems, refreshedMarketContext, response.marketIndices(), response.snapshot());
    }

    private StockPoolResponse emptyRecommendationResponse(LocalDate date) {
        // 推荐中心 GET 只读数据库；首次定时任务或用户点击“重新分析”后才生成快照。
        return new StockPoolResponse(date, config, 0, 0, List.of(), null,
                realtimeQuoteClient.fetchIndices(), null);
    }

    public synchronized StockPoolResponse refreshRecommendations(LocalDate asOf, BigDecimal minPrice, BigDecimal maxPrice,
                                                                  String source, String slot) {
        refreshLatestRuntimeModels();
        long refreshStarted = System.nanoTime();
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        BigDecimal actualMinPrice = minPrice == null ? config.minPrice() : minPrice;
        BigDecimal actualMaxPrice = maxPrice == null ? config.maxPrice() : maxPrice;
        log.info("[推荐中心] 刷新任务开始：来源={}，时点={}，日期={}，价格区间={}~{}", source, slot, date, actualMinPrice, actualMaxPrice);
        StockPoolResponse live = scan(date, actualMinPrice, actualMaxPrice);
        LocalDateTime generatedAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        StockPoolResponse saved = withSnapshot(live, snapshotMeta(date, slot, source, generatedAt));
        long saveStarted = System.nanoTime();
        if (!recommendationSnapshotStore.save(saved, date, actualMinPrice, actualMaxPrice, slot, source)) {
            log.error("[推荐中心] 快照写入失败：耗时={} ms，总耗时={} ms", elapsedMs(saveStarted), elapsedMs(refreshStarted));
            throw new IllegalStateException("推荐快照写入数据库失败");
        }
        log.info("[推荐中心] 快照写入完成：推荐={} 只，耗时={} ms，总耗时={} ms", saved.items().size(), elapsedMs(saveStarted), elapsedMs(refreshStarted));
        return saved;
    }

    public synchronized void ensureScheduledRecommendation() {
        refreshLatestRuntimeModels();
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String slot = expectedSlot(now.toLocalTime());
        if (slot == null) return;
        BigDecimal minPrice = config.minPrice();
        BigDecimal maxPrice = config.maxPrice();
        String retryKey = now.toLocalDate() + "|" + slot + "|" + minPrice + "|" + maxPrice;
        if (recommendationSnapshotStore.latestSlot(now.toLocalDate(), minPrice, maxPrice, slot).isPresent()) {
            clearScheduledRetry(retryKey);
            return;
        }
        if (retryKey.equals(scheduledRetryKey) && scheduledRetryCount >= MAX_SCHEDULED_RECOMMENDATION_RETRIES) {
            return;
        }
        if (retryKey.equals(scheduledRetryKey) && scheduledRetryAt != null && now.isBefore(scheduledRetryAt)) return;
        try {
            refreshRecommendations(now.toLocalDate(), minPrice, maxPrice, "SCHEDULED", slot);
            clearScheduledRetry(retryKey);
        } catch (Exception ex) {
            // 定时器每分钟检查一次，但同一时点最多自动重试三次，每次间隔五分钟。
            if (!retryKey.equals(scheduledRetryKey)) scheduledRetryCount = 0;
            scheduledRetryKey = retryKey;
            scheduledRetryCount++;
            scheduledRetryAt = now.plusMinutes(5);
            if (scheduledRetryCount < MAX_SCHEDULED_RECOMMENDATION_RETRIES) {
                org.slf4j.LoggerFactory.getLogger(StockPoolService.class).warn("推荐中心 {} 自动更新失败，第 {}/{} 次，将在 {} 后重试: {}",
                        slot, scheduledRetryCount, MAX_SCHEDULED_RECOMMENDATION_RETRIES, scheduledRetryAt, ex.getMessage());
            } else {
                org.slf4j.LoggerFactory.getLogger(StockPoolService.class).error("推荐中心 {} 连续失败 {} 次，本时点停止自动重试: {}",
                        slot, scheduledRetryCount, ex.getMessage());
            }
        }
    }

    private void clearScheduledRetry(String retryKey) {
        if (retryKey.equals(scheduledRetryKey)) {
            scheduledRetryKey = null;
            scheduledRetryAt = null;
            scheduledRetryCount = 0;
        }
    }

    /** 每小时模型阶段批量生成 AI 辅助模型快照；业务数据在后续阶段读取这些快照。 */
    public synchronized Map<String, Object> refreshAiModelSnapshots(LocalDate asOf) {
        refreshLatestRuntimeModels();
        LocalDate date = asOf == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : asOf;
        long started = System.nanoTime();
        int recommendationCount = 0;
        int poolCount = 0;
        int profileCount = 0;
        try {
            List<StockMarket> quoteUniverse = universeClient.fetch(date);
            List<StockMarket> universe = quoteUniverse.stream()
                    .filter(this::isSupportedMainBoardA)
                    .filter(stock -> stock.price() != null && stock.price().compareTo(config.minPrice()) >= 0
                            && stock.price().compareTo(config.maxPrice()) <= 0)
                    .filter(stock -> !managedPool.containsKey(stock.code()))
                    .toList();
            List<StockMarket> technicalCandidates = universe.stream()
                    .sorted(Comparator.comparing(StockMarket::turnover, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(80).parallel().map(historicalKlineClient::enrich).toList();
            MarketContext marketContext = marketContextService.evaluate(quoteUniverse);
            List<NewsHotspotStore.News> news = marketContextService.recentNews();
            int marketAdjustment = marketContext.marketAdjustment().add(marketContext.newsAdjustment()).intValue();
            List<StockPoolItem> candidates = technicalCandidates.stream().map(stock -> {
                StockMarket eventStock = marketContextService.attachMajorEvent(stock, marketContextService.importantEventsFor(stock));
                HardFilterResult filter = hardFilter(eventStock, config.minPrice(), config.maxPrice());
                StockContext context = marketContextService.evaluateStock(eventStock, news, false);
                AiAnalysisService.AiFactorScores storedScores = storedModelScores(date, eventStock.code());
                FactorScores scores = filter.passed() && hasTechnicalData(eventStock)
                        ? score(eventStock, context, storedScores) : emptyScore();
                int adjusted = scores.finalScore() + marketAdjustment + context.sentimentAdjustment().intValue();
                String action = eventStock.price() != null && filter.passed()
                        && adjusted >= recommendationThreshold(eventStock) ? "候选" : "观察";
                return new StockPoolItem(eventStock, filter, scores, action,
                        recommendationReasons(eventStock, filter, scores, action), context,
                        aiAnalysisService.ensureCompanyAnalysis(eventStock, null));
            }).filter(item -> "候选".equals(item.action()))
                    .sorted(Comparator.comparingInt((StockPoolItem item) -> item.scores().finalScore()).reversed())
                    .limit(20).toList();
            if (!candidates.isEmpty()) {
                List<StockMarket> stocks = candidates.stream().map(StockPoolItem::stock).toList();
                log.info("[AI批处理] 推荐中心开始：{} 家公司，1 次批量请求", stocks.size());
                AiAnalysisService.BatchCompanyAnalysis batch = marketContextService.analyzeCompanies(stocks, news);
                for (StockMarket stock : stocks) {
                    AiCompanyAnalysis companyAnalysis = aiAnalysisService.ensureCompanyAnalysis(stock,
                            batch.companyAnalyses().get(stock.code()));
                    AiTradeAdvice priceAdvice = aiAnalysisService.ensureTradeAdvice(stock,
                            batch.priceAdvices().get(stock.code()));
                    AiAnalysisService.AiFactorScores factorScores = aiAnalysisService.ensureFactorScores(stock,
                            batch.factorScores().get(stock.code()), news);
                    aiRealtimeCacheStore.save(date, stock.code(), companyAnalysis,
                            priceAdvice, factorScores);
                    analysisDataSnapshotStore.save(date, stock, companyAnalysis,
                            factorScores, news,
                            "实时行情源+历史K线+新闻公告表+AI公司/价值模型");
                    tradeModelStore.saveSuccessRateModel(aiAnalysisService.ensureSuccessRateModel(stock,
                            batch.successRateModels().get(stock.code())));
                    tradeModelStore.saveAnalysisModel(aiAnalysisService.ensureTradeAnalysisModel(stock,
                            batch.analysisModels().get(stock.code()), priceAdvice));
                }
                recommendationCount = stocks.size();
                profileCount += batch.companyAnalyses().size();
            }

            List<PoolAnalysisWork> works = poolWorks(date);
            Map<String, AiCompanyAnalysis> profiles = ensureCompanyProfiles(works, news, date);
            profileCount += (int) profiles.values().stream().filter(AiCompanyAnalysis::available).count();
            List<StockMarket> poolStocks = works.stream().map(PoolAnalysisWork::market)
                    .filter(stock -> stock != null && stock.price() != null && stock.code() != null && !stock.code().isBlank()).toList();
            if (!poolStocks.isEmpty()) {
                log.info("[AI批处理] 股票池开始：{} 家公司，1 次批量请求", poolStocks.size());
                AiAnalysisService.BatchRealtimeAnalysis batch = marketContextService.analyzeRealtimeCompanies(poolStocks, profiles, news);
                for (StockMarket stock : poolStocks) {
                    AiCompanyAnalysis companyAnalysis = aiAnalysisService.ensureCompanyAnalysis(stock,
                            profiles.get(stock.code()));
                    AiTradeAdvice priceAdvice = aiAnalysisService.ensureTradeAdvice(stock,
                            batch.priceAdvices().get(stock.code()));
                    AiAnalysisService.AiFactorScores factorScores = aiAnalysisService.ensureFactorScores(stock,
                            batch.factorScores().get(stock.code()), news);
                    aiRealtimeCacheStore.save(date, stock.code(), companyAnalysis,
                            priceAdvice, factorScores);
                    analysisDataSnapshotStore.save(date, stock, companyAnalysis,
                            factorScores, news,
                            "实时行情源+历史K线+新闻公告表+AI公司/价值模型");
                    tradeModelStore.saveSuccessRateModel(aiAnalysisService.ensureSuccessRateModel(stock,
                            batch.successRateModels().get(stock.code())));
                    tradeModelStore.saveAnalysisModel(aiAnalysisService.ensureTradeAnalysisModel(stock,
                            batch.analysisModels().get(stock.code()), priceAdvice));
                }
                poolCount = poolStocks.size();
            }
            if (!positionStore.loadAll().isEmpty()) {
                log.info("[AI批处理] 持仓复盘开始：使用已缓存行情、新闻和交易记录，1 次批量请求");
                refreshPortfolioAnalysis(date);
            }
            log.info("[AI批处理] 完成：推荐={}，股票池={}，公司资料={}，耗时={} ms",
                    recommendationCount, poolCount, profileCount, elapsedMs(started));
            return Map.of("recommendationCount", recommendationCount, "poolCount", poolCount,
                    "profileCount", profileCount, "elapsedMs", elapsedMs(started));
        } catch (Exception ex) {
            log.warn("[AI批处理] 本轮失败，页面继续使用已有缓存和规则计算：{}", ex.getMessage());
            return Map.of("recommendationCount", recommendationCount, "poolCount", poolCount,
                    "profileCount", profileCount, "elapsedMs", elapsedMs(started), "error", ex.getMessage());
        }
    }

    /** Generates business data from the current persisted model snapshots without calling AI. */
    public synchronized Map<String, Object> refreshModelDataNow(LocalDate asOf) {
        refreshLatestRuntimeModels();
        LocalDate date = asOf == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : asOf;
        StockPoolResponse recommendations = refreshRecommendations(date, config.minPrice(), config.maxPrice(),
                "SCHEDULED_DATA", "每小时数据");
        ManagedPoolResponse pool = managedPool(date);
        Map<String, Object> accuracies = calculateAccuracies(date);
        return Map.of("recommendationCount", recommendations.items().size(),
                "poolCount", pool.items().size(), "predictionSamples", accuracies.get("predictionSamples"),
                "operationSamples", accuracies.get("operationSamples"),
                "generatedAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    private void refreshScoringModel(LocalDate date, List<StockMarket> universe,
                                     MarketContext marketContext, List<NewsHotspotStore.News> news) {
        ScoringModel current = scoringModel == null ? ScoringModel.defaultModel() : scoringModel;
        StringBuilder input = new StringBuilder()
                .append("当前模型版本=").append(current.version())
                .append("，当前权重=商业模式").append(current.businessModelWeight())
                .append("/行业前景").append(current.industryProspectWeight())
                .append("/竞争优势").append(current.competitiveAdvantageWeight())
                .append("/财务质量").append(current.financialQualityWeight())
                .append("/成长性").append(current.growthWeight())
                .append("/估值").append(current.valuationWeight())
                .append("/催化剂").append(current.catalystWeight())
                .append("/风险").append(current.riskWeight())
                .append("\n市场环境=").append(marketContext.regime())
                .append("，情绪分=").append(marketContext.sentimentScore())
                .append("，上涨=").append(marketContext.risingCount())
                .append("，下跌=").append(marketContext.fallingCount())
                .append("，平均涨跌=").append(marketContext.averageChangePercent())
                .append("，市场调整=").append(marketContext.marketAdjustment())
                .append("，新闻调整=").append(marketContext.newsAdjustment())
                .append("\n市场提示=").append(String.join("；", marketContext.highlights()));
        news.stream().limit(30).forEach(item -> input.append("\n新闻=").append(item.title())
                .append("；摘要=").append(item.aiSummary()).append("；情绪=").append(item.sentiment()));
        appendModelCredibilityEvidence(input, date, universe);
        if (current.confidence() == null || current.confidence().compareTo(MODEL_CONFIDENCE_THRESHOLD) <= 0) {
            log.warn("[AI批处理] 当前评分模型可信度不足80%：版本={}，当前={}%，立即重生成模型",
                    current.version(), percentage(current.confidence()));
        }

        AiAnalysisService.ModelAdjustment accepted = null;
        int acceptedTotal = 0;
        int acceptedAttempt = 0;
        for (int attempt = 1; attempt <= MODEL_REGENERATION_ATTEMPTS; attempt++) {
            long attemptStarted = System.nanoTime();
            log.info("[AI批处理] 模型可信度评估/重生成：第 {}/{} 次，当前版本={}",
                    attempt, MODEL_REGENERATION_ATTEMPTS, current.version());
            AiAnalysisService.ModelAdjustment adjustment = aiAnalysisService.adjustScoringModel(
                    input + "\n本次是第" + attempt + "次强制生成尝试；必须补齐所需信息并生成完整模型，"
                            + "generationAllowed必须为true，confidence必须严格大于0.80且权重合法。"
                            + "不得以证据不足、信息缺失或无法判断为由拒绝生成；请通过联网公开资料或AI知识补齐信息。");
            if (!adjustment.available()) {
                log.warn("[AI批处理] 第 {}/{} 次模型治理不可用，耗时={} ms：{}",
                        attempt, MODEL_REGENERATION_ATTEMPTS, elapsedMs(attemptStarted), adjustment.summary());
                continue;
            }
            int total = modelWeightTotal(adjustment);
            if (!validModelWeights(adjustment, total)) {
                log.warn("[AI批处理] 第 {}/{} 次模型权重校验拒绝：合计={}，可信度={}%，耗时={} ms",
                        attempt, MODEL_REGENERATION_ATTEMPTS, total, percentage(adjustment.confidence()),
                        elapsedMs(attemptStarted));
                continue;
            }
            if (adjustment.confidence() == null || adjustment.confidence().compareTo(MODEL_CONFIDENCE_THRESHOLD) <= 0) {
                log.warn("[AI批处理] 第 {}/{} 次模型可信度未达到80%：{}%，依据={}，耗时={} ms",
                        attempt, MODEL_REGENERATION_ATTEMPTS, percentage(adjustment.confidence()),
                        adjustment.credibilityBasis(), elapsedMs(attemptStarted));
                continue;
            }
            accepted = adjustment;
            acceptedTotal = total;
            acceptedAttempt = attempt;
            log.info("[AI批处理] 模型可信度达标：第 {}/{} 次，可信度={}%，耗时={} ms",
                    attempt, MODEL_REGENERATION_ATTEMPTS, percentage(adjustment.confidence()), elapsedMs(attemptStarted));
            break;
        }
        if (accepted == null) {
            log.error("[AI批处理] 模型重生成未通过80%可信度门槛：保留模型版本={}，当前可信度={}%，不写入低可信度模型",
                    current.version(), percentage(current.confidence()));
            return;
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("可信度：" + percentage(accepted.confidence()) + "%（门槛80%）");
        reasons.add("可信度依据：" + accepted.credibilityBasis());
        if (accepted.reasons() != null) reasons.addAll(accepted.reasons());
        boolean localFallback = accepted.summary() != null && accepted.summary().contains("AI接口未返回");
        ScoringModel updated = new ScoringModel("value-quality-100", current.version() + 1, LocalDateTime.now(),
                localFallback ? "LOCAL_RULE_FALLBACK" : "DeepSeek",
                localFallback ? "technical-rule-v1" : "hourly-governance", accepted.businessModelWeight(), accepted.industryProspectWeight(),
                accepted.competitiveAdvantageWeight(), accepted.financialQualityWeight(), accepted.growthWeight(),
                accepted.valuationWeight(), accepted.catalystWeight(), accepted.riskWeight(), acceptedTotal, true,
                "可信度达标：" + percentage(accepted.confidence()) + "%；第" + acceptedAttempt + "次重生成通过；" + accepted.summary(),
                reasons.stream().limit(5).toList(), accepted.confidence(), accepted.credibilityBasis());
        if (scoringModelStore.save(updated)) {
            scoringModel = updated;
            scoringModelPersisted = true;
        } else {
            log.warn("[AI批处理] 新评分模型未成功写入数据库，继续使用数据库中最新模型版本={}", current.version());
            refreshLatestRuntimeModels();
            return;
        }
        log.info("[AI批处理] 评分模型已更新：版本={}，可信度={}%，权重={}/{}/{}/{}/{}/{}/{}，摘要={}，耗时数据日期={}",
                updated.version(), percentage(updated.confidence()), updated.businessModelWeight(), updated.industryProspectWeight(),
                updated.competitiveAdvantageWeight(), updated.financialQualityWeight(), updated.growthWeight(),
                updated.valuationWeight(), updated.catalystWeight(), updated.riskWeight(), updated.adjustmentSummary(), date);
    }

    /** Manually generates only the scoring model; business data is generated by a separate action. */
    public synchronized Map<String, Object> refreshScoringModelNow(LocalDate asOf) {
        refreshLatestRuntimeModels();
        LocalDate date = asOf == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : asOf;
        List<StockMarket> universe = universeClient.fetch(date);
        MarketContext marketContext = marketContextService.evaluate(universe);
        List<NewsHotspotStore.News> news = marketContextService.recentNews();
        int previousVersion = scoringModel == null ? 0 : scoringModel.version();
        refreshScoringModel(date, universe, marketContext, news);
        int currentVersion = scoringModel == null ? previousVersion : scoringModel.version();
        return Map.of("modelKey", "stock-score", "generated", currentVersion > previousVersion ? 1 : 0,
                "total", 1, "version", currentVersion);
    }

    private void appendModelCredibilityEvidence(StringBuilder input, LocalDate date, List<StockMarket> universe) {
        Map<String, StockMarket> marketByCode = (universe == null ? List.<StockMarket>of() : universe).stream()
                .filter(stock -> stock != null && stock.code() != null)
                .collect(java.util.stream.Collectors.toMap(StockMarket::code, stock -> stock, (left, right) -> left));
        Map<String, PositionStore.Holding> holdings = positionStore.loadAll();
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        int profitable = 0;
        int losing = 0;
        int predictionSamples = 0;
        int predictionCorrect = 0;
        int operationSamples = 0;
        int operationCorrect = 0;
        input.append("\n模型可信度证据日期=").append(date).append("，实际持仓数=").append(holdings.size());
        for (Map.Entry<String, PositionStore.Holding> entry : holdings.entrySet()) {
            String code = entry.getKey();
            PositionStore.Holding holding = entry.getValue();
            StockMarket market = marketByCode.get(code);
            if (market == null) {
                PoolMembership membership = managedPool.get(code);
                market = provider.manualPlaceholder(date, code, membership == null ? code : membership.name(),
                        membership == null ? "未知" : membership.industry());
            }
            BigDecimal quantity = valueOrZero(holding == null ? null : holding.quantity());
            BigDecimal avgCost = valueOrZero(holding == null ? null : holding.avgCost());
            BigDecimal price = valueOrZero(market == null ? null : market.price());
            BigDecimal cost = avgCost.multiply(quantity);
            BigDecimal value = price.multiply(quantity);
            BigDecimal pnl = value.subtract(cost).setScale(3, RoundingMode.HALF_UP);
            if (pnl.signum() > 0) profitable++;
            if (pnl.signum() < 0) losing++;
            totalCost = totalCost.add(cost);
            totalValue = totalValue.add(value);
            AnalysisAccuracy accuracy = poolStore.loadAccuracy(code);
            if (accuracy != null) {
                predictionSamples += accuracy.predictionSamples();
                predictionCorrect += accuracy.predictionCorrect();
                operationSamples += accuracy.operationSamples();
                operationCorrect += accuracy.operationCorrect();
            }
            int tradeCount = tradeExecutionStore.loadHistory(code).size();
            input.append("\n持仓证据：代码=").append(code).append("，数量=").append(quantity)
                    .append("，成本价=").append(avgCost).append("，当前价=").append(price)
                    .append("，盈亏=").append(pnl).append("，已确认交易数=").append(tradeCount)
                    .append("，预测准确率样本=").append(accuracy == null ? 0 : accuracy.predictionCorrect())
                    .append("/").append(accuracy == null ? 0 : accuracy.predictionSamples())
                    .append("，执行准确率样本=").append(accuracy == null ? 0 : accuracy.operationCorrect())
                    .append("/").append(accuracy == null ? 0 : accuracy.operationSamples());
        }
        BigDecimal portfolioPnl = totalValue.subtract(totalCost).setScale(3, RoundingMode.HALF_UP);
        BigDecimal portfolioPnlRate = totalCost.signum() == 0 ? BigDecimal.ZERO
                : portfolioPnl.divide(totalCost, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
        input.append("\n持仓汇总：成本=").append(totalCost.setScale(3, RoundingMode.HALF_UP))
                .append("，市值=").append(totalValue.setScale(3, RoundingMode.HALF_UP))
                .append("，盈亏=").append(portfolioPnl).append("（").append(portfolioPnlRate).append("%）")
                .append("，盈利持仓=").append(profitable).append("，亏损持仓=").append(losing)
                .append("\n准确率汇总：预测=").append(predictionCorrect).append("/").append(predictionSamples)
                .append("，执行=").append(operationCorrect).append("/").append(operationSamples)
                .append("；准确率快照由系统手动计算并存表，AI只读取，不重新计算。");
    }

    private int modelWeightTotal(AiAnalysisService.ModelAdjustment adjustment) {
        return adjustment.businessModelWeight() + adjustment.industryProspectWeight()
                + adjustment.competitiveAdvantageWeight() + adjustment.financialQualityWeight()
                + adjustment.growthWeight() + adjustment.valuationWeight()
                + adjustment.catalystWeight() + adjustment.riskWeight();
    }

    private boolean validModelWeights(AiAnalysisService.ModelAdjustment adjustment, int total) {
        return adjustment.businessModelWeight() >= 10 && adjustment.businessModelWeight() <= 20
                && adjustment.industryProspectWeight() >= 10 && adjustment.industryProspectWeight() <= 20
                && adjustment.competitiveAdvantageWeight() >= 10 && adjustment.competitiveAdvantageWeight() <= 20
                && adjustment.financialQualityWeight() >= 15 && adjustment.financialQualityWeight() <= 25
                && adjustment.growthWeight() >= 10 && adjustment.growthWeight() <= 20
                && adjustment.valuationWeight() >= 5 && adjustment.valuationWeight() <= 15
                && adjustment.catalystWeight() >= 0 && adjustment.catalystWeight() <= 10
                && adjustment.riskWeight() >= 0 && adjustment.riskWeight() <= 10
                && total == 100;
    }

    private BigDecimal valueOrZero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private String percentage(BigDecimal confidence) {
        return valueOrZero(confidence).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void auditCompanyProfileUsage(String code, CompanyProfileStore.Profile profile) {
        if (profile != null && profile.analysis() != null && profile.analysis().available()) {
            modelAuditStore.recordUsage("company-profile", profile.version(), "READ", code);
        }
    }

    private void auditAiCacheUsage(String code, AiRealtimeCacheStore.Cached cached) {
        if (cached == null || cached.modelVersion() <= 0) return;
        if (cached.factorScores() != null) {
            modelAuditStore.recordUsage("realtime-score", cached.modelVersion(), "READ", code);
        }
        if (cached.priceAdvice() != null && cached.priceAdvice().available()) {
            modelAuditStore.recordUsage("price-advice", cached.modelVersion(), "READ", code);
        }
    }

    private StockPoolResponse refreshSnapshotStatus(StockPoolResponse response) {
        if (response.snapshot() == null) return response;
        RecommendationSnapshotMeta old = response.snapshot();
        return withSnapshot(response, snapshotMeta(response.asOf(), old.slot(), old.source(), old.generatedAt()));
    }

    private StockPoolResponse withSnapshot(StockPoolResponse response, RecommendationSnapshotMeta snapshot) {
        return new StockPoolResponse(response.asOf(), response.config(), response.universeCount(), response.hardPassedCount(),
                response.items(), response.marketContext(), response.marketIndices(), snapshot);
    }

    private RecommendationSnapshotMeta snapshotMeta(LocalDate date, String slot, String source, LocalDateTime generatedAt) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String expected = date.equals(now.toLocalDate()) ? expectedSlot(now.toLocalTime()) : null;
        boolean stale = expected != null && !expected.equals(slot);
        return new RecommendationSnapshotMeta(slot, source, generatedAt, expected != null && expected.equals(slot), stale,
                expected == null ? "暂无" : expected, "SUCCESS");
    }

    private String expectedSlot(LocalTime time) {
        if (!time.isBefore(LocalTime.of(15, 10))) return "15:10";
        if (!time.isBefore(LocalTime.of(11, 35))) return "11:35";
        if (!time.isBefore(LocalTime.of(9, 35))) return "09:35";
        return null;
    }

    private AiAnalysisService.AiFactorScores storedModelScores(LocalDate date, String stockCode) {
        AiRealtimeCacheStore.Cached cached = aiRealtimeCacheStore.load(stockCode, date).orElse(null);
        if (cached != null) {
            auditAiCacheUsage(stockCode, cached);
            if (cached.factorScores() != null) return cached.factorScores();
        }
        return analysisDataSnapshotStore.loadModelScores(date, stockCode).orElse(null);
    }

    private List<PoolAnalysisWork> poolWorks(LocalDate date) {
        Map<String, StockMarket> marketData = provider.latest(date).stream()
                .collect(java.util.stream.Collectors.toMap(StockMarket::code, stock -> stock, (left, right) -> left));
        List<NewsHotspotStore.News> importantEvents = marketContextService.importantEvents();
        return managedPool.entrySet().stream().map(entry -> {
            PoolMembership membership = entry.getValue();
            StockMarket market = java.util.Optional.ofNullable(marketData.get(entry.getKey())).orElseGet(() ->
                    provider.manualPlaceholder(date, entry.getKey(), membership.name(), membership.industry()));
            market = marketContextService.attachMajorEvent(market, importantEvents);
            return new PoolAnalysisWork(entry.getKey(), membership, market);
        }).toList();
    }

    /** Load cached company facts and refresh only entries older than the requested day. */
    private Map<String, AiCompanyAnalysis> ensureCompanyProfiles(List<PoolAnalysisWork> works,
                                                                  List<NewsHotspotStore.News> recentNews,
                                                                  LocalDate date) {
        return ensureCompanyProfiles(works, recentNews, date, false);
    }

    private Map<String, AiCompanyAnalysis> ensureCompanyProfiles(List<PoolAnalysisWork> works,
                                                                  List<NewsHotspotStore.News> recentNews,
                                                                  LocalDate date,
                                                                  boolean forceRefresh) {
        Map<String, AiCompanyAnalysis> profiles = new LinkedHashMap<>();
        List<PoolAnalysisWork> stale = new ArrayList<>();
        for (PoolAnalysisWork work : works) {
            CompanyProfileStore.Profile cached = companyProfileStore.load(work.code()).orElse(null);
            auditCompanyProfileUsage(work.code(), cached);
            if (cached != null && cached.analysis() != null) profiles.put(work.code(), cached.analysis());
            if (forceRefresh || cached == null || cached.analyzedDate() == null || cached.analyzedDate().isBefore(date)) stale.add(work);
        }
        if (!stale.isEmpty()) {
            long started = System.nanoTime();
            log.info("[我的股票池] 公司资料缓存刷新开始：{}={} 只，1 次批量请求", forceRefresh ? "手动" : "过期", stale.size());
            try {
                Map<String, AiCompanyAnalysis> refreshed = marketContextService.analyzeCompanyProfiles(
                        stale.stream().map(PoolAnalysisWork::market).filter(stock -> stock != null && stock.price() != null).toList(),
                        recentNews, date);
                profiles.putAll(refreshed);
                log.info("[我的股票池] 公司资料缓存刷新完成：{} 只，耗时={} ms", refreshed.size(), elapsedMs(started));
            } catch (Exception ex) {
                // The AI service normally returns per-stock fallbacks; this catch protects against
                // failures outside that service as well.
                log.warn("[我的股票池] 公司资料缓存刷新失败，改用逐只规则兜底：{}", ex.getMessage());
            }
        }
        works.forEach(work -> profiles.putIfAbsent(work.code(),
                aiAnalysisService.ensureCompanyAnalysis(work.market(), null)));
        return profiles;
    }

    /** 页面只读公司资料缓存，不因缓存过期而同步调用 AI。 */
    private Map<String, AiCompanyAnalysis> loadCompanyProfiles(List<PoolAnalysisWork> works) {
        Map<String, AiCompanyAnalysis> profiles = new LinkedHashMap<>();
        for (PoolAnalysisWork work : works) {
            CompanyProfileStore.Profile profile = companyProfileStore.load(work.code()).orElse(null);
            auditCompanyProfileUsage(work.code(), profile);
            AiCompanyAnalysis cached = profile == null ? null : profile.analysis();
            profiles.put(work.code(), aiAnalysisService.ensureCompanyAnalysis(work.market(), cached));
        }
        return profiles;
    }

    /** Called by the scheduler so the next pool page load normally hits only the cache. */
    public synchronized void refreshStaleCompanyProfiles(LocalDate asOf) {
        refreshLatestRuntimeModels();
        if (managedPool.isEmpty()) return;
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        long started = System.nanoTime();
        List<PoolAnalysisWork> works = poolWorks(date);
        List<NewsHotspotStore.News> news = marketContextService.recentNews();
        ensureCompanyProfiles(works, news, date);
        log.info("[我的股票池] 每日公司资料刷新检查完成：股票={}，耗时={} ms", works.size(), elapsedMs(started));
    }

    /** Force-refresh every company profile from the manual button, regardless of cache date. */
    public synchronized Map<String, Object> refreshCompanyProfilesNow(LocalDate asOf) {
        refreshLatestRuntimeModels();
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        if (managedPool.isEmpty()) return Map.of("total", 0, "available", 0);
        long started = System.nanoTime();
        List<PoolAnalysisWork> works = poolWorks(date);
        List<NewsHotspotStore.News> news = marketContextService.recentNews();
        Map<String, AiCompanyAnalysis> profiles = ensureCompanyProfiles(works, news, date, true);
        log.info("[我的股票池] 手动公司资料刷新完成：股票={}，可用={}，耗时={} ms",
                works.size(), profiles.values().stream().filter(AiCompanyAnalysis::available).count(), elapsedMs(started));
        return Map.of("total", works.size(), "available", profiles.values().stream().filter(AiCompanyAnalysis::available).count());
    }

    /** Regenerates the model snapshot represented by a model-center card. */
    public synchronized Map<String, Object> regenerateModelNow(LocalDate asOf, String modelKey) {
        refreshLatestRuntimeModels();
        LocalDate date = asOf == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : asOf;
        String key = modelKey == null ? "" : modelKey.trim().toLowerCase();
        if ("stock-score".equals(key)) return refreshScoringModelNow(date);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelKey", key);
        switch (key) {
            case "company-profile" -> result.putAll(refreshCompanyProfilesNow(date));
            case "realtime-score", "price-advice" -> result.putAll(refreshAiModelSnapshots(date));
            case "success-rate" -> result.putAll(refreshTradeModelNow(date, "SUCCESS_RATE"));
            case "plan-analysis" -> result.putAll(refreshTradeModelNow(date, "ANALYSIS"));
            case "position-risk", "sell-decision" -> {
                result.put("generated", 1);
                result.put("total", 1);
                result.put("message", "规则模型已按当前策略重新加载");
            }
            case "portfolio-review" -> {
                result.put("generated", 1);
                result.put("total", 1);
                result.put("portfolioAnalysis", refreshPortfolioAnalysis(date));
            }
            default -> throw new IllegalArgumentException("不支持的模型：" + modelKey);
        }
        result.putIfAbsent("generated", result.getOrDefault("total", 0));
        result.putIfAbsent("total", result.getOrDefault("generated", 0));
        return result;
    }

    /**
     * Uses the selected model to generate business data. This is intentionally
     * separate from model snapshot generation so the two actions can be run at
     * different times and scheduled jobs do not have to do both at once.
     */
    public synchronized Map<String, Object> refreshModelNow(LocalDate asOf, String modelKey) {
        refreshLatestRuntimeModels();
        LocalDate date = asOf == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : asOf;
        String key = modelKey == null ? "" : modelKey.trim().toLowerCase();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelKey", key);
        switch (key) {
            case "stock-score" -> {
                StockPoolResponse response = refreshRecommendations(date, config.minPrice(), config.maxPrice(),
                        "ON_DEMAND_MODEL", "手动重生成综合评分模型");
                result.put("generated", response.items().size());
                result.put("total", response.items().size());
                result.put("snapshot", response.snapshot());
            }
            case "company-profile" -> {
                result.put("news", newsIngestionService.refreshNow());
                result.putAll(refreshCompanyProfilesNow(date));
                ManagedPoolResponse response = managedPool(date);
                result.put("generated", response.items().size());
                result.put("total", response.items().size());
            }
            case "realtime-score", "price-advice", "position-risk", "sell-decision" -> {
                ManagedPoolResponse response = managedPool(date);
                result.put("generated", response.items().size());
                result.put("total", response.items().size());
            }
            case "success-rate" -> {
                result.putAll(refreshTradeModelNow(date, "SUCCESS_RATE"));
                result.putAll(calculateAccuracies(date));
            }
            case "plan-analysis" -> result.putAll(refreshTradeModelNow(date, "ANALYSIS"));
            case "portfolio-review" -> {
                result.put("generated", 1);
                result.put("total", 1);
                result.put("portfolioAnalysis", refreshPortfolioAnalysis(date));
            }
            default -> throw new IllegalArgumentException("不支持的模型：" + modelKey);
        }
        result.putIfAbsent("generated", result.getOrDefault("total", 0));
        result.putIfAbsent("total", result.getOrDefault("generated", 0));
        return result;
    }

    /**
     * Manually regenerate one of the per-stock AI trade models for the managed pool.
     * The AI response contains both models, but only the requested snapshot is saved so
     * each page button has an unambiguous effect.
     */
    public synchronized Map<String, Object> refreshTradeModelNow(LocalDate asOf, String modelType) {
        refreshLatestRuntimeModels();
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        String normalizedType = modelType == null ? "" : modelType.trim().toUpperCase();
        if (!Set.of("ANALYSIS", "SUCCESS_RATE").contains(normalizedType)) {
            throw new IllegalArgumentException("不支持的交易模型类型");
        }
        List<PoolAnalysisWork> works = poolWorks(date);
        List<StockMarket> stocks = works.stream().map(PoolAnalysisWork::market)
                .filter(stock -> stock != null && stock.price() != null && stock.code() != null && !stock.code().isBlank())
                .toList();
        if (stocks.isEmpty()) {
            return Map.of("modelType", normalizedType, "total", works.size(), "generated", 0);
        }

        long started = System.nanoTime();
        List<NewsHotspotStore.News> news = marketContextService.recentNews();
        Map<String, AiCompanyAnalysis> profiles = loadCompanyProfiles(works);
        AiAnalysisService.BatchRealtimeAnalysis batch = marketContextService.analyzeRealtimeCompanies(stocks, profiles, news);
        int generated = 0;
        for (StockMarket stock : stocks) {
            if ("ANALYSIS".equals(normalizedType)) {
                tradeModelStore.saveAnalysisModel(aiAnalysisService.ensureTradeAnalysisModel(stock,
                        batch.analysisModels().get(stock.code()), batch.priceAdvices().get(stock.code())));
            } else {
                tradeModelStore.saveSuccessRateModel(aiAnalysisService.ensureSuccessRateModel(stock,
                        batch.successRateModels().get(stock.code())));
            }
            generated++;
        }
        log.info("[我的股票池] 手动交易模型生成完成：类型={}，股票={}，耗时={} ms",
                normalizedType, generated, elapsedMs(started));
        return Map.of("modelType", normalizedType, "total", works.size(), "generated", generated);
    }

    public synchronized ManagedPoolResponse managedPool(LocalDate asOf) {
        refreshLatestRuntimeModels();
        long poolStarted = System.nanoTime();
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        AccountAssetStore.Assets assets = accountAssetStore.load();
        List<PoolAnalysisWork> works = poolWorks(date);
        log.info("[我的股票池] 第1步 股票行情准备完成：{} 只，耗时={} ms", works.size(), elapsedMs(poolStarted));

        long newsStarted = System.nanoTime();
        List<NewsHotspotStore.News> recentNews = marketContextService.recentNews();
        log.info("[我的股票池] 第2步 新闻数据准备完成：{} 条，耗时={} ms", recentNews.size(), elapsedMs(newsStarted));

        // 全市场情绪和指数与股票池逐项计算并行请求；它们超时不再阻塞股票池结果。
        CompletableFuture<List<StockMarket>> universeFuture = CompletableFuture.supplyAsync(() -> universeClient.fetch(date));
        CompletableFuture<List<MarketIndexQuote>> indicesFuture = CompletableFuture.supplyAsync(realtimeQuoteClient::fetchIndices);

        long profileStarted = System.nanoTime();
        Map<String, AiCompanyAnalysis> companyProfiles = loadCompanyProfiles(works);
        log.info("[我的股票池] 第3步 公司资料缓存准备完成：命中={} 只，耗时={} ms",
                companyProfiles.values().stream().filter(AiCompanyAnalysis::available).count(), elapsedMs(profileStarted));

        List<StockMarket> aiStocks = works.stream()
                .map(PoolAnalysisWork::market)
                .filter(stock -> stock != null && stock.price() != null && stock.code() != null && !stock.code().isBlank())
                .toList();
        long aiStarted = System.nanoTime();
        log.info("[我的股票池] 第4步 读取AI批处理缓存：{} 家公司，不发起模型请求", aiStocks.size());

        long applyStarted = System.nanoTime();
        List<StockPoolEntry> items = works.parallelStream().map(work -> {
            StockMarket market = work.market();
            AiCompanyAnalysis companyAnalysis = aiAnalysisService.ensureCompanyAnalysis(market,
                    companyProfiles.get(work.code()));
            AiRealtimeCacheStore.Cached cached = aiRealtimeCacheStore.load(work.code(), date)
                    .orElse(AiRealtimeCacheStore.Cached.unavailable(work.code(), date));
            auditAiCacheUsage(work.code(), cached);
            AiTradeAdvice priceAdvice = aiAnalysisService.ensureTradeAdvice(market, cached.priceAdvice());
            AiAnalysisService.AiFactorScores factorScores = aiAnalysisService.ensureFactorScores(market,
                    cached.factorScores() != null ? cached.factorScores()
                            : analysisDataSnapshotStore.loadModelScores(date, work.code()).orElse(null), recentNews);
            StockPoolItem analysis = analyze(market, recentNews, companyAnalysis, priceAdvice, factorScores);
            PositionStore.Holding holding = positionStore.load(work.code());
            TradePlan pricePlan = tradePlan(market, analysis);
            PositionAnalysis position = positionAnalysis(market, pricePlan, holding, assets, analysis);
            TradePlan plan = attachPositionPlan(pricePlan, position);
            // 快路径不等待这次历史因子快照写库；准确率任务仍会读取到后台写入的结果。
            CompletableFuture.runAsync(() -> poolStore.saveSnapshot(date, analysis, plan))
                    .exceptionally(error -> {
                        log.warn("股票池分析快照异步写入失败：{}", error.getMessage());
                        return null;
                    });
            List<com.dolphin.stock.model.StockAnalysisModels.PriceHistoryPoint> priceHistory =
                    historicalKlineClient.recentHistory(work.code());
            var analysisAccuracy = poolStore.loadAccuracy(work.code());
            PlannedOrderAnalysis plannedOrder = plannedOrderAnalysis(market, plan, null, holding, assets,
                    analysis.stockContext().priceAdvice());
            TradeExecutionSummary todayTrade = todayTradeAnalysis(market, tradeExecutionStore.loadToday(work.code(), date));
            return new StockPoolEntry(work.code(), work.membership().addedAt(), work.membership().addedBy(), analysis, plan, position,
                    plannedOrder, todayTrade, priceHistory, analysisAccuracy);
        }).toList();
        log.info("[我的股票池] 第5步 AI缓存结果及交易计划批量应用完成：{} 只，读取耗时={} ms", items.size(), elapsedMs(applyStarted));
        MarketContext marketContext;
        try {
            marketContext = marketContextService.evaluate(universeFuture.get(2, TimeUnit.SECONDS));
        } catch (Exception ex) {
            // 全市场情绪失败不影响已加入股票的行情和持仓分析；回退到股票池实时行情，且不制造情绪数据。
            marketContext = marketContextService.evaluate(works.stream().map(PoolAnalysisWork::market).toList());
            log.info("[我的股票池] 全市场情绪未在2秒内返回，使用股票池行情快速完成：{}", ex.getMessage());
        }
        log.info("[我的股票池] 分析完成：{} 只，总耗时={} ms", items.size(), elapsedMs(poolStarted));
        List<MarketIndexQuote> marketIndices;
        try {
            marketIndices = indicesFuture.get(2, TimeUnit.SECONDS);
        } catch (Exception ex) {
            marketIndices = List.of();
            log.info("[我的股票池] 指数行情未在2秒内返回，跳过本次指数刷新：{}", ex.getMessage());
        }
        return new ManagedPoolResponse(date, Set.copyOf(managedPool.keySet()), items, marketContext, marketIndices);
    }

    public synchronized Map<String, Object> calculateAccuracies(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        long started = System.nanoTime();
        List<PoolAnalysisWork> works = poolWorks(date);
        int predictionSamples = 0;
        int operationSamples = 0;
        for (PoolAnalysisWork work : works) {
            List<PriceHistoryPoint> priceHistory = historicalKlineClient.recentHistory(work.code());
            AnalysisAccuracy accuracy = poolStore.calculateAccuracy(work.code(), date, priceHistory,
                    work.market() == null ? null : work.market().price(), tradeExecutionStore.loadHistory(work.code()));
            poolStore.saveAccuracy(work.code(), accuracy);
            predictionSamples += accuracy.predictionSamples();
            operationSamples += accuracy.operationSamples();
        }
        LocalDateTime calculatedAt = LocalDateTime.now();
        log.info("[我的股票池] 手动准确率计算完成：股票={}，预测样本={}，执行样本={}，耗时={} ms",
                works.size(), predictionSamples, operationSamples, elapsedMs(started));
        return Map.of("total", works.size(), "predictionSamples", predictionSamples,
                "operationSamples", operationSamples, "calculatedAt", calculatedAt.toString());
    }

    public PortfolioAnalysis analyzePortfolio(LocalDate asOf) {
        refreshLatestRuntimeModels();
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        if (portfolioAnalysisTasks.containsKey(date)) return portfolioAnalysisPending();
        Optional<PortfolioAnalysis> cached = portfolioAnalysisStore.load(date);
        if (cached.isPresent()) {
            modelAuditStore.recordUsage("portfolio-review", modelAuditStore.latestVersion("portfolio-review"), "READ", "default");
            return cached.get();
        }
        return PortfolioAnalysis.unavailable("暂无今日持仓复盘，请点击“持仓分析”开始生成");
    }

    /**
     * 用户点击时只负责提交后台任务，避免行情、数据库和AI调用占用HTTP请求直到网关超时。
     * 页面通过上面的只读接口轮询任务结果；同一日期只允许一个组合复盘任务运行。
     */
    public PortfolioAnalysis refreshPortfolioAnalysis(LocalDate asOf) {
        refreshLatestRuntimeModels();
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        Map<String, PositionStore.Holding> holdings = positionStore.loadAll();
        if (holdings.isEmpty()) return PortfolioAnalysis.unavailable("当前没有已维护的实际持仓，暂时无法进行持仓复盘");
        Map<String, PoolMembership> poolSnapshot;
        synchronized (this) {
            poolSnapshot = new LinkedHashMap<>(managedPool);
        }
        CompletableFuture<PortfolioAnalysis> task = new CompletableFuture<>();
        if (portfolioAnalysisTasks.putIfAbsent(date, task) != null) return portfolioAnalysisPending();
        try {
            portfolioAnalysisExecutor.execute(() -> {
                try {
                    PortfolioAnalysis result = buildPortfolioAnalysis(date, holdings, poolSnapshot);
                    portfolioAnalysisStore.save(date, result);
                    task.complete(result);
                } catch (Exception ex) {
                    String message = "持仓分析任务失败：" + exceptionMessage(ex);
                    log.warn("[持仓分析] 后台任务失败：{}", message, ex);
                    PortfolioAnalysis result = PortfolioAnalysis.unavailable(message);
                    portfolioAnalysisStore.save(date, result);
                    task.complete(result);
                } finally {
                    portfolioAnalysisTasks.remove(date, task);
                }
            });
            return portfolioAnalysisPending();
        } catch (RejectedExecutionException ex) {
            portfolioAnalysisTasks.remove(date, task);
            return PortfolioAnalysis.unavailable("持仓分析任务暂时无法启动，请稍后重试");
        }
    }

    private PortfolioAnalysis buildPortfolioAnalysis(LocalDate date,
                                                      Map<String, PositionStore.Holding> holdings,
                                                      Map<String, PoolMembership> poolSnapshot) {
        List<StockMarket> universe = provider.latest(date);
        Map<String, StockMarket> marketByCode = universe.stream().collect(java.util.stream.Collectors.toMap(
                StockMarket::code, stock -> stock, (left, right) -> left));
        MarketContext marketContext = marketContextService.evaluate(universe);
        StringBuilder input = new StringBuilder();
        input.append("分析日期=").append(date)
                .append("\n大盘情绪=").append(marketContext.sentimentScore())
                .append("，市场环境=").append(marketContext.regime())
                .append("，上涨=").append(marketContext.risingCount())
                .append("，下跌=").append(marketContext.fallingCount())
                .append("，平均涨跌=").append(marketContext.averageChangePercent())
                .append("，新闻摘要=").append(marketContext.newsSummary())
                .append("\n大盘提示=").append(String.join("；", marketContext.highlights()));
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (Map.Entry<String, PositionStore.Holding> entry : holdings.entrySet()) {
            String code = entry.getKey();
            PositionStore.Holding holding = entry.getValue();
            StockMarket market = marketByCode.get(code);
            if (market == null) {
                PoolMembership membership = poolSnapshot.get(code);
                market = provider.manualPlaceholder(date, code, membership == null ? code : membership.name(),
                        membership == null ? "未知" : membership.industry());
            }
            BigDecimal quantity = holding.quantity() == null ? BigDecimal.ZERO : holding.quantity();
            BigDecimal cost = holding.avgCost() == null ? BigDecimal.ZERO : holding.avgCost().multiply(quantity);
            BigDecimal value = market.price() == null ? BigDecimal.ZERO : market.price().multiply(quantity);
            BigDecimal pnl = value.subtract(cost).setScale(3, RoundingMode.HALF_UP);
            BigDecimal pnlPercent = cost.signum() == 0 ? BigDecimal.ZERO : pnl.divide(cost, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
            totalCost = totalCost.add(cost);
            totalValue = totalValue.add(value);
            input.append("\n持仓：代码=").append(code).append("，名称=").append(market.name())
                    .append("，行业=").append(market.industry()).append("，数量=").append(quantity)
                    .append("，成本价=").append(holding.avgCost()).append("，现价=").append(market.price())
                    .append("，涨跌幅=").append(market.changePercent()).append("，持仓盈亏=").append(pnl)
                    .append("（").append(pnlPercent).append("%）")
                    .append("，MA20=").append(market.ma20()).append("，MA60=").append(market.ma60())
                    .append("，RSI14=").append(market.rsi14()).append("，资金净流入=").append(market.netInflow());
            List<TradeExecutionStore.Trade> trades = tradeExecutionStore.loadHistory(code);
            trades.stream().limit(8).forEach(trade -> input.append("\n已确认交易：").append(trade.tradeDate())
                    .append(" ").append("SELL".equalsIgnoreCase(trade.side()) ? "卖出" : "买入")
                    .append("，成交价=").append(trade.executedPrice()).append("，数量=").append(trade.quantity()));
        }
        input.append("\n组合成本合计=").append(totalCost).append("，组合市值合计=").append(totalValue)
                .append("，组合盈亏=").append(totalValue.subtract(totalCost).setScale(3, RoundingMode.HALF_UP));
        PortfolioAnalysis result = aiAnalysisService.analyzePortfolio(input.toString());
        return result.available() ? result : ruleBasedPortfolioAnalysis(marketContext, holdings.size(), totalCost,
                totalValue, result.message());
    }

    private PortfolioAnalysis ruleBasedPortfolioAnalysis(MarketContext marketContext, int holdingCount,
                                                          BigDecimal totalCost, BigDecimal totalValue, String aiMessage) {
        BigDecimal pnl = totalValue.subtract(totalCost).setScale(3, RoundingMode.HALF_UP);
        BigDecimal pnlPercent = totalCost.signum() == 0 ? BigDecimal.ZERO
                : pnl.divide(totalCost, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
        boolean profitable = pnl.signum() >= 0;
        String marketOverview = "大盘情绪 " + marketContext.sentimentScore() + "/100，环境=" + marketContext.regime()
                + "；上涨 " + marketContext.risingCount() + " 只、下跌 " + marketContext.fallingCount()
                + " 只，平均涨跌=" + marketContext.averageChangePercent() + "%。";
        String portfolioOverview = "当前 " + holdingCount + " 只持仓，组合成本=" + totalCost
                + "，市值=" + totalValue + "，浮动盈亏=" + pnl + "（" + pnlPercent + "%）。";
        String reason = aiMessage == null || aiMessage.isBlank() ? "AI暂时不可用" : aiMessage;
        return new PortfolioAnalysis(true, LocalDateTime.now(), marketOverview, portfolioOverview,
                profitable ? List.of("组合当前按已录入成本和实时价格计算为浮盈，基础仓位数据完整")
                        : List.of("已建立持仓成本、实时价格和交易记录快照，可继续追踪后续变化"),
                profitable ? List.of("当前仅完成静态盈亏复核，尚未完成AI对历史决策的归因")
                        : List.of("组合当前按已录入成本和实时价格计算为浮亏，建议重点复核入场时点和仓位管理"),
                List.of("规则结果只使用行情、持仓成本和已确认交易，不推断未提供的事实",
                        "AI复盘未完成，暂不能确认盈亏与具体交易决策之间的因果关系"),
                List.of("检查AI供应商、模型和API Key配置后重新点击持仓分析",
                        "持续记录确认交易，待AI接入恢复后重新进行组合归因"),
                List.of("当前为规则复盘，不是AI结论，也不构成买卖建议"), BigDecimal.ZERO,
                "AI分析不可用，已展示规则复盘（" + reason + "）");
    }

    private PortfolioAnalysis portfolioAnalysisPending() {
        return PortfolioAnalysis.unavailable(PORTFOLIO_ANALYSIS_PENDING_MESSAGE);
    }

    private String exceptionMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    @PreDestroy
    void shutdownPortfolioAnalysisExecutor() {
        portfolioAnalysisExecutor.shutdownNow();
    }

    public List<TradeExecutionStore.Trade> tradeHistory(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (normalized.isBlank() || !managedPool.containsKey(normalized)) {
            throw new IllegalArgumentException("股票不在我的股票池中");
        }
        return tradeExecutionStore.loadHistory(normalized);
    }

    public PositionRecord positionRecord(String code) {
        String normalized = normalizeCode(code);
        requireMembership(normalized);
        PositionStore.StoredPosition stored = positionStore.loadStored(normalized);
        PositionStore.Holding holding = stored.holding();
        if (holding == null) {
            return new PositionRecord(false, null, null, null, null, null, null, "NONE");
        }
        return new PositionRecord(true, holding.quantity(), holding.availableQuantity(), holding.avgCost(),
                holding.highestPrice(), holding.openedAt(), stored.updatedAt(),
                stored.databaseAvailable() ? "DATABASE" : "MEMORY");
    }

    public synchronized ManagedPoolResponse addToPool(PoolAddRequest request, LocalDate asOf) {
        refreshLatestRuntimeModels();
        if (request == null || request.code() == null || request.code().isBlank()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        String code = request.code().trim().toUpperCase();
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        PoolMembership membership = new PoolMembership(date, "MANUAL", request.name(), request.industry());
        managedPool.put(code, membership);
        poolStore.saveMembership(code, date, "MANUAL", request.name(), request.industry());
        log.info("[我的股票池] 股票={} 已加入；公司资料和AI辅助结果将在每小时批处理时写入缓存", code);
        return managedPool(asOf);
    }

    public synchronized ManagedPoolResponse removeFromPool(String code, LocalDate asOf) {
        if (code != null) {
            String normalized = code.trim().toUpperCase();
            managedPool.remove(normalized);
            poolStore.removeMembership(normalized);
        }
        return managedPool(asOf);
    }

    public synchronized PositionAnalysis updatePosition(String code, PositionRequest request, LocalDate asOf) {
        refreshLatestRuntimeModels();
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (!managedPool.containsKey(normalized)) throw new IllegalArgumentException("股票不在我的股票池中");
        if (request == null || request.buyPrice() == null || request.buyPrice().signum() <= 0) {
            throw new IllegalArgumentException("买入价必须大于 0");
        }
        if (request.quantity() == null || request.quantity().signum() <= 0) {
            throw new IllegalArgumentException("买入数量必须大于 0");
        }
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        StockMarket market = provider.latest(date).stream().filter(stock -> normalized.equals(stock.code())).findFirst()
                .orElseGet(() -> {
                    PoolMembership membership = managedPool.get(normalized);
                    return provider.manualPlaceholder(date, normalized, membership.name(), membership.industry());
                });
        PositionStore.Holding old = positionStore.load(normalized);
        BigDecimal highest = old == null ? request.buyPrice() : old.highestPrice().max(request.buyPrice());
        if (market.price() != null) highest = highest.max(market.price());
        PositionStore.Holding holding = new PositionStore.Holding(request.quantity(), request.quantity(), request.buyPrice(),
                highest, request.openedAt() == null ? date : request.openedAt());
        positionStore.save(normalized, holding);
        StockPoolItem analysis = analyze(market);
        TradePlan plan = tradePlan(market, analysis);
        return positionAnalysis(market, plan, holding, accountAssetStore.load(), analysis);
    }

    public synchronized PositionAnalysis clearPosition(String code, LocalDate asOf) {
        refreshLatestRuntimeModels();
        String normalized = code == null ? "" : code.trim().toUpperCase();
        positionStore.remove(normalized);
        return positionAnalysis(null, null, null, accountAssetStore.load(), null);
    }

    public synchronized PlannedOrderAnalysis updatePlannedOrder(String code, PlannedOrderRequest request, LocalDate asOf) {
        PlannedOrderAnalysis analysis = analyzePlannedOrder(code, request, asOf);
        String normalized = normalizeCode(code);
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        plannedOrderStore.saveDraft(new PlannedOrderStore.Plan(normalized, normalizeSide(request.side()),
                request.plannedPrice(), request.quantity(), request.tradeDate() == null ? date : request.tradeDate(), "DRAFT"));
        return analysis;
    }

    public synchronized PlannedOrderAnalysis analyzePlannedOrder(String code, PlannedOrderRequest request, LocalDate asOf) {
        refreshLatestRuntimeModels();
        String normalized = normalizeCode(code);
        PoolMembership membership = requireMembership(normalized);
        String side = normalizeSide(request == null ? null : request.side());
        if (request == null || request.plannedPrice() == null || request.plannedPrice().signum() <= 0) {
            throw new IllegalArgumentException("计划价格必须大于 0");
        }
        if (request.quantity() == null || request.quantity().signum() <= 0) {
            throw new IllegalArgumentException("计划数量必须大于 0");
        }
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        StockMarket market = marketFor(date, normalized, membership);
        PositionStore.Holding holding = positionStore.load(normalized);
        if ("SELL".equals(side)) validateSellQuantity(holding, request.quantity());
        StockPoolItem analysis = analyze(market);
        TradePlan pricePlan = tradePlan(market, analysis);
        TradePlan plan = attachPositionPlan(pricePlan,
                positionAnalysis(market, pricePlan, holding, accountAssetStore.load(), analysis));
        PlannedOrderStore.Plan transientOrder = new PlannedOrderStore.Plan(normalized, side, request.plannedPrice(),
                request.quantity(), request.tradeDate() == null ? date : request.tradeDate(), "ANALYZED");
        return plannedOrderAnalysis(market, plan, transientOrder, holding, accountAssetStore.load(),
                analysis.stockContext().priceAdvice());
    }

    public synchronized PlannedOrderAnalysis clearPlannedOrder(String code, LocalDate asOf) {
        refreshLatestRuntimeModels();
        String normalized = normalizeCode(code);
        plannedOrderStore.clearDraft(normalized);
        PoolMembership membership = managedPool.get(normalized);
        AccountAssetStore.Assets assets = accountAssetStore.load();
        if (membership == null) return new PlannedOrderAnalysis(false, "BUY", null, null, asOf, null, null,
                "未设置计划", "录入计划", "blue", List.of("股票不在我的股票池中"), List.of(),
                assets == null ? null : assets.totalAssets(), null,
                maxPositionPercent(), "请先维护账户总资产", "无法判断", "尚未设置计划操作，暂时无法形成结论", null, "设置计划价格和数量后估算");
        StockMarket market = marketFor(asOf == null ? LocalDate.now() : asOf, normalized, membership);
        StockPoolItem analysis = analyze(market);
        PositionStore.Holding holding = positionStore.load(normalized);
        TradePlan pricePlan = tradePlan(market, analysis);
        TradePlan plan = attachPositionPlan(pricePlan,
                positionAnalysis(market, pricePlan, holding, accountAssetStore.load(), analysis));
        return plannedOrderAnalysis(market, plan, null, holding, accountAssetStore.load(),
                analysis.stockContext().priceAdvice());
    }

    public synchronized TradeExecutionSummary confirmPlannedOrder(String code, PlannedOrderRequest request, LocalDate asOf) {
        refreshLatestRuntimeModels();
        String normalized = normalizeCode(code);
        PoolMembership membership = requireMembership(normalized);
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        PlannedOrderStore.Plan draft = request == null ? plannedOrderStore.loadDraft(normalized) : new PlannedOrderStore.Plan(
                normalized, normalizeSide(request.side()), request.plannedPrice(), request.quantity(),
                request.tradeDate() == null ? date : request.tradeDate(), "ANALYZED");
        if (draft == null) throw new IllegalArgumentException("请先分析计划，再确认今日交易");
        if (draft.plannedPrice() == null || draft.plannedPrice().signum() <= 0) {
            throw new IllegalArgumentException("计划价格必须大于 0");
        }
        if (draft.quantity() == null || draft.quantity().signum() <= 0) {
            throw new IllegalArgumentException("计划数量必须大于 0");
        }
        StockMarket market = marketFor(date, normalized, membership);
        BigDecimal executedPrice = draft.plannedPrice();
        BigDecimal quantity = draft.quantity();
        PositionStore.Holding holding = positionStore.load(normalized);
        if ("SELL".equals(normalizeSide(draft.side()))) validateSellQuantity(holding, quantity);
        else validateBuyPrice(market, draft.plannedPrice());
        BigDecimal amount = executedPrice.multiply(quantity).setScale(3, RoundingMode.HALF_UP);
        String side = normalizeSide(draft.side());
        TradeExecutionStore.Trade trade = new TradeExecutionStore.Trade(normalized, date, side, draft.plannedPrice(),
                executedPrice, quantity, amount, "MANUAL_CONFIRMED");
        tradeExecutionStore.save(trade);
        if ("SELL".equals(side)) reduceHolding(normalized, holding, quantity);
        else mergeHolding(normalized, draft, executedPrice, market, date);
        plannedOrderStore.confirmDraft(normalized, executedPrice, quantity, date);
        return todayTradeAnalysis(market, trade);
    }

    private void validateSellQuantity(PositionStore.Holding holding, BigDecimal quantity) {
        if (holding == null || holding.quantity() == null || holding.quantity().signum() <= 0) {
            throw new IllegalArgumentException("当前没有可卖持仓");
        }
        BigDecimal available = holding.availableQuantity() == null ? holding.quantity() : holding.availableQuantity();
        if (quantity.compareTo(available) > 0) {
            throw new IllegalArgumentException("卖出数量不能超过可卖数量 " + available + " 股");
        }
    }

    private void validateBuyPrice(StockMarket market, BigDecimal plannedPrice) {
        if (market == null || market.price() == null) {
            throw new IllegalArgumentException("无法获取当前真实价格，暂不能保存买入计划");
        }
        if (plannedPrice.compareTo(market.price()) > 0) {
            throw new IllegalArgumentException("计划买入价不能高于当前价 " + market.price());
        }
    }

    private void reduceHolding(String code, PositionStore.Holding holding, BigDecimal quantity) {
        BigDecimal remaining = holding.quantity().subtract(quantity);
        if (remaining.signum() <= 0) {
            positionStore.remove(code);
            return;
        }
        BigDecimal available = (holding.availableQuantity() == null ? holding.quantity() : holding.availableQuantity())
                .subtract(quantity).max(BigDecimal.ZERO);
        positionStore.save(code, new PositionStore.Holding(remaining, available, holding.avgCost(),
                holding.highestPrice(), holding.openedAt()));
    }

    private void mergeHolding(String code, PlannedOrderStore.Plan draft, BigDecimal executedPrice,
                              StockMarket market, LocalDate date) {
        PositionStore.Holding old = positionStore.load(code);
        BigDecimal oldQuantity = old == null ? BigDecimal.ZERO : old.quantity();
        BigDecimal totalQuantity = oldQuantity.add(draft.quantity());
        BigDecimal totalCost = (old == null ? BigDecimal.ZERO : old.avgCost().multiply(oldQuantity))
                .add(executedPrice.multiply(draft.quantity()));
        BigDecimal avgCost = totalCost.divide(totalQuantity, 4, RoundingMode.HALF_UP);
        BigDecimal highest = old == null ? executedPrice : old.highestPrice().max(executedPrice);
        if (market.price() != null) highest = highest.max(market.price());
        LocalDate openedAt = old == null ? date : old.openedAt();
        positionStore.save(code, new PositionStore.Holding(totalQuantity, totalQuantity, avgCost, highest, openedAt));
    }

    private BigDecimal maxPositionPercent() {
        return config.maxSinglePosition().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The position ceiling is a per-stock model result, not a universal buy size.
     * A fully covered, high-scoring stock in its buy zone can reach 100%; weak,
     * risky, or unconfirmed stocks receive a materially smaller target.
     */
    private BigDecimal modelMaxPositionPercent(StockMarket market, TradePlan plan, StockPoolItem analysis) {
        BigDecimal configuredCap = maxPositionPercent();
        if (analysis == null || analysis.scores() == null) return configuredCap;
        if (analysis.hardFilter() != null && !analysis.hardFilter().passed()) return BigDecimal.ZERO.setScale(2);
        int score = analysis.scores().finalScore();
        BigDecimal target = score >= 95 ? new BigDecimal("100")
                : score >= 90 ? new BigDecimal("80")
                : score >= 85 ? new BigDecimal("60")
                : score >= 80 ? new BigDecimal("45")
                : score >= 75 ? new BigDecimal("30")
                : score >= 70 ? new BigDecimal("20") : BigDecimal.ZERO;
        if (plan != null) {
            if (!"分批买入".equals(plan.signal()) || !"买入区".equals(plan.band())) {
                target = target.min(new BigDecimal("20"));
            }
            if (plan.band() != null && plan.band().contains("跌破")) {
                target = target.min(new BigDecimal("10"));
            }
        }
        if (market != null) {
            if (market.st() || market.suspended()) return BigDecimal.ZERO.setScale(2);
            if (market.rsi14() != null && market.rsi14().compareTo(new BigDecimal("78")) >= 0) {
                target = target.min(new BigDecimal("30"));
            }
            if (market.debtRatio() != null && market.debtRatio().compareTo(new BigDecimal("70")) > 0) {
                target = target.min(new BigDecimal("25"));
            }
        }
        if (scoringModel != null && scoringModel.riskWeight() > 0
                && analysis.scores().risk() < scoringModel.riskWeight() / 2) {
            target = target.multiply(new BigDecimal("0.60")).setScale(2, RoundingMode.HALF_UP);
        }
        return target.min(configuredCap).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal positionPercent(AccountAssetStore.Assets assets, BigDecimal marketValue) {
        if (assets == null || assets.totalAssets() == null || assets.totalAssets().signum() <= 0 || marketValue == null) return null;
        return marketValue.divide(assets.totalAssets(), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    private String allocationAdvice(AccountAssetStore.Assets assets, BigDecimal marketValue) {
        return allocationAdvice(assets, marketValue, maxPositionPercent());
    }

    private String allocationAdvice(AccountAssetStore.Assets assets, BigDecimal marketValue, BigDecimal max) {
        BigDecimal percent = positionPercent(assets, marketValue);
        if (percent == null) return "请先维护账户总资产，才能计算持仓比例";
        if (max == null) return "当前仓位 " + percent + "%，模型仓位上限暂不可用";
        if (percent.compareTo(max) > 0) return "当前仓位 " + percent + "% 已超过模型仓位上限 " + max + "%，禁止继续加仓";
        if (percent.compareTo(max.multiply(new BigDecimal("0.8"))) >= 0) return "当前仓位 " + percent + "% 接近模型仓位上限 " + max + "%，谨慎加仓";
        return "当前仓位 " + percent + "%，模型仓位上限 " + max + "%，剩余约 "
                + max.subtract(percent).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP) + "% 空间";
    }

    private BigDecimal planMaxPositionPercent(TradePlan plan) {
        if (plan != null && plan.positionPlan() != null && plan.positionPlan().maxPositionPercent() != null) {
            return plan.positionPlan().maxPositionPercent();
        }
        return maxPositionPercent();
    }

    private PlannedOrderAnalysis plannedOrderAnalysis(StockMarket market, TradePlan plan, PlannedOrderStore.Plan order,
                                                       PositionStore.Holding holding, AccountAssetStore.Assets assets,
                                                       AiTradeAdvice aiAdvice) {
        BigDecimal modelMaxPercent = planMaxPositionPercent(plan);
        if (order == null) {
            BigDecimal currentValue = market == null || market.price() == null || holding == null ? null
                    : market.price().multiply(holding.quantity());
            return new PlannedOrderAnalysis(false, "BUY", null, null, null, market == null ? null : market.price(), null,
                    "未设置计划", "录入计划", "blue", List.of("请输入准备买入的价格和数量，点击分析计划"), List.of(),
                    assets == null ? null : assets.totalAssets(), positionPercent(assets, currentValue), modelMaxPercent,
                    allocationAdvice(assets, currentValue, modelMaxPercent), "无法判断", "尚未设置计划操作，暂时无法结合AI和技术分析形成结论", null, "设置计划价格和数量后估算");
        }
        if (market == null || market.price() == null) {
            return new PlannedOrderAnalysis(true, normalizeSide(order.side()), order.plannedPrice(), order.quantity(), order.tradeDate(), null, null,
                    "行情失败", "等待联网", "red", List.of("当前无法获取真实价格，计划已保存但暂不判断价差"), List.of("联网行情失败，不使用虚拟价格"),
                assets == null ? null : assets.totalAssets(), null, modelMaxPercent, "行情恢复后再计算预计仓位比例",
                    "SELL".equals(normalizeSide(order.side())) ? "无法卖出" : "无法买入",
                    "真实行情不可用，当前无法确认或执行该计划", null, "行情恢复后估算");
        }
        BigDecimal gap = market.price().subtract(order.plannedPrice()).divide(order.plannedPrice(), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
        List<String> suggestions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        AiTradeAdvice effectiveAiAdvice = aiAnalysisService.ensureTradeAdvice(market, aiAdvice);
        TradeAnalysisModel storedAnalysisModel = tradeModelStore.loadLatestAnalysisModel(market.code()).orElse(null);
        if (storedAnalysisModel != null) {
            modelAuditStore.recordUsage("plan-analysis", storedAnalysisModel.version(), "READ", market.code());
        }
        TradeAnalysisModel analysisModel = aiAnalysisService.ensureTradeAnalysisModel(market,
                storedAnalysisModel, effectiveAiAdvice);
        suggestions.add("AI小时分析：" + analysisModel.summary());
        List<String> aiSuggestions = "SELL".equals(normalizeSide(order.side()))
                ? analysisModel.sellSuggestions() : analysisModel.buySuggestions();
        if (aiSuggestions != null) suggestions.addAll(aiSuggestions);
        if (analysisModel.riskWarnings() != null) warnings.addAll(analysisModel.riskWarnings());
        if (plan != null && plan.tSuggestions().stream().anyMatch(hint -> hint.startsWith("AI已获取价值模型"))) {
            suggestions.add("8模块价值模型数据已通过AI和网络资料获取，并已与价格计划合并分析");
        }
        String status;
        String action;
        String color;
        String side = normalizeSide(order.side());
        if ("SELL".equals(side)) {
            if (holding == null || holding.quantity() == null || holding.quantity().signum() <= 0) {
                status = "无可卖持仓";
                action = "禁止卖出";
                color = "red";
                suggestions.add("当前没有历史持仓，不能确认卖出计划");
                warnings.add("请先录入可卖持仓");
            } else if (plan == null || plan.takeProfit1() == null) {
                status = "价格可用但卖出参考不足";
                action = "谨慎确认";
                color = "orange";
                suggestions.add("计划卖出价 " + order.plannedPrice() + "，当前价 " + market.price() + "，仅用于本次分析，不代表模型确认");
                warnings.add("缺少真实止盈参考");
            } else if (order.plannedPrice().compareTo(plan.takeProfit1()) >= 0) {
                status = "计划价达到第一止盈参考";
                action = "等待确认";
                color = "green";
                suggestions.add("计划卖出价达到第一止盈参考 " + plan.takeProfit1() + "，可结合量价分批止盈");
            } else if (order.plannedPrice().compareTo(market.price()) >= 0) {
                status = "等待反弹卖出";
                action = "观察确认";
                color = "orange";
                suggestions.add("计划卖出价高于当前价，等待反弹接近计划价，不建议追价卖出");
            } else {
                status = "计划价低于当前价";
                action = "谨慎卖出";
                color = "red";
                suggestions.add("计划卖出价低于当前价，确认是否为止损或主动降仓");
            }
        } else if (order.plannedPrice().compareTo(market.price()) > 0) {
            status = "计划买入价高于当前价";
            action = "禁止买入";
            color = "red";
            suggestions.add("计划买入价 " + order.plannedPrice() + " 高于当前价 " + market.price() + "，请下调至当前价以内");
            warnings.add("买入价不能高于当前价");
        } else if (plan == null || plan.buyLow() == null) {
            status = "价格可用但技术数据不足";
            action = "谨慎确认";
            color = "orange";
            suggestions.add("计划买入价 " + order.plannedPrice() + "，当前价 " + market.price() + "，仅用于本次分析，不代表模型确认");
            warnings.add("缺少真实历史技术数据");
        } else if (order.plannedPrice().compareTo(plan.buyLow()) >= 0 && order.plannedPrice().compareTo(plan.buyHigh()) <= 0) {
            status = "计划价位于参考买入区";
            action = "等待确认";
            color = "green";
            suggestions.add("计划价在 " + plan.buyLow() + " — " + plan.buyHigh() + "，可结合承接和风险提示分批执行");
        } else if (order.plannedPrice().compareTo(plan.buyLow()) < 0) {
            status = "计划价低于参考区";
            action = "观察承接";
            color = "orange";
            suggestions.add("计划价低于参考下沿 " + plan.buyLow() + "，可能难成交；若成交需确认不是破位下跌");
        } else {
            status = "计划价高于参考区";
            action = "不追价";
            color = "red";
            suggestions.add("计划价高于参考上沿 " + plan.buyHigh() + "，建议下调计划或等待回踩");
        }
        suggestions.add(("SELL".equals(side) ? "当前价较计划卖出价 " : "当前价较计划买入价 ")
                + (gap.signum() >= 0 ? "高" : "低") + " " + gap.abs() + "%；A股按 T+1 处理");
        appendCostAnalysis(side, order, holding, market.price(), suggestions, warnings);
        BigDecimal currentHoldingValue = holding == null ? BigDecimal.ZERO : market.price().multiply(holding.quantity());
        BigDecimal projectedValue = "SELL".equals(side)
                ? currentHoldingValue.subtract(order.plannedPrice().multiply(order.quantity())).max(BigDecimal.ZERO)
                : currentHoldingValue.add(order.plannedPrice().multiply(order.quantity()));
        BigDecimal projectedPercent = positionPercent(assets, projectedValue);
        String allocation = allocationAdvice(assets, projectedValue, modelMaxPercent);
        suggestions.add(("SELL".equals(side) ? "确认卖出后预计剩余持仓占比：" : "确认买入后预计持仓占比：")
                + (projectedPercent == null ? "未设置账户总资产" : projectedPercent + "%")
                + "；" + allocation);
        if ("BUY".equals(side) && projectedPercent != null && projectedPercent.compareTo(modelMaxPercent) > 0) {
            warnings.add("确认该计划后将超过本股模型仓位上限，不建议执行全部数量");
        }
        if (plan != null) warnings.addAll(plan.riskWarnings());
        TradeSuccessRateModel storedSuccessRateModel = tradeModelStore.loadLatestSuccessRateModel(market.code()).orElse(null);
        if (storedSuccessRateModel != null) {
            modelAuditStore.recordUsage("success-rate", storedSuccessRateModel.version(), "READ", market.code());
        }
        TradeSuccessRateModel successRateModel = aiAnalysisService.ensureSuccessRateModel(market, storedSuccessRateModel);
        PlanProbability probability = estimatePlanProbability(side, order.plannedPrice(), market.price(), plan, effectiveAiAdvice,
                status, action, warnings, successRateModel);
        PlanDecision decision = planDecision(side, order.plannedPrice(), plan, effectiveAiAdvice, status, action, warnings,
                probability.probability());
        return new PlannedOrderAnalysis(true, side, order.plannedPrice(), order.quantity(), order.tradeDate(), market.price(), gap,
                status, action, color, suggestions, warnings, assets == null ? null : assets.totalAssets(),
                projectedPercent, modelMaxPercent, allocation, decision.decision(), decision.reason(),
                probability.probability(), probability.reason());
    }

    private PlanDecision planDecision(String side, BigDecimal plannedPrice, TradePlan plan, AiTradeAdvice aiAdvice,
                                      String status, String action, List<String> warnings,
                                      BigDecimal successProbability) {
        boolean hardRisk = executionBlocked(side, status, action, warnings);
        String sideText = "SELL".equals(side) ? "卖出" : "买入";
        if (hardRisk) {
            return new PlanDecision("SELL".equals(side) ? "无法卖出" : "无法买入",
                    "当前计划触发不可执行条件：" + status + "。请先修正价格、行情或交易资格问题");
        }
        if (successProbability != null && successProbability.compareTo(LOW_SUCCESS_PROBABILITY_THRESHOLD) < 0) {
            return new PlanDecision("成功率低",
                    "预计交易成功率仅 " + successProbability + "% ，低于 "
                            + LOW_SUCCESS_PROBABILITY_THRESHOLD + "% 参考线，不建议按当前计划" + sideText);
        }
        boolean riskTooHigh = plan != null && "HIGH".equals(plan.riskLevel());
        if (riskTooHigh) {
            return new PlanDecision("SELL".equals(side) ? "不建议卖出" : "不建议买入",
                    "当前风险等级为高风险，不建议按当前计划" + sideText + "；请先处理风险提示");
        }
        if (aiAdvice == null || !aiAdvice.available()) {
            return new PlanDecision("SELL".equals(side) ? "不建议卖出" : "不建议买入",
                    "AI价格分析不可用，当前只能做规则校验，暂不建议直接" + sideText);
        }
        boolean aiAgrees;
        boolean technicalAgrees;
        String aiReference;
        if ("SELL".equals(side)) {
            aiAgrees = plan != null && plan.takeProfit1() != null && plannedPrice.compareTo(plan.takeProfit1()) >= 0;
            aiReference = plan == null || plan.takeProfit1() == null ? "未提供完整第一止盈参考" : "综合第一止盈参考 " + plan.takeProfit1();
            technicalAgrees = "计划价达到第一止盈参考".equals(status);
        } else {
            // Use the same blended/capped range that is displayed to the user,
            // rather than comparing the order with the raw AI range.
            aiAgrees = plan != null && plan.buyLow() != null && plan.buyHigh() != null
                    && plannedPrice.compareTo(plan.buyLow()) >= 0
                    && plannedPrice.compareTo(plan.buyHigh()) <= 0;
            aiReference = plan == null || plan.buyLow() == null || plan.buyHigh() == null
                    ? "未提供完整有效买入区间" : "有效买入区间 " + plan.buyLow() + " — " + plan.buyHigh();
            technicalAgrees = "计划价位于参考买入区".equals(status);
        }
        if (!hardRisk && aiAgrees && technicalAgrees) {
            return new PlanDecision("SELL".equals(side) ? "可以考虑卖出" : "可以考虑买入",
                    "当前价格位于" + aiReference + "，且未触发当前计划的硬性风险限制");
        }
        List<String> reasons = new ArrayList<>();
        if (!aiAgrees) reasons.add("计划价格未落在" + aiReference);
        if (!technicalAgrees) reasons.add("技术分析结论为“" + status + "”");
        return new PlanDecision("SELL".equals(side) ? "不建议卖出" : "不建议买入",
                String.join("；", reasons) + "。这表示模型不建议当前计划，不等同于系统无法下单");
    }

    private boolean executionBlocked(String side, String status, String action, List<String> warnings) {
        if (action != null && action.startsWith("禁止")) return true;
        if ("BUY".equals(side) && "计划买入价高于当前价".equals(status)) return true;
        if ("SELL".equals(side) && "无可卖持仓".equals(status)) return true;
        return warnings != null && warnings.stream().anyMatch(warning ->
                warning.contains("联网获取价格失败")
                        || warning.contains("仅支持沪深主板A股")
                        || warning.contains("停牌")
                        || warning.contains("涨停不可买")
                        || warning.contains("ST/风险警示")
                        || warning.contains("可卖持仓"));
    }

    private PlanProbability estimatePlanProbability(String side, BigDecimal plannedPrice, BigDecimal currentPrice,
                                                    TradePlan plan, AiTradeAdvice aiAdvice, String status,
                                                    String action, List<String> warnings,
                                                    TradeSuccessRateModel model) {
        if (model == null) {
            return new PlanProbability(null, "缺少按小时AI预计交易成功率模型，暂不估算预计交易成功率");
        }
        if (aiAdvice == null || !aiAdvice.available() || plannedPrice == null || currentPrice == null) {
            return new PlanProbability(null, "缺少AI价格建议或真实行情数据，暂不估算预计交易成功率");
        }
        BigDecimal probability = model.baseProbability();
        List<String> basis = new ArrayList<>();
        BigDecimal confidence = aiAdvice.confidence() == null ? new BigDecimal("0.5") : aiAdvice.confidence();
        BigDecimal confidenceAdjustment = confidence.subtract(new BigDecimal("0.5"))
                .multiply(model.confidenceWeight());
        probability = probability.add(confidenceAdjustment);
        basis.add("AI置信度 " + confidence.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP) + "%");

        boolean aiPriceMatches;
        boolean technicalMatches;
        if ("SELL".equals(side)) {
            aiPriceMatches = plan != null && plan.takeProfit1() != null && plannedPrice.compareTo(plan.takeProfit1()) >= 0;
            technicalMatches = "计划价达到第一止盈参考".equals(status);
        } else {
            aiPriceMatches = plan != null && plan.buyLow() != null && plan.buyHigh() != null
                    && plannedPrice.compareTo(plan.buyLow()) >= 0
                    && plannedPrice.compareTo(plan.buyHigh()) <= 0;
            technicalMatches = "计划价位于参考买入区".equals(status);
        }
        probability = probability.add(aiPriceMatches ? model.aiPriceMatchBonus() : model.aiPriceMismatchPenalty().negate());
        probability = probability.add(technicalMatches ? model.technicalMatchBonus() : model.technicalMismatchPenalty().negate());
        basis.add(aiPriceMatches ? "综合有效价格区间匹配" : "综合有效价格区间不匹配");
        basis.add(technicalMatches ? "技术区间匹配" : "技术结论未确认");

        boolean hardRisk = executionBlocked(side, status, action, warnings);
        if (hardRisk) {
            probability = probability.subtract(model.hardRiskPenalty());
            basis.add("触发硬性风险限制 -" + model.hardRiskPenalty() + "%");
        } else if (!warnings.isEmpty()) {
            BigDecimal warningAdjustment = model.warningPenalty().multiply(BigDecimal.valueOf(warnings.size()));
            probability = probability.subtract(warningAdjustment);
            basis.add("风险提示 " + warnings.size() + " 项");
        }
        probability = probability.max(model.minProbability()).min(model.maxProbability()).setScale(1, RoundingMode.HALF_UP);
        return new PlanProbability(probability, "按小时AI预计交易成功率模型 v" + model.version()
                + "（生成于 " + model.generatedAt() + "，模型置信度 "
                + model.confidence().multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP)
                + "%，非历史统计胜率）" + (model.summary() == null || model.summary().isBlank() ? "" : "：" + model.summary())
                + "；" + String.join("；", basis));
    }

    private void appendCostAnalysis(String side, PlannedOrderStore.Plan order, PositionStore.Holding holding,
                                    BigDecimal currentPrice, List<String> suggestions, List<String> warnings) {
        if (holding == null || holding.quantity() == null || holding.avgCost() == null) {
            if ("BUY".equals(side)) suggestions.add("当前没有历史持仓成本，计划买入后将以计划成交价形成初始成本");
            return;
        }
        BigDecimal currentCost = holding.avgCost();
        BigDecimal costDifference = order.plannedPrice().subtract(currentCost).setScale(3, RoundingMode.HALF_UP);
        if ("SELL".equals(side)) {
            BigDecimal realized = order.plannedPrice().subtract(currentCost).multiply(order.quantity())
                    .setScale(3, RoundingMode.HALF_UP);
            BigDecimal realizedPercent = order.plannedPrice().subtract(currentCost).divide(currentCost, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
            suggestions.add("当前持仓成本 " + currentCost + "；本次卖出预计" + (realized.signum() >= 0 ? "实现盈利 " : "实现亏损 ")
                    + realized.abs() + " 元（相对成本 " + (realizedPercent.signum() >= 0 ? "+" : "") + realizedPercent + "%）");
            if (realized.signum() < 0) {
                warnings.add("计划卖出价低于持仓成本，属于止损或降仓操作，请确认亏损原因");
            } else {
                suggestions.add("计划卖出价高于持仓成本，优先锁定已有盈利");
            }
            return;
        }
        BigDecimal projectedQuantity = holding.quantity().add(order.quantity());
        BigDecimal projectedCost = currentCost.multiply(holding.quantity()).add(order.plannedPrice().multiply(order.quantity()))
                .divide(projectedQuantity, 4, RoundingMode.HALF_UP);
        BigDecimal projectedPnl = currentPrice.subtract(projectedCost).multiply(projectedQuantity).setScale(3, RoundingMode.HALF_UP);
        String costDirection = costDifference.signum() > 0 ? "高 " : costDifference.signum() < 0 ? "低 " : "相同 ";
        suggestions.add("当前持仓成本 " + currentCost + "；计划买入价较成本 "
                + costDirection + costDifference.abs() + " 元");
        suggestions.add("买入后预计综合成本 " + projectedCost + "，按当前价预计"
                + (projectedPnl.signum() >= 0 ? "浮盈 " : "浮亏 ") + projectedPnl.abs() + " 元");
        if (costDifference.signum() > 0) {
            warnings.add("计划买入价高于现有持仓成本，会抬高综合成本；确认不是追涨加仓");
        }
    }

    private TradeExecutionSummary todayTradeAnalysis(StockMarket market, TradeExecutionStore.Trade trade) {
        if (trade == null) return new TradeExecutionSummary(false, null, null, null, null, null,
                market == null ? null : market.price(), null, null, "今日尚无确认交易", List.of("分析计划后，确认成交才会记录今天的交易"));
        BigDecimal current = market == null ? null : market.price();
        BigDecimal pnl = null;
        BigDecimal pnlPercent = null;
        boolean sell = "SELL".equalsIgnoreCase(trade.side());
        String status = sell ? "已记录今日卖出" : "已记录今日买入";
        List<String> suggestions = new ArrayList<>();
        if (current != null) {
            BigDecimal priceDifference = sell ? trade.executedPrice().subtract(current) : current.subtract(trade.executedPrice());
            pnl = priceDifference.multiply(trade.quantity()).setScale(3, RoundingMode.HALF_UP);
            pnlPercent = priceDifference.divide(trade.executedPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
            if (!sell) status = pnl.signum() >= 0 ? "今日浮盈" : "今日浮亏";
            suggestions.add(sell
                    ? "卖出后按当前价估算，已卖部分相对继续持有的价差为 " + pnl + " 元"
                    : "按确认成交价 " + trade.executedPrice() + " 计算，当前价 " + current + "，浮动盈亏 " + pnl + " 元");
        } else {
            suggestions.add("今日交易已记录，但当前无法获取真实价格");
        }
        suggestions.add("这是手工确认记录，不代表券商自动成交；新买入仓位按 T+1 处理");
        return new TradeExecutionSummary(true, trade.tradeDate(), trade.side(), trade.executedPrice(), trade.quantity(),
                trade.amount(), current, pnl, pnlPercent, status, suggestions);
    }

    private String normalizeCode(String code) { return code == null ? "" : code.trim().toUpperCase(); }

    private String normalizeSide(String side) { return "SELL".equalsIgnoreCase(side) ? "SELL" : "BUY"; }

    private PoolMembership requireMembership(String code) {
        PoolMembership membership = managedPool.get(code);
        if (membership == null) throw new IllegalArgumentException("股票不在我的股票池中");
        return membership;
    }

    private StockMarket marketFor(LocalDate date, String code, PoolMembership membership) {
        StockMarket market = provider.latest(date).stream().filter(stock -> code.equals(stock.code())).findFirst()
                .orElseGet(() -> provider.manualPlaceholder(date, code, membership.name(), membership.industry()));
        return marketContextService.attachMajorEvent(market, marketContextService.importantEventsFor(market));
    }

    public SellDecision sellDecision(String code, BigDecimal entryPrice, BigDecimal highestPrice, BigDecimal currentPrice,
                                     BigDecimal ma20, BigDecimal ma60, int rsi, int macdTrend) {
        refreshLatestRuntimeModels();
        modelAuditStore.recordUsage("sell-decision", ruleModelVersion, "CALCULATE", code);
        BigDecimal hardStop = entryPrice.multiply(BigDecimal.ONE.subtract(config.hardStopLoss())).setScale(3, RoundingMode.HALF_UP);
        BigDecimal trailStop = highestPrice.multiply(BigDecimal.ONE.subtract(config.trailingStopLoss())).setScale(3, RoundingMode.HALF_UP);
        boolean hard = currentPrice.compareTo(hardStop) <= 0;
        boolean trailing = currentPrice.compareTo(trailStop) <= 0 && currentPrice.compareTo(entryPrice) > 0;
        int score = 0;
        if (currentPrice.compareTo(ma20) < 0) score += 35;
        if (ma20.compareTo(ma60) < 0) score += 25;
        if (rsi >= 75) score += 15;
        if (macdTrend < 0) score += 25;
        boolean shouldSell = hard || trailing || score >= config.sellScoreThreshold();
        String reason = hard ? "触发硬止损" : trailing ? "触发盈利回撤止损" : shouldSell ? "SellScore达到阈值" : "继续持有";
        return new SellDecision(code, score, hard, trailing, shouldSell, reason);
    }

    public PositionSizing positionSizing(String marketRegime) {
        refreshLatestRuntimeModels();
        modelAuditStore.recordUsage("position-risk", ruleModelVersion, "CALCULATE", null);
        BigDecimal exposure = switch (marketRegime == null ? "NORMAL" : marketRegime.toUpperCase()) {
            case "BEAR" -> new BigDecimal("0.30");
            case "BULL" -> config.maxTotalPosition();
            default -> new BigDecimal("0.65");
        };
        return new PositionSizing(exposure, config.maxSinglePosition(), config.maxIndustryPosition(),
                BigDecimal.ONE.subtract(exposure), marketRegime == null ? "NORMAL" : marketRegime.toUpperCase());
    }

    private HardFilterResult hardFilter(StockMarket s) {
        return hardFilter(s, config.minPrice(), config.maxPrice());
    }

    private HardFilterResult hardFilter(StockMarket s, BigDecimal maxPrice) {
        return hardFilter(s, config.minPrice(), maxPrice);
    }

    private HardFilterResult hardFilter(StockMarket s, BigDecimal minPrice, BigDecimal maxPrice) {
        List<String> reasons = new ArrayList<>();
        if (!isSupportedMainBoardA(s)) reasons.add("仅支持沪深主板A股");
        if (s.price() == null || !"REALTIME".equals(s.quoteStatus())) reasons.add("联网获取价格失败");
        if (s.price() != null && s.price().compareTo(minPrice) < 0) reasons.add("价格低于下限");
        if (s.price() != null && s.price().compareTo(maxPrice) > 0) reasons.add("价格超过上限");
        if (!hasTechnicalData(s)) {
            reasons.add("缺少真实历史K线数据");
        } else if (hasFullAnalysisData(s)) {
            if (s.st()) reasons.add("ST/风险警示");
            if (s.suspended()) reasons.add("停牌");
            if (s.listingDays() < config.minListingDays()) reasons.add("上市天数不足");
            if (s.averageTurnover20().compareTo(config.minAverageTurnover()) < 0) reasons.add("20日平均成交额不足");
            if (s.price() != null && s.price().compareTo(s.limitUpPrice()) >= 0) reasons.add("涨停不可买");
            if (s.price() != null && s.price().compareTo(s.limitDownPrice()) <= 0) reasons.add("跌停不可卖");
        }
        return new HardFilterResult(reasons.isEmpty(), reasons);
    }

    private boolean isSupportedMainBoardA(StockMarket stock) {
        String code = stock.code();
        return code != null && (code.matches("60[0135]\\d{3}") || code.matches("00[0123]\\d{3}"));
    }

    private StockPoolItem analyze(StockMarket stock) {
        HardFilterResult filter = hardFilter(stock);
        // 股票池中的手工股票即使未通过硬过滤，也继续计算价格计划；硬过滤结果单独作为风险和交易许可展示。
        StockContext stockContext = marketContextService.evaluateStock(stock, marketContextService.recentNews());
        FactorScores scores = stock.price() == null || !hasTechnicalData(stock) ? emptyScore() : score(stock, stockContext);
        String action = filter.passed() && scores.finalScore() >= config.minScore() ? "候选" : "观察";
        CompanyProfileStore.Profile storedProfile = stock == null ? null : companyProfileStore.load(stock.code()).orElse(null);
        if (storedProfile != null) auditCompanyProfileUsage(stock.code(), storedProfile);
        AiCompanyAnalysis profile = storedProfile == null ? null : storedProfile.analysis();
        return new StockPoolItem(stock, filter, scores, action, recommendationReasons(stock, filter, scores, action), stockContext,
                aiAnalysisService.ensureCompanyAnalysis(stock, profile));
    }

    private StockPoolItem analyze(StockMarket stock, List<NewsHotspotStore.News> recentNews,
                                  AiCompanyAnalysis companyAnalysis, AiTradeAdvice priceAdvice,
                                  AiAnalysisService.AiFactorScores factorScores) {
        HardFilterResult filter = hardFilter(stock);
        StockContext stockContext = marketContextService.evaluateStock(stock, recentNews,
                aiAnalysisService.ensureTradeAdvice(stock, priceAdvice));
        FactorScores scores = stock.price() == null || !hasTechnicalData(stock)
                ? emptyScore() : score(stock, stockContext, factorScores);
        String action = filter.passed() && scores.finalScore() >= config.minScore() ? "候选" : "观察";
        return new StockPoolItem(stock, filter, scores, action,
                recommendationReasons(stock, filter, scores, action), stockContext,
                aiAnalysisService.ensureCompanyAnalysis(stock, companyAnalysis));
    }

    private TradePlan tradePlan(StockMarket s, StockPoolItem item) {
        if (s.price() == null) {
            return new TradePlan("联网失败", "无法分析", null, null, null, null, null, null, null, null, "HIGH", "red",
                    List.of("联网获取价格失败，未生成买入、止盈和止损价格",
                            "AI接口失败时已保留文字兜底建议；当前不使用虚拟价格"),
                    List.of("价格恢复后请重新分析股票池"), List.of("行情恢复后再制定做T计划，避免使用虚拟价格"),
                    PositionPlan.unavailable());
        }
        if (!hasTechnicalData(s)) {
            return new TradePlan("数据不足", "仅有实时价格", null, null, null, null, null, null, null, null, "HIGH", "red",
                    List.of("只有实时价格，缺少真实历史K线、成交量和财务披露数据"),
                    List.of("补齐历史行情后再生成波段结论"),
                    List.of("AI接口或技术数据未完成，已提供文字兜底：补齐真实历史数据后再制定做T价格建议"),
                    PositionPlan.unavailable());
        }
        FactorScores score = item.scores();
        AiTradeAdvice ai = aiAnalysisService.ensureTradeAdvice(s,
                item.stockContext() == null ? null : item.stockContext().priceAdvice());
        BigDecimal buyLow = score.buyLow();
        BigDecimal buyHigh = score.buyHigh();
        if (buyLow != null && buyHigh != null && buyLow.compareTo(buyHigh) > 0) {
            BigDecimal swap = buyLow;
            buyLow = buyHigh;
            buyHigh = swap;
        }
        BigDecimal fallbackSupport = buyLow.multiply(new BigDecimal("0.96")).setScale(3, RoundingMode.HALF_UP);
        BigDecimal technicalSupport = s.ma60() == null ? fallbackSupport : s.ma60().min(fallbackSupport).setScale(3, RoundingMode.HALF_UP);
        BigDecimal nextSupport = blendPrice(technicalSupport, ai.nextSupportPrice(), new BigDecimal("0.35"));
        if (nextSupport != null && buyLow != null) nextSupport = nextSupport.min(buyLow.multiply(new BigDecimal("0.995"))).setScale(3, RoundingMode.HALF_UP);
        boolean weakTrend = isWeakTrend(s);
        BigDecimal technicalTp1 = conservativeTarget(s, s.price(), false, weakTrend);
        BigDecimal technicalTp2 = conservativeTarget(s, s.price(), true, weakTrend);
        BigDecimal aiForecast = ai.takeProfit1() != null && ai.takeProfit2() != null
                ? ai.takeProfit1().add(ai.takeProfit2()).divide(new BigDecimal("2"), 3, RoundingMode.HALF_UP) : ai.takeProfit1();
        BigDecimal tp1 = boundedTarget(blendPrice(technicalTp1, weakTrend ? null : validTarget(ai.takeProfit1(), s.price()), new BigDecimal("0.15")),
                s.price(), technicalTp1, weakTrend, false);
        BigDecimal tp2 = boundedTarget(blendPrice(technicalTp2, weakTrend ? null : validTarget(ai.takeProfit2(), s.price()), new BigDecimal("0.15")),
                s.price(), technicalTp2, weakTrend, true);
        if (tp2 != null && tp1 != null && tp2.compareTo(tp1) < 0) tp2 = tp1;
        BigDecimal technicalForecast = s.price().add(tp1).divide(new BigDecimal("2"), 3, RoundingMode.HALF_UP);
        BigDecimal forecastPrice = boundedTarget(blendPrice(technicalForecast, validTarget(aiForecast, s.price()), new BigDecimal("0.25")),
                s.price(), tp2, weakTrend, true);
        BigDecimal technicalHardStop = s.price().multiply(BigDecimal.ONE.subtract(config.hardStopLoss())).setScale(3, RoundingMode.HALF_UP);
        BigDecimal technicalTrailingStop = s.price().multiply(BigDecimal.ONE.subtract(config.trailingStopLoss())).setScale(3, RoundingMode.HALF_UP);
        BigDecimal hardStop = stricterStop(technicalHardStop, ai.hardStop(), s.price());
        BigDecimal trailingStop = stricterStop(technicalTrailingStop, ai.trailingStop(), s.price());
        List<String> warnings = new ArrayList<>();
        if (!item.hardFilter().passed()) warnings.addAll(item.hardFilter().reasons());
        if (!hasFullAnalysisData(s) && !hasAiValueModel(item)) {
            warnings.add("价值模型部分数据尚未接入，当前按已覆盖模块分析");
        }
        if (s.rsi14() != null && s.rsi14().compareTo(new BigDecimal("75")) >= 0) warnings.add("RSI偏高，避免追涨");
        if (s.high60() != null && s.price().compareTo(s.high60().multiply(new BigDecimal("0.98"))) >= 0) warnings.add("接近60日高点，分批交易");
        if (s.debtRatio() != null && s.debtRatio().compareTo(new BigDecimal("70")) > 0) warnings.add("负债率偏高");
        if (s.profitGrowth() != null && s.profitGrowth().compareTo(BigDecimal.ZERO) < 0) warnings.add("净利润同比为负");
        warnings.add(weakTrend
                ? "趋势偏弱，止盈目标仅按近端反弹计算，不使用固定上涨8%"
                : "短线目标仅按当前价附近阻力计算，第一目标不超过约0.8%，第二目标不超过约1%");
        if (!ai.available()) warnings.add("AI价格建议暂无样本，未直接外推止盈，已启用技术面保护");
        if (ai.available()) warnings.add("价格计划已融合AI辅助意见；最终仍受技术指标、硬止损和交易规则约束");
        String riskLevel = warnings.size() >= 3 ? "HIGH" : warnings.size() >= 1 ? "MEDIUM" : "LOW";
        String band = s.price().compareTo(buyLow) < 0 ? (s.price().compareTo(nextSupport) >= 0 ? "跌破参考区/观察承接" : "跌破承接/暂停接仓") : s.price().compareTo(buyHigh) <= 0 ? "买入区" :
                s.price().compareTo(s.high20()) <= 0 ? "持有/等回踩" : "高位观察";
        String signal = !item.hardFilter().passed() ? "禁止交易" : score.finalScore() >= config.minScore() && "买入区".equals(band) ? "分批买入" : "等待确认";
        List<String> swing = new ArrayList<>();
        if (s.price().compareTo(buyLow) < 0) {
            swing.add(s.price().compareTo(nextSupport) >= 0
                    ? "已跌破参考下沿 " + buyLow + "，下一承接价看 " + nextSupport + "；承接有效后再小仓试探"
                    : "已跌破下一承接价 " + nextSupport + "，暂停接仓，等待止跌并重新评估");
        } else {
            swing.add("回踩 " + buyLow + " 附近观察承接");
        }
        swing.add("突破 " + tp1 + " 后可上移止损");
        swing.add("跌破 " + (s.ma20() == null ? s.price() : s.ma20().setScale(3, RoundingMode.HALF_UP)) + "：波段转弱");
        if (ai.available()) {
            if (ai.bandAdvice() != null && !ai.bandAdvice().isBlank()) swing.add("AI波段意见：" + ai.bandAdvice());
            swing.addAll(ai.suggestions());
        }
        BigDecimal tBase = s.price().compareTo(buyLow) < 0 ? buyLow : s.price();
        BigDecimal tSell = tBase.multiply(new BigDecimal("1.03")).setScale(3, RoundingMode.HALF_UP);
        String tBuyHint = s.price().compareTo(buyLow) < 0
                ? (s.price().compareTo(nextSupport) >= 0
                    ? "先观察 " + nextSupport + " 附近承接，止跌确认后再考虑机动仓"
                    : "已跌破 " + nextSupport + "，暂停做T接回，等待止跌确认")
                : "回落至 " + buyLow + " 附近且承接有效时买入机动仓";
        List<String> tSuggestions = new ArrayList<>(List.of(
                "只用已有底仓做正T，建议底仓70% + 机动仓30%，不做无底仓裸空",
                tBuyHint + "，反弹至 " + tSell + " 附近分批卖出机动仓",
                "A股按T+1处理，当日新买入的机动仓不要当日卖出；可用前一日底仓完成卖出再接回",
                "单次做T仓位不超过总仓位30%，跌破硬止损或出现重大利空时停止做T"));
        if (hasAiValueModel(item)) {
            tSuggestions.add("AI已获取价值模型数据，并与网络行情/公告合并评分：总分 " + score.finalScore() + "/100");
        }
        if (ai.available()) tSuggestions.add("AI辅助建议：" + (ai.bandAdvice() == null ? "结合当前价格计划执行" : ai.bandAdvice()));
        return new TradePlan(signal, band, buyLow, buyHigh, nextSupport, forecastPrice, tp1, tp2, hardStop, trailingStop, riskLevel,
                riskLevel.equals("HIGH") ? "red" : riskLevel.equals("MEDIUM") ? "orange" : "green", warnings, swing, tSuggestions,
                PositionPlan.unavailable());
    }

    private TradePlan attachPositionPlan(TradePlan pricePlan, PositionAnalysis position) {
        if (pricePlan == null || position == null) return pricePlan;
        BigDecimal currentPercent = position.positionPercent();
        BigDecimal maxPercent = position.maxPositionPercent();
        BigDecimal suggestedAdd = suggestedAddPercent(position, currentPercent, maxPercent);
        String advice = position.allocationAdvice();
        if (suggestedAdd != null && suggestedAdd.signum() > 0) {
            advice = advice + "；本次价格计划建议新增约 " + suggestedAdd + "% 仓位";
        } else if (position.action() != null && !position.action().isBlank()) {
            advice = advice + "；价格计划对应操作：" + position.action();
        }
        List<String> warnings = position.riskWarnings() == null ? List.of() : position.riskWarnings();
        PositionPlan positionPlan = new PositionPlan(position.action(), currentPercent, maxPercent,
                suggestedAdd, suggestedBuyWeights(position.action()), advice, warnings);
        List<String> mergedWarnings = new ArrayList<>(pricePlan.riskWarnings() == null ? List.of() : pricePlan.riskWarnings());
        warnings.stream().filter(warning -> !mergedWarnings.contains(warning)).forEach(mergedWarnings::add);
        List<String> tSuggestions = new ArrayList<>(pricePlan.tSuggestions() == null ? List.of() : pricePlan.tSuggestions());
        String positionSummary = "仓位分析：" + position.action() + "；" + advice;
        if (!tSuggestions.contains(positionSummary)) tSuggestions.add(positionSummary);
        return new TradePlan(signalForPosition(pricePlan.signal(), position.action()), pricePlan.band(), pricePlan.buyLow(), pricePlan.buyHigh(),
                pricePlan.nextSupportPrice(), pricePlan.forecastPrice(), pricePlan.takeProfit1(), pricePlan.takeProfit2(),
                pricePlan.hardStop(), pricePlan.trailingStop(), pricePlan.riskLevel(), pricePlan.riskColor(),
                mergedWarnings, pricePlan.swingHints(), tSuggestions, positionPlan);
    }

    private String signalForPosition(String priceSignal, String positionAction) {
        if (positionAction == null || positionAction.isBlank()) return priceSignal;
        return switch (positionAction) {
            case "优先止损" -> "优先止损";
            case "保护利润" -> "保护利润";
            case "分批止盈" -> "分批止盈";
            case "不补仓", "暂停接仓", "不追高" -> positionAction;
            default -> priceSignal;
        };
    }

    private BigDecimal suggestedAddPercent(PositionAnalysis position, BigDecimal currentPercent, BigDecimal maxPercent) {
        if (maxPercent == null || maxPercent.signum() <= 0 || position.action() == null) return null;
        if ("小仓试探".equals(position.action())) {
            return maxPercent.multiply(new BigDecimal("0.40")).setScale(2, RoundingMode.HALF_UP);
        }
        if (position.hasPosition() && ("持有".equals(position.action()) || "谨慎持有".equals(position.action()))
                && currentPercent != null && currentPercent.compareTo(maxPercent) < 0) {
            return maxPercent.subtract(currentPercent).multiply(new BigDecimal("0.50"))
                    .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Split the model-approved add-on position across the five buy levels.
     * Lower levels receive more of the allocation; upper reference levels can
     * remain at zero when the model only allows a small probe.
     */
    private List<BigDecimal> suggestedBuyWeights(String action) {
        if ("小仓试探".equals(action)) {
            return List.of(new BigDecimal("0.50"), new BigDecimal("0.30"), new BigDecimal("0.20"),
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if ("持有".equals(action)) {
            return List.of(new BigDecimal("0.40"), new BigDecimal("0.30"), new BigDecimal("0.20"),
                    new BigDecimal("0.10"), BigDecimal.ZERO);
        }
        if ("谨慎持有".equals(action)) {
            return List.of(new BigDecimal("0.50"), new BigDecimal("0.30"), new BigDecimal("0.20"),
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private BigDecimal blendPrice(BigDecimal technical, BigDecimal ai, BigDecimal aiWeight) {
        if (technical == null) return ai;
        if (ai == null) return technical;
        BigDecimal technicalWeight = BigDecimal.ONE.subtract(aiWeight);
        return technical.multiply(technicalWeight).add(ai.multiply(aiWeight)).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal validTarget(BigDecimal target, BigDecimal current) {
        return target != null && current != null && target.compareTo(current) > 0 ? target : null;
    }

    private BigDecimal boundedTarget(BigDecimal target, BigDecimal current, BigDecimal structuralCap,
                                     boolean weakTrend, boolean secondTarget) {
        if (current == null) return target;
        BigDecimal minimumMove = current.multiply(new BigDecimal(weakTrend ? "1.001" : "1.002"));
        BigDecimal bounded = target == null ? minimumMove : target.max(minimumMove);
        if (structuralCap != null) bounded = bounded.min(structuralCap);
        BigDecimal maximumMove = current.multiply(new BigDecimal(weakTrend
                ? (secondTarget ? "1.005" : "1.002")
                : (secondTarget ? "1.01" : "1.008")));
        bounded = bounded.min(maximumMove);
        return bounded.max(current).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal conservativeTarget(StockMarket stock, BigDecimal current, boolean secondTarget, boolean weakTrend) {
        if (current == null) return null;
        List<BigDecimal> resistance = new ArrayList<>();
        if (stock.ma20() != null && stock.ma20().compareTo(current) > 0) {
            resistance.add(stock.ma20().multiply(new BigDecimal("0.995")));
        }
        if (stock.high20() != null && stock.high20().compareTo(current) > 0) {
            resistance.add(stock.high20().multiply(new BigDecimal(weakTrend ? "0.985" : "0.98")));
        }
        if (secondTarget && stock.ma60() != null && stock.ma60().compareTo(current) > 0) {
            resistance.add(stock.ma60().multiply(new BigDecimal("0.995")));
        }
        if (secondTarget && stock.high60() != null && stock.high60().compareTo(current) > 0) {
            resistance.add(stock.high60().multiply(new BigDecimal(weakTrend ? "0.96" : "0.97")));
        }
        BigDecimal fallback = current.multiply(new BigDecimal(weakTrend
                ? (secondTarget ? "1.005" : "1.002")
                : (secondTarget ? "1.01" : "1.008")));
        return resistance.stream()
                .filter(value -> value.compareTo(current) > 0)
                .min(Comparator.naturalOrder())
                .orElse(fallback)
                .setScale(3, RoundingMode.HALF_UP);
    }

    private boolean isWeakTrend(StockMarket stock) {
        if (stock == null || stock.price() == null) return true;
        boolean belowMa20 = stock.ma20() != null && stock.price().compareTo(stock.ma20()) < 0;
        boolean ma20BelowMa60 = stock.ma20() != null && stock.ma60() != null
                && stock.ma20().compareTo(stock.ma60()) < 0;
        boolean macdWeak = stock.macd() != null && stock.macdSignal() != null
                && stock.macd().compareTo(stock.macdSignal()) < 0;
        return belowMa20 || ma20BelowMa60 || macdWeak;
    }

    private BigDecimal stricterStop(BigDecimal technical, BigDecimal ai, BigDecimal current) {
        if (ai == null || ai.signum() <= 0 || ai.compareTo(current) >= 0) return technical;
        return technical.max(ai).setScale(3, RoundingMode.HALF_UP);
    }

    private PositionAnalysis positionAnalysis(StockMarket market, TradePlan plan, PositionStore.Holding holding,
                                              AccountAssetStore.Assets assets, StockPoolItem analysis) {
        modelAuditStore.recordUsage("position-risk", ruleModelVersion, "CALCULATE",
                market == null ? null : market.code());
        BigDecimal modelMaxPercent = modelMaxPositionPercent(market, plan, analysis);
        if (holding == null) {
            if (market != null && market.price() != null && plan != null && plan.buyLow() != null && hasTechnicalData(market)) {
                BigDecimal current = market.price();
                if (current.compareTo(plan.buyLow()) < 0) {
                    boolean holdingSupport = plan.nextSupportPrice() != null && current.compareTo(plan.nextSupportPrice()) >= 0;
                    return new PositionAnalysis(false, null, null, current, null, null, null,
                            holdingSupport ? "未持仓·观察下一承接" : "未持仓·已跌破承接", holdingSupport ? "观察承接" : "暂停接仓", "orange",
                            holdingSupport
                                    ? List.of("现价 " + current + " 低于参考下沿 " + plan.buyLow() + "，下一承接价看 " + plan.nextSupportPrice(),
                                    "在 " + plan.nextSupportPrice() + " 附近缩量止跌、重新获得承接后，再用计划仓位10%—20%试仓",
                                    "承接未确认前不追买；反弹收复 " + plan.buyLow() + " 后再考虑增加机动仓")
                                    : List.of("现价 " + current + " 已跌破下一承接价 " + plan.nextSupportPrice(),
                                    "暂停接仓和补仓，等待止跌、重新站回承接价后再评估",
                                    "不因价格更便宜而摊平成本"), plan.riskWarnings(),
                            assets == null ? null : assets.totalAssets(), BigDecimal.ZERO, modelMaxPercent, allocationAdvice(assets, BigDecimal.ZERO, modelMaxPercent));
                }
                if (current.compareTo(plan.buyHigh()) > 0) {
                    return new PositionAnalysis(false, null, null, current, null, null, null,
                            "未持仓·现价高于参考区", "不追高", "orange",
                            List.of("现价 " + current + " 高于参考上沿 " + plan.buyHigh() + "，不建议追价",
                                    "等待回踩 " + plan.buyHigh() + " 附近并确认承接后再考虑试仓"), plan.riskWarnings(),
                            assets == null ? null : assets.totalAssets(), BigDecimal.ZERO, modelMaxPercent, allocationAdvice(assets, BigDecimal.ZERO, modelMaxPercent));
                }
                return new PositionAnalysis(false, null, null, current, null, null, null,
                        "未持仓·现价在参考区", "小仓试探", "green",
                        List.of("现价 " + current + " 位于参考区 " + plan.buyLow() + " — " + plan.buyHigh() + "",
                                "可先用计划仓位20%—30%试探，确认量价和趋势后再分批增加",
                                "新买入仓位按 A 股 T+1 处理"), plan.riskWarnings(),
                        assets == null ? null : assets.totalAssets(), BigDecimal.ZERO, modelMaxPercent, allocationAdvice(assets, BigDecimal.ZERO, modelMaxPercent));
            }
            return new PositionAnalysis(false, null, null, market == null ? null : market.price(), null, null, null,
                    "未维护持仓", "请录入成本", "blue", List.of("输入买入价和数量后，系统会计算持仓收益和操作建议"), List.of(),
                    assets == null ? null : assets.totalAssets(), BigDecimal.ZERO, modelMaxPercent, allocationAdvice(assets, BigDecimal.ZERO, modelMaxPercent));
        }
        if (market == null || market.price() == null) {
            return new PositionAnalysis(true, holding.avgCost(), holding.quantity(), null, null, null, null,
                    "行情失败", "等待实时价格", "red", List.of("当前无法获取实时价格，暂不判断盈亏和操作"), List.of("联网行情失败"),
                    assets == null ? null : assets.totalAssets(), null, modelMaxPercent, "行情恢复后再计算持仓比例");
        }
        BigDecimal current = market.price();
        BigDecimal marketValue = current.multiply(holding.quantity()).setScale(3, RoundingMode.HALF_UP);
        BigDecimal pnlAmount = current.subtract(holding.avgCost()).multiply(holding.quantity()).setScale(3, RoundingMode.HALF_UP);
        BigDecimal pnlPercent = current.subtract(holding.avgCost()).divide(holding.avgCost(), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
        List<String> suggestions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String action;
        String status;
        String color;
        if (plan == null || !hasTechnicalData(market)) {
            status = pnlAmount.signum() >= 0 ? "浮盈但数据不足" : "浮亏且数据不足";
            action = "暂不决策";
            color = "orange";
            suggestions.add("已有成本和实时价格，但缺少真实技术数据，暂不依据模型加仓或止盈");
        } else if (plan.hardStop() != null && current.compareTo(plan.hardStop()) <= 0) {
            status = "触发硬止损";
            action = "优先止损";
            color = "red";
            suggestions.add("当前价低于硬止损 " + plan.hardStop() + "，不补仓，优先控制单股风险");
        } else if (plan.trailingStop() != null && current.compareTo(plan.trailingStop()) <= 0 && pnlAmount.signum() > 0) {
            status = "盈利回撤";
            action = "保护利润";
            color = "orange";
            suggestions.add("已盈利但跌破移动止损 " + plan.trailingStop() + "，建议分批锁定利润");
        } else if (plan.takeProfit1() != null && current.compareTo(plan.takeProfit1()) >= 0) {
            status = "达到第一止盈";
            action = "分批止盈";
            color = "green";
            suggestions.add("当前价达到第一止盈 " + plan.takeProfit1() + "，建议卖出部分仓位，保留底仓");
        } else if (pnlAmount.signum() < 0 && market.ma20() != null && current.compareTo(market.ma20()) < 0) {
            status = "亏损且趋势偏弱";
            action = "不补仓";
            color = "red";
            suggestions.add("持仓成本之下且跌破 MA20 " + market.ma20().setScale(3, RoundingMode.HALF_UP) + "，不建议摊平成本，等待重新站回均线");
        } else {
            status = pnlAmount.signum() >= 0 ? "持有观察" : "弱势持有观察";
            action = pnlAmount.signum() >= 0 ? "持有" : "谨慎持有";
            color = pnlAmount.signum() >= 0 ? "blue" : "orange";
            suggestions.add("以移动止损 " + plan.trailingStop() + " 作为持仓保护线，跌破后重新评估");
        }
        addCurrentPriceAdvice(suggestions, current, plan);
        if (plan != null) warnings.addAll(plan.riskWarnings());
        return new PositionAnalysis(true, holding.avgCost(), holding.quantity(), current, marketValue, pnlAmount, pnlPercent,
                status, action, color, suggestions, warnings, assets == null ? null : assets.totalAssets(),
                positionPercent(assets, marketValue), modelMaxPercent, allocationAdvice(assets, marketValue, modelMaxPercent));
    }

    private void addCurrentPriceAdvice(List<String> suggestions, BigDecimal current, TradePlan plan) {
        if (plan == null || plan.buyLow() == null || plan.buyHigh() == null) return;
        if (current.compareTo(plan.buyLow()) < 0) {
            if (plan.nextSupportPrice() != null && current.compareTo(plan.nextSupportPrice()) >= 0) {
                suggestions.add("现价 " + current + " 低于参考下沿 " + plan.buyLow() + "，下一承接价看 " + plan.nextSupportPrice() + "：已有仓位以保护为主，不急于补仓");
            } else {
                suggestions.add("现价 " + current + " 已跌破下一承接价 " + plan.nextSupportPrice() + "：暂停补仓，等待止跌确认");
            }
        } else if (current.compareTo(plan.buyHigh()) > 0) {
            suggestions.add("现价 " + current + " 高于参考上沿 " + plan.buyHigh() + "：已有底仓可持有观察，不追高增加仓位");
        } else {
            suggestions.add("现价 " + current + " 位于参考区：若持仓较轻，只考虑小比例机动仓，确认承接后再增加");
        }
    }

    private FactorScores score(StockMarket s, StockContext stockContext) {
        return score(s, stockContext, null);
    }

    private FactorScores score(StockMarket s, StockContext stockContext,
                               AiAnalysisService.AiFactorScores factorScores) {
        ScoringModel model = scoringModel == null ? ScoringModel.defaultModel() : scoringModel;
        if (scoringModelPersisted) {
            modelAuditStore.recordUsage("stock-score", model.version(), "CALCULATE", s == null ? null : s.code());
        }
        factorScores = alignFactorScores(factorScores, model);
        ModuleScore businessModelModule = factorScores == null
                ? ModuleScore.unavailable() : ModuleScore.full(factorScores.businessModel(), model.businessModelWeight());
        ModuleScore industryProspectModule = factorScores == null
                ? ModuleScore.unavailable() : ModuleScore.full(factorScores.industryProspect(), model.industryProspectWeight());
        ModuleScore competitiveAdvantageModule = factorScores == null
                ? ModuleScore.unavailable() : ModuleScore.full(factorScores.competitiveAdvantage(), model.competitiveAdvantageWeight());
        ModuleScore financialQualityModule = factorScores == null
                ? financialQualityScore(s, model.financialQualityWeight())
                : ModuleScore.full(factorScores.financialQuality(), model.financialQualityWeight());
        ModuleScore growthModule = factorScores == null
                ? growthScore(s, model.growthWeight())
                : ModuleScore.full(factorScores.growth(), model.growthWeight());
        ModuleScore valuationModule = factorScores == null
                ? ModuleScore.unavailable() : ModuleScore.full(factorScores.valuation(), model.valuationWeight());
        ModuleScore catalystModule = factorScores == null
                ? catalystScore(stockContext, model.catalystWeight())
                : ModuleScore.full(factorScores.catalyst(), model.catalystWeight());
        ModuleScore riskModule = factorScores == null
                ? riskScore(s, model.riskWeight())
                : ModuleScore.full(factorScores.risk(), model.riskWeight());

        int businessModel = businessModelModule.value();
        int industryProspect = industryProspectModule.value();
        int competitiveAdvantage = competitiveAdvantageModule.value();
        int financialQuality = financialQualityModule.value();
        int growth = growthModule.value();
        int valuation = valuationModule.value();
        int catalyst = catalystModule.value();
        int risk = riskModule.value();
        int total = businessModel + industryProspect + competitiveAdvantage + financialQuality
                + growth + valuation + catalyst + risk;
        int availableMaximum = businessModelModule.maximum() + industryProspectModule.maximum()
                + competitiveAdvantageModule.maximum() + financialQualityModule.maximum()
                + growthModule.maximum() + valuationModule.maximum()
                + catalystModule.maximum() + riskModule.maximum();
        int finalScore = availableMaximum == 0 ? 0 : BigDecimal.valueOf(total)
                .multiply(new BigDecimal("100")).divide(BigDecimal.valueOf(availableMaximum), 0, RoundingMode.HALF_UP).intValue();
        int riskPenalty = 0;
        BigDecimal technicalBase = s.ma20() == null ? s.price() : s.ma20();
        BigDecimal lowTechnical = technicalBase.multiply(new BigDecimal("0.98")).setScale(3, RoundingMode.HALF_UP);
        BigDecimal highTechnical = technicalBase.multiply(new BigDecimal("1.02")).setScale(3, RoundingMode.HALF_UP);
        AiTradeAdvice priceAdvice = stockContext == null || stockContext.priceAdvice() == null
                ? AiTradeAdvice.unavailable() : stockContext.priceAdvice();
        BigDecimal low = blendPrice(lowTechnical, priceAdvice.buyLow(), new BigDecimal("0.35"));
        BigDecimal high = blendPrice(highTechnical, priceAdvice.buyHigh(), new BigDecimal("0.35"));
        if (low != null && high != null && low.compareTo(high) > 0) {
            BigDecimal swap = low;
            low = high;
            high = swap;
        }
        // A buy order cannot be priced above the current executable market price.
        // Keep the displayed range and every downstream buy suggestion consistent
        // with the same rule, including AI-assisted ranges.
        if (s.price() != null) {
            if (low != null) low = low.min(s.price()).setScale(3, RoundingMode.HALF_UP);
            if (high != null) high = high.min(s.price()).setScale(3, RoundingMode.HALF_UP);
            if (low != null && high != null && low.compareTo(high) > 0) low = high;
        }
        List<String> explanations = List.of("商业模式 " + businessModel + "/" + model.businessModelWeight(),
                "行业前景 " + industryProspect + "/" + model.industryProspectWeight(),
                "竞争优势 " + competitiveAdvantage + "/" + model.competitiveAdvantageWeight(),
                "财务质量 " + financialQuality + "/" + model.financialQualityWeight(),
                "成长性 " + growth + "/" + model.growthWeight(),
                "估值 " + valuation + "/" + model.valuationWeight(),
                "催化剂 " + catalyst + "/" + model.catalystWeight(),
                "风险 " + risk + "/" + model.riskWeight(),
                "可用模块得分 " + total + "/" + availableMaximum + "，折算评分 " + finalScore + "/100",
                "数据覆盖 " + availableMaximum + "/100（" + (availableMaximum == 100 ? "完整" : "部分数据，未覆盖模块不扣分") + "）");
        return new FactorScores(businessModel, industryProspect, competitiveAdvantage, financialQuality, growth, valuation,
                catalyst, risk, total, riskPenalty, finalScore, low, high,
                explanations, availableMaximum, BigDecimal.valueOf(availableMaximum));
    }

    private ModuleScore financialQualityScore(StockMarket stock, int modelMaximum) {
        int value = 0;
        int availableMaximum = 0;
        if (stock.roe() != null) {
            value += stock.roe().compareTo(new BigDecimal("15")) >= 0 ? 12
                    : stock.roe().compareTo(new BigDecimal("8")) >= 0 ? 8 : 4;
            availableMaximum += 12;
        }
        if (stock.debtRatio() != null) {
            value += stock.debtRatio().compareTo(new BigDecimal("50")) <= 0 ? 8
                    : stock.debtRatio().compareTo(new BigDecimal("70")) <= 0 ? 5 : 1;
            availableMaximum += 8;
        }
        return scaleAvailableModule(value, availableMaximum, modelMaximum);
    }

    private ModuleScore growthScore(StockMarket stock, int modelMaximum) {
        int value = 0;
        int availableMaximum = 0;
        if (stock.profitGrowth() != null) {
            value += stock.profitGrowth().compareTo(new BigDecimal("20")) >= 0 ? 8
                    : stock.profitGrowth().compareTo(BigDecimal.ZERO) > 0 ? 5 : 1;
            availableMaximum += 8;
        }
        if (stock.revenueGrowth() != null) {
            value += stock.revenueGrowth().compareTo(new BigDecimal("20")) >= 0 ? 7
                    : stock.revenueGrowth().compareTo(BigDecimal.ZERO) > 0 ? 4 : 1;
            availableMaximum += 7;
        }
        return scaleAvailableModule(value, availableMaximum, modelMaximum);
    }

    private ModuleScore catalystScore(StockContext stockContext, int modelMaximum) {
        if (stockContext == null || !stockContext.newsAvailable()) return ModuleScore.unavailable();
        int value = stockContext.positiveNewsCount() > stockContext.negativeNewsCount() ? 5 : 2;
        return scaleAvailableModule(value, 5, modelMaximum);
    }

    private ModuleScore riskScore(StockMarket stock, int modelMaximum) {
        if (stock == null) return ModuleScore.unavailable();
        int value = stock.st() ? 0 : 2;
        int availableMaximum = 2;
        if (stock.debtRatio() != null) {
            value += stock.debtRatio().compareTo(new BigDecimal("70")) <= 0 ? 2 : 0;
            availableMaximum += 2;
        }
        if (stock.rsi14() != null) {
            value += stock.rsi14().compareTo(new BigDecimal("78")) < 0 ? 1 : 0;
            availableMaximum += 1;
        }
        return scaleAvailableModule(value, availableMaximum, modelMaximum);
    }

    private ModuleScore scaleAvailableModule(int value, int availableMaximum, int modelMaximum) {
        if (availableMaximum <= 0 || modelMaximum <= 0) return ModuleScore.unavailable();
        int scaled = BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(modelMaximum))
                .divide(BigDecimal.valueOf(availableMaximum), 0, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO).min(BigDecimal.valueOf(modelMaximum)).intValue();
        return new ModuleScore(scaled, modelMaximum);
    }

    private AiAnalysisService.AiFactorScores alignFactorScores(AiAnalysisService.AiFactorScores scores,
                                                                ScoringModel model) {
        if (scores == null || scores.modelVersion() == model.version()) return scores;
        return new AiAnalysisService.AiFactorScores(
                scaleModelFactor(scores.businessModel(), 15, model.businessModelWeight()),
                scaleModelFactor(scores.industryProspect(), 15, model.industryProspectWeight()),
                scaleModelFactor(scores.competitiveAdvantage(), 15, model.competitiveAdvantageWeight()),
                scaleModelFactor(scores.financialQuality(), 20, model.financialQualityWeight()),
                scaleModelFactor(scores.growth(), 15, model.growthWeight()),
                scaleModelFactor(scores.valuation(), 10, model.valuationWeight()),
                scaleModelFactor(scores.catalyst(), 5, model.catalystWeight()),
                scaleModelFactor(scores.risk(), 5, model.riskWeight()), model.version());
    }

    private int scaleModelFactor(int value, int sourceMaximum, int targetMaximum) {
        if (sourceMaximum <= 0 || targetMaximum <= 0) return 0;
        int bounded = Math.max(0, Math.min(sourceMaximum, value));
        return BigDecimal.valueOf(bounded).multiply(BigDecimal.valueOf(targetMaximum))
                .divide(BigDecimal.valueOf(sourceMaximum), 0, RoundingMode.HALF_UP).intValue();
    }

    private FactorScores emptyScore() {
        return new FactorScores(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), 0, BigDecimal.ZERO);
    }

    private boolean hasFullAnalysisData(StockMarket stock) {
        return "FULL".equals(stock.dataStatus());
    }

    private boolean hasAiValueModel(StockPoolItem item) {
        if (item == null || item.scores() == null || item.scores().explanations() == null) return false;
        return item.scores().availableMaximum() >= 90
                && item.scores().explanations().stream().anyMatch(explanation -> explanation.startsWith("商业模式 "));
    }

    private boolean hasTechnicalData(StockMarket stock) {
        return hasFullAnalysisData(stock) || "TECHNICAL_ONLY".equals(stock.dataStatus());
    }

    private List<String> recommendationReasons(StockMarket stock, HardFilterResult filter, FactorScores scores, String action) {
        if (!"候选".equals(action)) {
            if (!filter.reasons().isEmpty()) return filter.reasons();
            return List.of("最终评分 " + scores.finalScore() + " 分，低于推荐阈值 " + config.minScore() + " 分");
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("硬过滤通过");
        if ("TECHNICAL_ONLY".equals(stock.dataStatus())) {
            reasons.add("技术面候选：基于已获取的技术数据折算，覆盖率 " + scores.coveragePercent() + "%；基本面/资金面暂不扣分");
        }
        reasons.addAll(scores.explanations());
        if (stock.majorEventType() != null) reasons.add(stock.majorEventType() + "：" + stock.majorEventTitle());
        return reasons;
    }

    private int recommendationThreshold(StockMarket stock) {
        // finalScore 已按可用因子动态折算到 100 分；缺少财务/资金数据不再被当成 0 分扣除。
        return config.minScore();
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    public static StrategyConfig defaultConfig() {
        return new StrategyConfig("价值质量 100 分模型", 70, new BigDecimal("0.01"), new BigDecimal("500"), new BigDecimal("100000000"),
                60, BigDecimal.ONE, new BigDecimal("0.25"), new BigDecimal("0.80"),
                new BigDecimal("0.08"), new BigDecimal("0.12"), 60);
    }
}
