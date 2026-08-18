package com.dolphin.stock.web;

import com.dolphin.stock.model.StockAnalysisModels.*;
import com.dolphin.stock.service.AiProviderConfigStore;
import com.dolphin.stock.service.MarketDataSourceStore;
import com.dolphin.stock.service.NewsIngestionService;
import com.dolphin.stock.service.RecommendationScheduler;
import com.dolphin.stock.service.StockPoolService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class StockPoolController {
    private final StockPoolService service;
    private final NewsIngestionService newsIngestionService;
    private final AiProviderConfigStore aiProviderConfigStore;
    private final MarketDataSourceStore marketDataSourceStore;
    private final RecommendationScheduler recommendationScheduler;
    public StockPoolController(StockPoolService service, NewsIngestionService newsIngestionService,
                               AiProviderConfigStore aiProviderConfigStore,
                               MarketDataSourceStore marketDataSourceStore,
                               RecommendationScheduler recommendationScheduler) {
        this.service = service;
        this.newsIngestionService = newsIngestionService;
        this.aiProviderConfigStore = aiProviderConfigStore;
        this.marketDataSourceStore = marketDataSourceStore;
        this.recommendationScheduler = recommendationScheduler;
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("status", "UP", "service", "dolphin-stock-analysis"); }

    @GetMapping("/system/initialization")
    public Map<String, Object> initializationStatus() {
        LocalDateTime initializedAt = recommendationScheduler.lastInitializationAt();
        return Map.of("initializedAt", initializedAt == null ? "" : initializedAt);
    }

    @PostMapping("/system/initialization")
    public Map<String, Object> initialize(@RequestParam(required = false) String asOf) {
        return recommendationScheduler.initializeNow(asOf == null ? null : LocalDate.parse(asOf));
    }

    @GetMapping("/stock-pool")
    public StockPoolResponse pool(@RequestParam(required = false) String asOf) {
        return service.recommendations(asOf == null ? null : LocalDate.parse(asOf), null, null);
    }

    @GetMapping("/recommendations")
    public StockPoolResponse recommendations(@RequestParam(required = false) String asOf,
                                             @RequestParam(required = false) BigDecimal minPrice,
                                             @RequestParam(required = false) BigDecimal maxPrice) {
        return service.recommendations(asOf == null ? null : LocalDate.parse(asOf), minPrice, maxPrice);
    }

    @PostMapping("/recommendations/refresh")
    public StockPoolResponse refreshRecommendations(@RequestParam(required = false) String asOf,
                                                    @RequestParam(required = false) BigDecimal minPrice,
                                                    @RequestParam(required = false) BigDecimal maxPrice) {
        return service.refreshRecommendations(asOf == null ? null : LocalDate.parse(asOf), minPrice,
                maxPrice, "ON_DEMAND", "手动刷新");
    }

    @GetMapping("/managed-pool")
    public ManagedPoolResponse managedPool(@RequestParam(required = false) String asOf) {
        return service.managedPool(asOf == null ? null : LocalDate.parse(asOf));
    }

    @PostMapping("/managed-pool/company-profiles/refresh")
    public Map<String, Object> refreshCompanyProfiles(@RequestParam(required = false) String asOf) {
        return service.refreshCompanyProfilesNow(asOf == null ? null : LocalDate.parse(asOf));
    }

    @PostMapping("/managed-pool/models/{modelKey}/regenerate")
    public Map<String, Object> regenerateModel(@PathVariable String modelKey,
                                                @RequestParam(required = false) String asOf) {
        return service.regenerateModelNow(asOf == null ? null : LocalDate.parse(asOf), modelKey);
    }

    @PostMapping("/managed-pool/models/{modelKey}/data/refresh")
    public Map<String, Object> refreshModelData(@PathVariable String modelKey,
                                                 @RequestParam(required = false) String asOf) {
        return service.refreshModelNow(asOf == null ? null : LocalDate.parse(asOf), modelKey);
    }

    @PostMapping("/managed-pool/models/analysis/refresh")
    public Map<String, Object> refreshTradeAnalysisModel(@RequestParam(required = false) String asOf) {
        return service.refreshTradeModelNow(asOf == null ? null : LocalDate.parse(asOf), "ANALYSIS");
    }

    @PostMapping("/managed-pool/models/success-rate/refresh")
    public Map<String, Object> refreshTradeSuccessRateModel(@RequestParam(required = false) String asOf) {
        return service.refreshTradeModelNow(asOf == null ? null : LocalDate.parse(asOf), "SUCCESS_RATE");
    }

    @PostMapping("/managed-pool/news/refresh")
    public Map<String, Object> refreshNews() {
        return newsIngestionService.refreshNow();
    }

    @PostMapping("/managed-pool/accuracy/calculate")
    public Map<String, Object> calculateAccuracies(@RequestParam(required = false) String asOf) {
        return service.calculateAccuracies(asOf == null ? null : LocalDate.parse(asOf));
    }

    /** 只读查询接口：前端提交刷新任务后会通过 GET 轮询缓存结果。 */
    @RequestMapping(value = "/managed-pool/portfolio-analysis", method = {RequestMethod.GET, RequestMethod.POST})
    public PortfolioAnalysis analyzePortfolio(@RequestParam(required = false) String asOf) {
        return service.analyzePortfolio(asOf == null ? null : LocalDate.parse(asOf));
    }

    /** 用户明确点击时立即分析；普通页面读取仍使用上面的缓存接口。 */
    @PostMapping("/managed-pool/portfolio-analysis/refresh")
    public PortfolioAnalysis refreshPortfolioAnalysis(@RequestParam(required = false) String asOf) {
        return service.refreshPortfolioAnalysis(asOf == null ? null : LocalDate.parse(asOf));
    }

    @GetMapping("/managed-pool/{code}/history")
    public java.util.List<com.dolphin.stock.service.TradeExecutionStore.Trade> tradeHistory(@PathVariable String code) {
        return service.tradeHistory(code);
    }

    @GetMapping("/managed-pool/{code}/position")
    public PositionRecord positionRecord(@PathVariable String code) {
        return service.positionRecord(code);
    }

    @PostMapping("/managed-pool")
    public ManagedPoolResponse addToPool(@RequestBody PoolAddRequest request,
                                         @RequestParam(required = false) String asOf) {
        return service.addToPool(request, asOf == null ? null : LocalDate.parse(asOf));
    }

    @DeleteMapping("/managed-pool/{code}")
    public ManagedPoolResponse removeFromPool(@PathVariable String code,
                                              @RequestParam(required = false) String asOf) {
        return service.removeFromPool(code, asOf == null ? null : LocalDate.parse(asOf));
    }

    @PutMapping("/managed-pool/{code}/position")
    public PositionAnalysis updatePosition(@PathVariable String code,
                                            @RequestBody PositionRequest request,
                                            @RequestParam(required = false) String asOf) {
        return service.updatePosition(code, request, asOf == null ? null : LocalDate.parse(asOf));
    }

    @DeleteMapping("/managed-pool/{code}/position")
    public PositionAnalysis clearPosition(@PathVariable String code,
                                           @RequestParam(required = false) String asOf) {
        return service.clearPosition(code, asOf == null ? null : LocalDate.parse(asOf));
    }

    @PutMapping("/managed-pool/{code}/planned-order")
    public PlannedOrderAnalysis updatePlannedOrder(@PathVariable String code,
                                                    @RequestBody PlannedOrderRequest request,
                                                    @RequestParam(required = false) String asOf) {
        return service.updatePlannedOrder(code, request, asOf == null ? null : LocalDate.parse(asOf));
    }

    @PostMapping("/managed-pool/{code}/planned-order/analyze")
    public PlannedOrderAnalysis analyzePlannedOrder(@PathVariable String code,
                                                     @RequestBody PlannedOrderRequest request,
                                                     @RequestParam(required = false) String asOf) {
        return service.analyzePlannedOrder(code, request, asOf == null ? null : LocalDate.parse(asOf));
    }

    @DeleteMapping("/managed-pool/{code}/planned-order")
    public PlannedOrderAnalysis clearPlannedOrder(@PathVariable String code,
                                                    @RequestParam(required = false) String asOf) {
        return service.clearPlannedOrder(code, asOf == null ? null : LocalDate.parse(asOf));
    }

    @PostMapping("/managed-pool/{code}/planned-order/confirm")
    public TradeExecutionSummary confirmPlannedOrder(@PathVariable String code,
                                                      @RequestBody(required = false) PlannedOrderRequest request,
                                                      @RequestParam(required = false) String asOf) {
        return service.confirmPlannedOrder(code, request, asOf == null ? null : LocalDate.parse(asOf));
    }

    @GetMapping("/strategy-config")
    public StrategyConfig config() { return service.config(); }

    @GetMapping("/scoring-model")
    public ScoringModel scoringModel() { return service.scoringModel(); }

    @GetMapping("/managed-pool/models/status")
    public java.util.List<ModelStatus> modelStatuses() { return service.modelStatuses(); }

    @PutMapping("/strategy-config")
    public StrategyConfig update(@RequestBody StrategyConfig config) { return service.updateConfig(config); }

    @GetMapping("/account/assets")
    public AccountAssetSummary accountAssets() { return service.accountAssets(); }

    @PutMapping("/account/assets")
    public AccountAssetSummary updateAccountAssets(@RequestBody AccountAssetRequest request) {
        return service.updateAccountAssets(request);
    }

    @GetMapping("/ai/config")
    public AiProviderConfig aiConfig() { return aiProviderConfigStore.load(); }

    @PutMapping("/ai/config")
    public AiProviderConfig updateAiConfig(@RequestBody AiProviderUpdateRequest request) {
        return aiProviderConfigStore.save(request);
    }

    @GetMapping("/market-data/sources")
    public java.util.List<MarketDataSourceConfig> marketDataSources() { return marketDataSourceStore.load(); }

    @PutMapping("/market-data/sources")
    public java.util.List<MarketDataSourceConfig> updateMarketDataSources(
            @RequestBody java.util.List<MarketDataSourceConfigRequest> requests) {
        return marketDataSourceStore.save(requests);
    }

    @GetMapping("/position-sizing")
    public PositionSizing sizing(@RequestParam(defaultValue = "NORMAL") String regime) { return service.positionSizing(regime); }

    @GetMapping("/sell-decision")
    public SellDecision sell(@RequestParam String code, @RequestParam BigDecimal entryPrice,
                             @RequestParam BigDecimal highestPrice, @RequestParam BigDecimal currentPrice,
                             @RequestParam BigDecimal ma20, @RequestParam BigDecimal ma60,
                             @RequestParam int rsi, @RequestParam(defaultValue = "1") int macdTrend) {
        return service.sellDecision(code, entryPrice, highestPrice, currentPrice, ma20, ma60, rsi, macdTrend);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> serviceUnavailable(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", ex.getMessage() == null ? "实时行情更新失败" : ex.getMessage()));
    }
}
