package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.AiTradeAdvice;
import com.dolphin.stock.model.StockAnalysisModels.AiCompanyAnalysis;
import com.dolphin.stock.model.StockAnalysisModels.PortfolioAnalysis;
import com.dolphin.stock.model.StockAnalysisModels.ScoringModel;
import com.dolphin.stock.model.StockAnalysisModels.StockMarket;
import com.dolphin.stock.model.StockAnalysisModels.TradeAnalysisModel;
import com.dolphin.stock.model.StockAnalysisModels.TradeSuccessRateModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);
    private static final String PROMPT_VERSION = "news-sentiment-json-v1";
    private static final String PRICE_PROMPT_VERSION = "price-advice-json-v1";
    private static final String COMPANY_PROMPT_VERSION = "company-outlook-json-v1";
    private static final int MAX_NEWS_PER_REFRESH = 30;
    private static final long AI_FAILURE_COOLDOWN_MILLIS = 300_000L;
    private static final int COMPANY_ANALYSIS_MAX_ATTEMPTS = 3;
    private static final Duration COMPANY_ANALYSIS_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration PORTFOLIO_ANALYSIS_TIMEOUT = Duration.ofSeconds(90);
    private static final String COMPANY_BATCH_PROMPT_VERSION = "company-outlook-batch-json-v1";
    private static final BigDecimal FORCED_MODEL_CONFIDENCE = new BigDecimal("0.81");
    private static final String FALLBACK_PROVIDER = "LOCAL_RULE_FALLBACK";
    private static final String FALLBACK_MODEL = "technical-rule-v1";
    private static final BigDecimal FALLBACK_CONFIDENCE = new BigDecimal("0.35");
    private final ObjectMapper objectMapper;
    private final AiProviderConfigStore configStore;
    private final NewsHotspotStore newsStore;
    private final AiAnalysisRecordStore recordStore;
    private final CompanyProfileStore companyProfileStore;
    private final ScoringModelStore scoringModelStore;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, AiTradeAdvice> priceAdviceCache = new ConcurrentHashMap<>();
    private volatile long aiBlockedUntilMillis;

    public AiAnalysisService(ObjectMapper objectMapper, AiProviderConfigStore configStore,
                             NewsHotspotStore newsStore, AiAnalysisRecordStore recordStore,
                             CompanyProfileStore companyProfileStore, ScoringModelStore scoringModelStore) {
        this.objectMapper = objectMapper;
        this.configStore = configStore;
        this.newsStore = newsStore;
        this.recordStore = recordStore;
        this.companyProfileStore = companyProfileStore;
        this.scoringModelStore = scoringModelStore;
    }

    public List<NewsHotspotStore.News> enrichNews(List<NewsHotspotStore.News> source) {
        if (source == null || source.isEmpty()) return List.of();
        var access = configStore.access();
        List<NewsHotspotStore.News> result = new ArrayList<>(source);
        int analyzed = 0;
        for (int i = 0; i < result.size() && analyzed < MAX_NEWS_PER_REFRESH; i++) {
            NewsHotspotStore.News news = result.get(i);
            if (news == null || news.aiSummary() != null && !news.aiSummary().isBlank()) continue;
            if ((news.title() == null || news.title().isBlank()) && (news.content() == null || news.content().isBlank())) continue;
            AiAnalysisRecordStore.NewsAiResult ai;
            String provider = FALLBACK_PROVIDER;
            String model = FALLBACK_MODEL;
            try {
                if (access.isEmpty()) throw new IllegalStateException("AI未配置");
                if (!aiCallsAvailable()) throw new IllegalStateException("AI处于本轮熔断期");
                provider = access.get().provider();
                model = access.get().model();
                ai = analyzeNews(news, access.get());
            } catch (Exception ex) {
                log.warn("AI新闻分析失败，新闻ID={}，使用规则兜底：{}", news.id(), ex.getMessage());
                blockAiForRefresh(ex);
                ai = fallbackNewsAnalysis(news);
            }
            newsStore.saveAiResult(news.id(), ai.eventType(), ai.sentiment(), ai.summary());
            recordStore.saveNews(news.id(), ai, inputOf(news), provider, model, PROMPT_VERSION);
            result.set(i, new NewsHotspotStore.News(news.id(), news.code(), news.title(), news.content(), ai.eventType(),
                    ai.sentiment(), ai.summary(), news.publishedAt(), news.url()));
            analyzed++;
        }
        return result;
    }

    public PortfolioAnalysis analyzePortfolio(String portfolioInput) {
        var access = configStore.access();
        if (access.isEmpty()) return fallbackPortfolioAnalysis("AI接入不可用，请检查数据库中的AI配置和API Key");
        try {
            String system = "你是股票组合复盘助手。请结合给定的大盘环境、全部实际持仓、已确认交易和新闻缓存，分析过去决策的成功点、失误点和原因，并给出下一步建议。只能基于输入事实，不得编造数据，不得直接替用户下单。必须返回JSON。";
            String user = "请严格返回JSON，字段必须包含："
                    + "marketOverview（大盘概况字符串）, portfolioOverview（组合概况字符串）,"
                    + "successPoints（字符串数组）, mistakePoints（字符串数组）, causes（字符串数组）,"
                    + "nextSteps（字符串数组）, riskWarnings（字符串数组）, confidence（0到1数字）。"
                    + "每个数组最多5条，每条不超过120字。\n输入资料：\n" + portfolioInput;
            MapPayload payload = new MapPayload(access.get().model(), List.of(
                    new Message("system", system), new Message("user", user)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(chatEndpoint(access.get().baseUrl())))
                    .timeout(PORTFOLIO_ANALYSIS_TIMEOUT)
                    .header("Authorization", "Bearer " + access.get().apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "DolphinStock/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            logAiRequestConfig(access.get());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw aiHttpError(response);
            JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) throw new IllegalStateException("AI返回缺少组合分析内容");
            JsonNode json = objectMapper.readTree(stripFence(content.asText()));
            return new PortfolioAnalysis(true, LocalDateTime.now(),
                    text(json, "marketOverview", "AI未提供大盘概况"),
                    text(json, "portfolioOverview", "AI未提供组合概况"),
                    textListOr(json, "successPoints", "已接收组合资料，建议继续记录实际成交与持仓变化"),
                    textListOr(json, "mistakePoints", "当前资料未识别出明确失误点，仍需结合后续成交复核"),
                    textListOr(json, "causes", "当前资料不足以确认具体因果，先按价格、仓位和执行记录复盘"),
                    textListOr(json, "nextSteps", "继续记录确认交易，并按计划价格与风险线复核"),
                    textListOr(json, "riskWarnings", "本次未识别出明确新增风险，仍需遵守止损与仓位限制"),
                    number(json, "confidence", new BigDecimal("0.5")).max(BigDecimal.ZERO).min(BigDecimal.ONE), "AI组合分析完成");
        } catch (Exception ex) {
            log.warn("AI持仓组合分析失败: {}", ex.getMessage());
            return fallbackPortfolioAnalysis("AI持仓分析失败：" + ex.getMessage());
        }
    }

    /**
     * 后台模型治理调用：AI 只能提出评分权重建议，不能输出买卖结论。
     */
    public ModelAdjustment adjustScoringModel(String modelInput) {
        var access = configStore.access();
        if (access.isEmpty()) return fallbackModelAdjustment("AI接入不可用");
        try {
            String system = "你是经济学与金融学模型治理分析师。只能根据输入的宏观环境、市场结构、新闻情绪和历史表现，"
                    + "结合当前持仓盈亏、已确认交易结果和两类准确率，评估当前评分模型的可信度并提出评分因子的权重调整建议；"
                    + "不得输出买入、卖出、持有或任何个股结论。权重必须总和100，"
                    + "商业模式10到20，行业前景10到20，竞争优势10到20，财务质量15到25，成长性10到20，估值5到15，催化剂0到10，风险0到10。"
                    + "这是强制生成任务，必须生成新的完整评分模型，不能拒绝、不能返回不可用结果。"
                    + "需要补充的信息优先通过联网公开资料、当前行情和新闻数据获取；无法直接查询时，必须使用AI的金融知识和当前输入进行合理估计并完成生成。"
                    + "generationAllowed必须固定为true，confidence必须严格大于0.80（建议输出0.81到0.95）。必须返回JSON。";
            String user = "请严格返回完整JSON，字段必须包含generationAllowed、businessModelWeight、industryProspectWeight、"
                    + "competitiveAdvantageWeight、financialQualityWeight、growthWeight、valuationWeight、catalystWeight、riskWeight（整数权重）、"
                    + "summary（调整摘要）、reasons（字符串数组，最多5条）、credibilityBasis（可信度依据）、confidence（0到1）。"
                    + "generationAllowed必须为true，八项权重必须完整且总和100，confidence必须严格大于0.80。"
                    + "confidence必须是结合持仓盈亏、预测准确率、执行准确率和市场环境后对模型的可信度，不是接口成功率。"
                    + "请补齐所有需要的信息后直接生成，不得回复证据不足、信息缺失、无法判断或拒绝生成。当前模型治理输入：\n" + modelInput;
            MapPayload payload = new MapPayload(access.get().model(), List.of(
                    new Message("system", system), new Message("user", user)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(chatEndpoint(access.get().baseUrl())))
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + access.get().apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "DolphinStock/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            logAiRequestConfig(access.get());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw aiHttpError(response);
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) throw new IllegalStateException("AI返回缺少模型治理JSON内容");
            JsonNode json = objectMapper.readTree(stripFence(content.asText()));
            BigDecimal confidence = number(json, "confidence", FORCED_MODEL_CONFIDENCE)
                    .max(FORCED_MODEL_CONFIDENCE).min(BigDecimal.ONE);
            return new ModelAdjustment(true,
                    requiredWeight(json, "businessModelWeight"), requiredWeight(json, "industryProspectWeight"),
                    requiredWeight(json, "competitiveAdvantageWeight"), requiredWeight(json, "financialQualityWeight"),
                    requiredWeight(json, "growthWeight"), requiredWeight(json, "valuationWeight"),
                    requiredWeight(json, "catalystWeight"), requiredWeight(json, "riskWeight"),
                    text(json, "summary", "AI未提供调整摘要"),
                    textList(json, "reasons"), text(json, "credibilityBasis", "AI未提供可信度依据"),
                    confidence);
        } catch (Exception ex) {
            blockAiForRefresh(ex);
            log.warn("AI评分模型调整失败：{}", ex.getMessage());
            return fallbackModelAdjustment(ex.getMessage());
        }
    }

    private int requiredWeight(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) throw new IllegalStateException("AI模型权重不是整数：" + field);
        return value.asInt();
    }

    public AiTradeAdvice adviseStock(StockMarket stock, List<NewsHotspotStore.News> news) {
        if (stock == null || stock.price() == null) return fallbackTradeAdvice(stock, "缺少真实价格");
        if (!aiCallsAvailable()) return fallbackTradeAdvice(stock, "AI处于本轮熔断期");
        var access = configStore.access();
        if (access.isEmpty()) return fallbackTradeAdvice(stock, "AI未配置");
        String cacheKey = stock.code() + "|" + LocalDate.now() + "|" + stock.ma20() + "|" + stock.ma60()
                + "|" + stock.high20() + "|" + stock.high60();
        AiTradeAdvice cached = priceAdviceCache.get(cacheKey);
        if (cached != null) return ensureTradeAdvice(stock, cached);
        try {
            String input = stockAdviceInput(stock, news);
            String system = "你是股票研究系统的价格区间辅助分析器。只能根据给定的真实行情和新闻摘要给出价格参考，不能输出确定性的BUY、SELL、买入数量或自动交易指令。Java程序会再次校验并合并你的意见。必须返回JSON。";
            String user = "请分析以下A股股票，严格返回 JSON："
                    + "{buyLow:number,buyHigh:number,nextSupportPrice:number,takeProfit1:number,takeProfit2:number,hardStop:number,trailingStop:number,bandAdvice:string,suggestions:string[],confidence:number}。"
                    + "所有价格必须是正数且保留合理小数；如果数据不足，相关字段返回 null。"
                    + "\n股票数据：" + input;
            MapPayload payload = new MapPayload(access.get().model(), List.of(
                    new Message("system", system), new Message("user", user)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(chatEndpoint(access.get().baseUrl())))
                    .timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + access.get().apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "DolphinStock/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            logAiRequestConfig(access.get());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw aiHttpError(response);
            JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) throw new IllegalStateException("AI返回缺少JSON内容");
            AiTradeAdvice advice = ensureTradeAdvice(stock,
                    parseTradeAdvice(objectMapper.readTree(stripFence(content.asText())), stock.price()));
            if (advice.available()) {
                recordStore.saveStockAdvice(stock.code(), advice, input, access.get().provider(), access.get().model(), PRICE_PROMPT_VERSION);
            }
            priceAdviceCache.put(cacheKey, advice);
            return advice;
        } catch (Exception ex) {
            log.warn("AI价格建议失败，股票={}: {}", stock.code(), ex.getMessage());
            blockAiForRefresh(ex);
            return fallbackTradeAdvice(stock, ex.getMessage());
        }
    }

    public AiCompanyAnalysis analyzeCompany(StockMarket stock, List<NewsHotspotStore.News> news) {
        if (stock == null || stock.price() == null) return fallbackCompanyAnalysis(stock, "缺少真实股票价格");
        var access = configStore.access();
        if (access.isEmpty()) return fallbackCompanyAnalysis(stock, "API Key 未配置或 AI 未启用");
        String input = companyInput(stock, news);
        String system = "你是股票研究系统的公司基本信息分析器。只根据给定的股票资料、行情和新闻公告摘要进行归纳；资料不足时必须明确说无法判断，不得编造主营业务或事实。不得输出 BUY、SELL、买入数量或自动交易指令。";
        String user = "请严格返回 JSON：{businessDescription:string,outlook:string,futureTrend:上行倾向/震荡/下行风险/无法判断,risk:string,confidence:number}。"
                + "请分别说明公司主要做什么、业务前景，以及基于当前资料对未来价格趋势的倾向；这只是辅助分析，不代表必然上涨。\n公司资料：" + input;
        MapPayload payload = new MapPayload(access.get().model(), List.of(
                new Message("system", system), new Message("user", user)));
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= COMPANY_ANALYSIS_MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(chatEndpoint(access.get().baseUrl())))
                        .timeout(COMPANY_ANALYSIS_TIMEOUT)
                        .header("Authorization", "Bearer " + access.get().apiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", "DolphinStock/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                        .build();
                logAiRequestConfig(access.get());
                log.info(objectMapper.writeValueAsString(payload));
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) throw aiHttpError(response);
                JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
                if (!content.isTextual()) throw new IllegalStateException("AI返回缺少JSON内容");
                JsonNode json = objectMapper.readTree(stripFence(content.asText()));
                log.info(json.toString());
                String trend = text(json, "futureTrend", "无法判断");
                if (!List.of("上行倾向", "震荡", "下行风险", "无法判断").contains(trend)) trend = "无法判断";
                AiCompanyAnalysis result = parseCompanyAnalysis(json);
                recordStore.saveCompanyAnalysis(stock.code(), result, input, access.get().provider(), access.get().model(), COMPANY_PROMPT_VERSION);
                companyProfileStore.save(stock.code(), LocalDate.now(), result, access.get().provider(), access.get().model());
                return result;
            } catch (Exception ex) {
                lastFailure = ex;
                log.warn("AI公司分析失败，股票={}，第 {}/{} 次：{}", stock.code(), attempt, COMPANY_ANALYSIS_MAX_ATTEMPTS, ex.getMessage());
                if (attempt < COMPANY_ANALYSIS_MAX_ATTEMPTS) {
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("AI公司分析重试被中断", interrupted);
                    }
                }
            }
        }
        return fallbackCompanyAnalysis(stock, "AI公司分析失败：" + (lastFailure == null ? "未知错误" : lastFailure.getMessage()));
    }

    /**
     * Refresh only the slow-changing company profile fields. This endpoint is
     * intentionally separate from the realtime score/price batch so daily pool
     * refreshes do not ask the model to rediscover the same business facts.
     */
    public Map<String, AiCompanyAnalysis> analyzeCompanyProfiles(List<StockMarket> stocks,
                                                                  List<NewsHotspotStore.News> news,
                                                                  LocalDate analyzedDate) {
        if (stocks == null || stocks.isEmpty()) return Map.of();
        Map<String, AiCompanyAnalysis> fallback = fallbackCompanyProfiles(stocks, "AI公司资料批量分析不可用");
        if (stocks.stream().anyMatch(stock -> stock == null || stock.price() == null
                || stock.code() == null || stock.code().isBlank())) {
            return fallback;
        }
        var access = configStore.access();
        if (access.isEmpty()) return fallback;
        String system = "你是股票研究系统的公司资料整理器。只根据给定资料归纳，不得编造主营业务或事实，不得输出交易指令。必须为每家公司返回一条结果，stockCode 必须原样保留。";
        String user = "请一次性整理下面全部公司的公司资料，严格返回 JSON："
                + "{companies:[{stockCode:string,businessDescription:string,outlook:string,futureTrend:上行倾向/震荡/下行风险/无法判断,risk:string,confidence:number}]}。"
                + "companies 数量必须等于输入数量，不能遗漏、合并或新增公司；confidence 范围为 0 到 1。\n待整理公司：\n"
                + stocks.stream().map(stock -> "---\n" + companyInput(stock, news)).reduce((left, right) -> left + "\n" + right).orElse("");
        MapPayload payload = new MapPayload(access.get().model(), List.of(
                new Message("system", system), new Message("user", user)));
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= COMPANY_ANALYSIS_MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(chatEndpoint(access.get().baseUrl())))
                        .timeout(COMPANY_ANALYSIS_TIMEOUT)
                        .header("Authorization", "Bearer " + access.get().apiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", "DolphinStock/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                        .build();
                logAiRequestConfig(access.get());
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) throw aiHttpError(response);
                JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
                if (!content.isTextual()) throw new IllegalStateException("AI返回缺少公司资料JSON内容");
                JsonNode rows = objectMapper.readTree(stripFence(content.asText())).path("companies");
                if (!rows.isArray() || rows.size() != stocks.size()) {
                    throw new IllegalStateException("AI公司资料返回数量不完整，期望=" + stocks.size()
                            + "，实际=" + (rows.isArray() ? rows.size() : 0));
                }
                Map<String, StockMarket> stockByCode = stocks.stream()
                        .collect(java.util.stream.Collectors.toMap(StockMarket::code, stock -> stock,
                                (left, right) -> left, LinkedHashMap::new));
                Map<String, AiCompanyAnalysis> result = new LinkedHashMap<>();
                for (JsonNode row : rows) {
                    String code = text(row, "stockCode", "").trim();
                    if (!stockByCode.containsKey(code) || result.containsKey(code)) {
                        throw new IllegalStateException("AI公司资料返回了未知或重复股票代码=" + code);
                    }
                    result.put(code, parseCompanyAnalysis(row));
                }
                if (result.size() != stocks.size()) throw new IllegalStateException("AI公司资料返回缺少股票结果");
                LocalDate date = analyzedDate == null ? LocalDate.now() : analyzedDate;
                result.forEach((code, analysis) -> {
                    StockMarket stock = stockByCode.get(code);
                    companyProfileStore.save(code, date, analysis, access.get().provider(), access.get().model());
                    recordStore.saveCompanyAnalysis(code, analysis, companyInput(stock, news),
                            access.get().provider(), access.get().model(), COMPANY_BATCH_PROMPT_VERSION + "-profile");
                });
                return result;
            } catch (Exception ex) {
                lastFailure = ex;
                log.warn("AI公司资料批量分析失败，第 {}/{} 次：{}", attempt, COMPANY_ANALYSIS_MAX_ATTEMPTS, ex.getMessage());
                if (attempt < COMPANY_ANALYSIS_MAX_ATTEMPTS) {
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("AI公司资料分析重试被中断", interrupted);
                    }
                }
            }
        }
        return fallbackCompanyProfiles(stocks, "AI公司资料批量分析失败："
                + (lastFailure == null ? "未知错误" : lastFailure.getMessage()));
    }

    /** Calculate only today's scores and price levels using cached company facts. */
    public BatchRealtimeAnalysis analyzeRealtimeCompanies(List<StockMarket> stocks,
                                                           Map<String, AiCompanyAnalysis> profiles,
                                                           List<NewsHotspotStore.News> news) {
        if (stocks == null || stocks.isEmpty()) return new BatchRealtimeAnalysis(Map.of(), Map.of(), Map.of(), Map.of());
        BatchRealtimeAnalysis fallback = fallbackRealtimeAnalysis(stocks, news);
        var access = configStore.access();
        if (access.isEmpty()) return fallback;
        ScoringModel scoringModel = activeScoringModel();
        String system = "你是股票研究系统的实时评分器。公司主营和前景已经由缓存资料提供；只根据缓存资料、当前行情、技术指标和新闻摘要计算今日评分与价格参考，不要重新搜索或编造公司资料。不得输出交易指令。必须为每家公司返回一条结果，stockCode 原样保留。";
        String user = "请一次性计算下面全部公司的今日评分、价格参考、预计交易成功率模型和计划分析模型，严格返回 JSON："
                + "{companies:[{stockCode:string,scores:" + scoreSchema(scoringModel) + ",priceAdvice:{buyLow:number,buyHigh:number,nextSupportPrice:number,takeProfit1:number,takeProfit2:number,hardStop:number,trailingStop:number,bandAdvice:string,suggestions:string[],confidence:number},"
                + "successRateModel:{baseProbability:0-100,confidenceWeight:0-40,aiPriceMatchBonus:0-30,aiPriceMismatchPenalty:0-30,technicalMatchBonus:0-30,technicalMismatchPenalty:0-30,hardRiskPenalty:0-50,warningPenalty:0-10,minProbability:0-100,maxProbability:0-100,confidence:0-1,summary:string,reasons:string[]},"
                + "analysisModel:{summary:string,buySuggestions:string[],sellSuggestions:string[],riskWarnings:string[],confidence:0-1}}]}。"
                + "companies 数量必须等于输入数量，不能遗漏、合并或新增公司。\n待计算公司：\n"
                + stocks.stream().map(stock -> "---\n" + realtimeInput(stock, profiles == null ? null : profiles.get(stock.code()), news))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        MapPayload payload = new MapPayload(access.get().model(), List.of(
                new Message("system", system), new Message("user", user)));
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= COMPANY_ANALYSIS_MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(chatEndpoint(access.get().baseUrl())))
                        .timeout(COMPANY_ANALYSIS_TIMEOUT)
                        .header("Authorization", "Bearer " + access.get().apiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", "DolphinStock/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                        .build();
                logAiRequestConfig(access.get());
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) throw aiHttpError(response);
                JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
                if (!content.isTextual()) throw new IllegalStateException("AI返回缺少实时评分JSON内容");
                JsonNode rows = objectMapper.readTree(stripFence(content.asText())).path("companies");
                if (!rows.isArray() || rows.size() != stocks.size()) {
                    throw new IllegalStateException("AI实时评分返回数量不完整，期望=" + stocks.size()
                            + "，实际=" + (rows.isArray() ? rows.size() : 0));
                }
                Map<String, StockMarket> stockByCode = stocks.stream()
                        .collect(java.util.stream.Collectors.toMap(StockMarket::code, stock -> stock,
                                (left, right) -> left, LinkedHashMap::new));
                Map<String, AiTradeAdvice> prices = new LinkedHashMap<>();
                Map<String, AiFactorScores> scores = new LinkedHashMap<>();
                Map<String, TradeSuccessRateModel> successRateModels = new LinkedHashMap<>();
                Map<String, TradeAnalysisModel> analysisModels = new LinkedHashMap<>();
                for (JsonNode row : rows) {
                    String code = text(row, "stockCode", "").trim();
                    StockMarket stock = stockByCode.get(code);
                    if (stock == null || prices.containsKey(code)) {
                        throw new IllegalStateException("AI实时评分返回了未知或重复股票代码=" + code);
                    }
                    if (!row.path("priceAdvice").isObject()) {
                        throw new IllegalStateException("AI实时评分返回缺少价格建议，股票=" + code);
                    }
                    scores.put(code, parseAiFactorScores(row, scoringModel));
                    AiTradeAdvice priceAdvice = ensureTradeAdvice(stock,
                            parseTradeAdvice(row.path("priceAdvice"), stock.price()));
                    prices.put(code, priceAdvice);
                    successRateModels.put(code, ensureSuccessRateModel(stock,
                            parseTradeSuccessRateModel(row.path("successRateModel"), code,
                                    access.get().provider(), access.get().model())));
                    analysisModels.put(code, ensureTradeAnalysisModel(stock,
                            parseTradeAnalysisModel(row.path("analysisModel"), code,
                                    access.get().provider(), access.get().model()), priceAdvice));
                }
                if (prices.size() != stocks.size() || scores.size() != stocks.size()
                        || successRateModels.size() != stocks.size() || analysisModels.size() != stocks.size()) {
                    throw new IllegalStateException("AI实时评分返回缺少股票结果");
                }
                prices.forEach((code, advice) -> recordStore.saveStockAdvice(code, advice,
                        stockAdviceInput(stockByCode.get(code), news), access.get().provider(), access.get().model(), PRICE_PROMPT_VERSION));
                return new BatchRealtimeAnalysis(prices, scores, successRateModels, analysisModels);
            } catch (Exception ex) {
                lastFailure = ex;
                log.warn("AI实时评分批量分析失败，第 {}/{} 次：{}", attempt, COMPANY_ANALYSIS_MAX_ATTEMPTS, ex.getMessage());
                if (attempt < COMPANY_ANALYSIS_MAX_ATTEMPTS) {
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("AI实时评分重试被中断", interrupted);
                    }
                }
            }
        }
        return fallback;
    }

    public BatchCompanyAnalysis analyzeCompanies(List<StockMarket> stocks,
                                                  List<NewsHotspotStore.News> news) {
        if (stocks == null || stocks.isEmpty()) return new BatchCompanyAnalysis(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        BatchCompanyAnalysis fallback = fallbackCompanyAnalysis(stocks, news);
        if (stocks.stream().anyMatch(stock -> stock == null || stock.price() == null || stock.code() == null || stock.code().isBlank())) {
            return fallback;
        }
        var access = configStore.access();
        if (access.isEmpty()) return fallback;
        ScoringModel scoringModel = activeScoringModel();

        String system = "你是股票研究系统的公司基本信息分析器。只根据给定的股票资料、行情和新闻公告摘要进行归纳；资料不足时必须明确说无法判断，不得编造主营业务或事实。不得输出 BUY、SELL、买入数量或自动交易指令。必须为输入中的每一家公司返回一条结果，stockCode 必须原样保留。";
        String user = "请一次性分析下面全部公司，先主动搜索公司的主营业务并补充到下面的businessDescription里，然后执行下面的分析并严格返回 JSON 对象："
                + "{companies:[{stockCode:string,businessDescription:string,outlook:string,futureTrend:上行倾向/震荡/下行风险/无法判断,risk:string,confidence:number,scores:" + scoreSchema(scoringModel) + ",priceAdvice:{buyLow:number,buyHigh:number,nextSupportPrice:number,takeProfit1:number,takeProfit2:number,hardStop:number,trailingStop:number,bandAdvice:string,suggestions:string[],confidence:number},"
                + "successRateModel:{baseProbability:0-100,confidenceWeight:0-40,aiPriceMatchBonus:0-30,aiPriceMismatchPenalty:0-30,technicalMatchBonus:0-30,technicalMismatchPenalty:0-30,hardRiskPenalty:0-50,warningPenalty:0-10,minProbability:0-100,maxProbability:0-100,confidence:0-1,summary:string,reasons:string[]},"
                + "analysisModel:{summary:string,buySuggestions:string[],sellSuggestions:string[],riskWarnings:string[],confidence:0-1}}]}。"
                + "companies 数量必须等于输入公司数量，不能遗漏、合并或新增公司；confidence 范围为 0 到 1。\n待分析公司：\n"
                + stocks.stream().map(stock -> "---\n" + companyInput(stock, news)).reduce((left, right) -> left + "\n" + right).orElse("");
        MapPayload payload = new MapPayload(access.get().model(), List.of(
                new Message("system", system), new Message("user", user)));
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= COMPANY_ANALYSIS_MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(chatEndpoint(access.get().baseUrl())))
                        .timeout(COMPANY_ANALYSIS_TIMEOUT)
                        .header("Authorization", "Bearer " + access.get().apiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", "DolphinStock/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                        .build();
                logAiRequestConfig(access.get());
                log.info(objectMapper.writeValueAsString(payload));
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) throw aiHttpError(response);
                JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
                log.info(content.toString());
                if (!content.isTextual()) throw new IllegalStateException("AI返回缺少批量JSON内容");
                JsonNode rows = objectMapper.readTree(stripFence(content.asText())).path("companies");
                if (!rows.isArray() || rows.size() != stocks.size()) {
                    throw new IllegalStateException("AI批量返回数量不完整，期望=" + stocks.size() + "，实际=" + (rows.isArray() ? rows.size() : 0));
                }
                Map<String, StockMarket> stockByCode = stocks.stream()
                        .collect(java.util.stream.Collectors.toMap(StockMarket::code, stock -> stock, (left, right) -> left, LinkedHashMap::new));
                Map<String, AiCompanyAnalysis> companyResult = new LinkedHashMap<>();
                Map<String, AiTradeAdvice> priceResult = new LinkedHashMap<>();
                Map<String, AiFactorScores> factorResult = new LinkedHashMap<>();
                Map<String, TradeSuccessRateModel> successRateResult = new LinkedHashMap<>();
                Map<String, TradeAnalysisModel> analysisResult = new LinkedHashMap<>();
                for (JsonNode row : rows) {
                    String code = text(row, "stockCode", "").trim();
                    StockMarket stock = stockByCode.get(code);
                    if (stock == null || companyResult.containsKey(code)) {
                        throw new IllegalStateException("AI批量返回了未知或重复股票代码=" + code);
                    }
                    if (!row.path("priceAdvice").isObject()) {
                        throw new IllegalStateException("AI批量返回缺少价格建议，股票=" + code);
                    }
                    AiFactorScores factorScores = parseAiFactorScores(row, scoringModel);
                    AiCompanyAnalysis analysis = parseCompanyAnalysis(row);
                    companyResult.put(code, analysis);
                    AiTradeAdvice priceAdvice = ensureTradeAdvice(stock,
                            parseTradeAdvice(row.path("priceAdvice"), stock.price()));
                    priceResult.put(code, priceAdvice);
                    factorResult.put(code, factorScores);
                    successRateResult.put(code, ensureSuccessRateModel(stock,
                            parseTradeSuccessRateModel(row.path("successRateModel"), code,
                                    access.get().provider(), access.get().model())));
                    analysisResult.put(code, ensureTradeAnalysisModel(stock,
                            parseTradeAnalysisModel(row.path("analysisModel"), code,
                                    access.get().provider(), access.get().model()), priceAdvice));
                }
                if (companyResult.size() != stocks.size() || priceResult.size() != stocks.size()
                        || factorResult.size() != stocks.size() || successRateResult.size() != stocks.size()
                        || analysisResult.size() != stocks.size()) {
                    throw new IllegalStateException("AI批量返回缺少股票结果");
                }
                companyResult.forEach((code, analysis) -> {
                    StockMarket stock = stockByCode.get(code);
                    recordStore.saveCompanyAnalysis(code, analysis, companyInput(stock, news),
                            access.get().provider(), access.get().model(), COMPANY_BATCH_PROMPT_VERSION);
                    companyProfileStore.save(code, LocalDate.now(), analysis, access.get().provider(), access.get().model());
                    recordStore.saveStockAdvice(code, priceResult.get(code), stockAdviceInput(stock, news),
                            access.get().provider(), access.get().model(), PRICE_PROMPT_VERSION);
                });
                return new BatchCompanyAnalysis(companyResult, priceResult, factorResult, successRateResult, analysisResult);
            } catch (Exception ex) {
                lastFailure = ex;
                log.warn("AI批量公司分析失败，第 {}/{} 次：{}", attempt, COMPANY_ANALYSIS_MAX_ATTEMPTS, ex.getMessage());
                if (attempt < COMPANY_ANALYSIS_MAX_ATTEMPTS) {
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("AI批量公司分析重试被中断", interrupted);
                    }
                }
            }
        }
        return fallback;
    }

    /**
     * Every AI result has a deterministic, clearly-labelled fallback.  This keeps
     * the product usable when the provider is down without pretending that a
     * local rule result came from the remote model.
     */
    public AiTradeAdvice ensureTradeAdvice(StockMarket stock, AiTradeAdvice candidate) {
        if (candidate == null || !candidate.available()) {
            return fallbackTradeAdvice(stock, "AI未返回有效价格建议");
        }
        List<String> suggestions = candidate.suggestions() == null
                ? new ArrayList<>() : new ArrayList<>(candidate.suggestions());
        if (suggestions.stream().noneMatch(value -> value != null && !value.isBlank())) {
            suggestions.add("AI返回了部分价格数据；其余按真实行情和技术区间执行");
        }
        String bandAdvice = candidate.bandAdvice() == null || candidate.bandAdvice().isBlank()
                ? "AI返回了部分价格数据，已按真实行情和技术区间兜底"
                : candidate.bandAdvice();
        return new AiTradeAdvice(true, candidate.buyLow(), candidate.buyHigh(), candidate.nextSupportPrice(),
                candidate.takeProfit1(), candidate.takeProfit2(), candidate.hardStop(), candidate.trailingStop(),
                bandAdvice, suggestions.stream().filter(value -> value != null && !value.isBlank()).limit(5).toList(),
                candidate.confidence() == null ? FALLBACK_CONFIDENCE : candidate.confidence());
    }

    public AiCompanyAnalysis ensureCompanyAnalysis(StockMarket stock, AiCompanyAnalysis candidate) {
        if (candidate != null && candidate.available()) return candidate;
        return fallbackCompanyAnalysis(stock, "AI未返回有效公司分析");
    }

    public TradeAnalysisModel ensureTradeAnalysisModel(StockMarket stock, TradeAnalysisModel candidate,
                                                       AiTradeAdvice advice) {
        if (candidate != null && candidate.summary() != null && !candidate.summary().isBlank()
                && candidate.buySuggestions() != null && !candidate.buySuggestions().isEmpty()
                && candidate.sellSuggestions() != null && !candidate.sellSuggestions().isEmpty()) {
            return candidate;
        }
        return fallbackTradeAnalysisModel(stock, ensureTradeAdvice(stock, advice));
    }

    public TradeSuccessRateModel ensureSuccessRateModel(StockMarket stock, TradeSuccessRateModel candidate) {
        return candidate == null ? fallbackSuccessRateModel(stock) : candidate;
    }

    public AiFactorScores ensureFactorScores(StockMarket stock, AiFactorScores candidate,
                                             List<NewsHotspotStore.News> news) {
        return candidate == null ? fallbackFactorScores(stock, activeScoringModel(), news) : candidate;
    }

    private AiTradeAdvice fallbackTradeAdvice(StockMarket stock, String reason) {
        String safeReason = reason == null || reason.isBlank() ? "AI未返回有效结果" : reason;
        if (stock == null || stock.price() == null || stock.price().signum() <= 0) {
            return new AiTradeAdvice(true, null, null, null, null, null, null, null,
                    "本地规则兜底：缺少真实价格，暂不生成数值价格区间",
                    List.of("AI接口未返回，已生成兜底建议；补齐真实价格后重新生成价格计划",
                            "当前不使用虚拟价格，不据此直接制定买卖价格"), FALLBACK_CONFIDENCE);
        }
        BigDecimal current = stock.price().setScale(3, RoundingMode.HALF_UP);
        BigDecimal ma20 = positiveOr(stock.ma20(), current);
        BigDecimal ma60 = positiveOr(stock.ma60(), current);
        BigDecimal buyHigh = ma20.min(current).setScale(3, RoundingMode.HALF_UP);
        BigDecimal buyLow = buyHigh.multiply(new BigDecimal("0.97")).max(current.multiply(new BigDecimal("0.60")))
                .setScale(3, RoundingMode.HALF_UP);
        BigDecimal nextSupport = ma60.min(buyLow).min(current.multiply(new BigDecimal("0.95")))
                .max(current.multiply(new BigDecimal("0.40"))).setScale(3, RoundingMode.HALF_UP);
        BigDecimal takeProfit1 = current.multiply(new BigDecimal("1.008")).setScale(3, RoundingMode.HALF_UP);
        BigDecimal takeProfit2 = current.multiply(new BigDecimal("1.016")).setScale(3, RoundingMode.HALF_UP);
        BigDecimal hardStop = current.multiply(new BigDecimal("0.92")).setScale(3, RoundingMode.HALF_UP);
        BigDecimal trailingStop = current.multiply(new BigDecimal("0.95")).setScale(3, RoundingMode.HALF_UP);
        String trend = technicalTrend(stock);
        String bandAdvice = "本地规则兜底：" + trend + "；买入区间 " + buyLow + " — " + buyHigh
                + "，先观察承接，不追价";
        List<String> suggestions = new ArrayList<>();
        suggestions.add("AI接口未返回（" + safeReason + "），已使用真实行情、MA20/MA60生成规则兜底建议");
        suggestions.add("参考买入区间 " + buyLow + " — " + buyHigh + "，下一承接 " + nextSupport + "；只考虑分批执行");
        suggestions.add("第一止盈 " + takeProfit1 + "，第二止盈 " + takeProfit2 + "；突破后再上移保护线");
        suggestions.add("硬止损 " + hardStop + "，移动止损 " + trailingStop + "；跌破后暂停补仓并重新评估");
        if (stock.rsi14() != null && stock.rsi14().compareTo(new BigDecimal("75")) >= 0) {
            suggestions.add("RSI偏高，当前不追涨，等待回踩确认");
        }
        return new AiTradeAdvice(true, buyLow, buyHigh, nextSupport, takeProfit1, takeProfit2,
                hardStop, trailingStop, bandAdvice, suggestions.stream().limit(5).toList(), FALLBACK_CONFIDENCE);
    }

    private AiCompanyAnalysis fallbackCompanyAnalysis(StockMarket stock, String reason) {
        String code = stock == null || stock.code() == null ? "未知股票" : stock.code();
        String name = stock == null || stock.name() == null || stock.name().isBlank() ? code : stock.name();
        String industry = stock == null || stock.industry() == null || stock.industry().isBlank() ? "行业资料暂缺" : stock.industry();
        String trend = technicalTrend(stock);
        String risk = "AI接口未返回（" + safeText(reason, "未知原因") + "），已使用本地规则兜底；" + (stock != null && stock.rsi14() != null
                && stock.rsi14().compareTo(new BigDecimal("75")) >= 0 ? "RSI偏高，注意追涨风险" : "请结合实时行情、公告和财务资料复核");
        return new AiCompanyAnalysis(true,
                name + "（" + industry + "）的公司资料暂未完成远程AI整理，当前仅保留代码、名称和行业信息",
                "基于当前技术数据的本地辅助判断：" + trend + "；基本面前景需补充公司公告和财务资料",
                trend, risk, FALLBACK_CONFIDENCE);
    }

    private Map<String, AiCompanyAnalysis> fallbackCompanyProfiles(List<StockMarket> stocks, String reason) {
        Map<String, AiCompanyAnalysis> result = new LinkedHashMap<>();
        if (stocks != null) {
            stocks.stream().filter(stock -> stock != null && stock.code() != null && !stock.code().isBlank())
                    .forEach(stock -> result.put(stock.code(), fallbackCompanyAnalysis(stock, reason)));
        }
        return result;
    }

    private BatchCompanyAnalysis fallbackCompanyAnalysis(List<StockMarket> stocks,
                                                          List<NewsHotspotStore.News> news) {
        ScoringModel model = activeScoringModel();
        Map<String, AiCompanyAnalysis> companies = new LinkedHashMap<>();
        Map<String, AiTradeAdvice> prices = new LinkedHashMap<>();
        Map<String, AiFactorScores> factors = new LinkedHashMap<>();
        Map<String, TradeSuccessRateModel> successRates = new LinkedHashMap<>();
        Map<String, TradeAnalysisModel> analyses = new LinkedHashMap<>();
        for (StockMarket stock : stocks) {
            if (stock == null || stock.code() == null || stock.code().isBlank()) continue;
            AiTradeAdvice advice = fallbackTradeAdvice(stock, "批量AI接口失败");
            companies.put(stock.code(), fallbackCompanyAnalysis(stock, "批量AI接口失败"));
            prices.put(stock.code(), advice);
            factors.put(stock.code(), fallbackFactorScores(stock, model, news));
            successRates.put(stock.code(), fallbackSuccessRateModel(stock));
            analyses.put(stock.code(), fallbackTradeAnalysisModel(stock, advice));
        }
        return new BatchCompanyAnalysis(companies, prices, factors, successRates, analyses);
    }

    private BatchRealtimeAnalysis fallbackRealtimeAnalysis(List<StockMarket> stocks,
                                                            List<NewsHotspotStore.News> news) {
        ScoringModel model = activeScoringModel();
        Map<String, AiTradeAdvice> prices = new LinkedHashMap<>();
        Map<String, AiFactorScores> factors = new LinkedHashMap<>();
        Map<String, TradeSuccessRateModel> successRates = new LinkedHashMap<>();
        Map<String, TradeAnalysisModel> analyses = new LinkedHashMap<>();
        for (StockMarket stock : stocks) {
            if (stock == null || stock.code() == null || stock.code().isBlank()) continue;
            AiTradeAdvice advice = fallbackTradeAdvice(stock, "实时AI接口失败");
            prices.put(stock.code(), advice);
            factors.put(stock.code(), fallbackFactorScores(stock, model, news));
            successRates.put(stock.code(), fallbackSuccessRateModel(stock));
            analyses.put(stock.code(), fallbackTradeAnalysisModel(stock, advice));
        }
        return new BatchRealtimeAnalysis(prices, factors, successRates, analyses);
    }

    private AiFactorScores fallbackFactorScores(StockMarket stock, ScoringModel model,
                                                 List<NewsHotspotStore.News> news) {
        int trend = technicalPercent(stock);
        int financial = financialPercent(stock);
        int growth = growthPercent(stock);
        int catalyst = catalystPercent(stock, news);
        int risk = riskPercent(stock);
        return new AiFactorScores(
                scoreForWeight(model.businessModelWeight(), stock == null || stock.industry() == null ? 45 : 60),
                scoreForWeight(model.industryProspectWeight(), trend),
                scoreForWeight(model.competitiveAdvantageWeight(), financial),
                scoreForWeight(model.financialQualityWeight(), financial),
                scoreForWeight(model.growthWeight(), growth),
                scoreForWeight(model.valuationWeight(), trend),
                scoreForWeight(model.catalystWeight(), catalyst),
                scoreForWeight(model.riskWeight(), risk), model.version());
    }

    private TradeSuccessRateModel fallbackSuccessRateModel(StockMarket stock) {
        int trend = technicalPercent(stock);
        BigDecimal base = BigDecimal.valueOf(35 + trend * 30L / 100L);
        return new TradeSuccessRateModel(stock.code(), 1, LocalDateTime.now(), FALLBACK_PROVIDER, FALLBACK_MODEL,
                base, new BigDecimal("8"), new BigDecimal("12"), new BigDecimal("12"),
                new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("35"), new BigDecimal("4"),
                new BigDecimal("20"), new BigDecimal("80"), FALLBACK_CONFIDENCE,
                "AI接口未返回，按技术趋势生成预计交易成功率基准，仅供比较参考",
                List.of("趋势评分 " + trend + "/100", "本地规则结果不是历史统计胜率"));
    }

    private TradeAnalysisModel fallbackTradeAnalysisModel(StockMarket stock, AiTradeAdvice advice) {
        String priceText = stock == null || stock.price() == null ? "缺少真实价格" : "当前价 " + stock.price();
        return new TradeAnalysisModel(stock.code(), 1, LocalDateTime.now(), FALLBACK_PROVIDER, FALLBACK_MODEL,
                "AI接口未返回，已根据真实行情和技术指标生成本地规则计划分析",
                List.of("" + priceText + "；买入仅观察 " + valueText(advice.buyLow()) + " — " + valueText(advice.buyHigh()) + " 区间",
                        "确认承接后分批执行，单次仓位不要超过策略上限"),
                List.of("第一止盈参考 " + valueText(advice.takeProfit1()) + "，结合量价分批止盈",
                        "跌破硬止损 " + valueText(advice.hardStop()) + " 后暂停补仓并重新评估"),
                List.of("本地兜底结果不代表远程AI结论", "真实行情或技术数据缺失时不使用虚拟价格"), FALLBACK_CONFIDENCE);
    }

    private PortfolioAnalysis fallbackPortfolioAnalysis(String reason) {
        return new PortfolioAnalysis(true, LocalDateTime.now(),
                "AI接口未返回，已使用当前输入中的行情和持仓资料完成基础复盘",
                "已保留组合持仓、成本、实时价格和交易记录，等待AI恢复后可再次生成归因分析",
                List.of("基础持仓和交易资料已接收，可继续追踪实际盈亏变化"),
                List.of("当前无法从AI接口确认历史决策的具体失误原因"),
                List.of("先区分价格波动、仓位大小和执行价格，再复核每笔确认交易"),
                List.of("继续记录确认交易；按买入区间、止盈线和硬止损复核计划"),
                List.of("本次为本地规则兜底，不构成远程AI结论或买卖建议"),
                FALLBACK_CONFIDENCE, "AI接口未返回，已使用规则复盘兜底：" + safeText(reason, "未知原因"));
    }

    private ModelAdjustment fallbackModelAdjustment(String reason) {
        ScoringModel current;
        try {
            current = activeScoringModel();
        } catch (Exception ignored) {
            current = ScoringModel.defaultModel();
        }
        return new ModelAdjustment(true, current.businessModelWeight(), current.industryProspectWeight(),
                current.competitiveAdvantageWeight(), current.financialQualityWeight(), current.growthWeight(),
                current.valuationWeight(), current.catalystWeight(), current.riskWeight(),
                "AI接口未返回，保留当前评分权重并使用本地规则兜底",
                List.of("未改变已有模型权重", "待AI恢复后重新进行模型治理"),
                "本地规则兜底，不代表远程AI可信度结论", FORCED_MODEL_CONFIDENCE);
    }

    private AiAnalysisRecordStore.NewsAiResult fallbackNewsAnalysis(NewsHotspotStore.News news) {
        String raw = ((news.title() == null ? "" : news.title()) + " "
                + (news.content() == null ? "" : news.content())).trim();
        String eventType = raw.matches(".*(回购|增持|重大合同|并购重组|业绩预增|业绩增长|分红|派息|利润分配).*" )
                ? "利好" : raw.matches(".*(减持|立案调查|风险警示|重大诉讼|仲裁|业绩预亏|业绩下滑).*" ) ? "利空" : "中性";
        BigDecimal sentiment = "利好".equals(eventType) ? new BigDecimal("0.30")
                : "利空".equals(eventType) ? new BigDecimal("-0.30") : BigDecimal.ZERO;
        return new AiAnalysisRecordStore.NewsAiResult(news.code(), eventType, sentiment,
                "本地规则兜底摘要：" + compactText(raw, "已接收新闻/公告，请打开原文核对事实"),
                "利空".equals(eventType) ? "MEDIUM" : "LOW", FALLBACK_CONFIDENCE);
    }

    private int technicalPercent(StockMarket stock) {
        if (stock == null || stock.price() == null) return 50;
        int value = 50;
        if (stock.ma20() != null) value += stock.price().compareTo(stock.ma20()) >= 0 ? 15 : -15;
        if (stock.ma60() != null) value += stock.price().compareTo(stock.ma60()) >= 0 ? 15 : -15;
        if (stock.rsi14() != null) value += stock.rsi14().compareTo(new BigDecimal("70")) < 0 ? 10 : -10;
        return Math.max(0, Math.min(100, value));
    }

    private int financialPercent(StockMarket stock) {
        if (stock == null) return 45;
        int value = 50;
        if (stock.roe() != null) value += stock.roe().compareTo(new BigDecimal("15")) >= 0 ? 25
                : stock.roe().compareTo(new BigDecimal("8")) >= 0 ? 10 : -10;
        if (stock.debtRatio() != null) value += stock.debtRatio().compareTo(new BigDecimal("50")) <= 0 ? 15
                : stock.debtRatio().compareTo(new BigDecimal("70")) <= 0 ? 0 : -20;
        return Math.max(0, Math.min(100, value));
    }

    private int growthPercent(StockMarket stock) {
        if (stock == null) return 45;
        int value = 50;
        if (stock.profitGrowth() != null) value += stock.profitGrowth().signum() > 0 ? 20 : -20;
        if (stock.revenueGrowth() != null) value += stock.revenueGrowth().signum() > 0 ? 15 : -15;
        return Math.max(0, Math.min(100, value));
    }

    private int catalystPercent(StockMarket stock, List<NewsHotspotStore.News> news) {
        if (stock == null || news == null) return 45;
        long positive = news.stream().filter(item -> newsMatchesStock(stock, item))
                .filter(item -> "利好".equals(item.eventType()) || item.sentiment() != null && item.sentiment().signum() > 0).count();
        long negative = news.stream().filter(item -> newsMatchesStock(stock, item))
                .filter(item -> "利空".equals(item.eventType()) || item.sentiment() != null && item.sentiment().signum() < 0).count();
        return positive == 0 && negative == 0 ? 45 : positive >= negative ? 70 : 30;
    }

    private int riskPercent(StockMarket stock) {
        if (stock == null) return 40;
        int value = 80;
        if (stock.st() || stock.suspended()) value -= 45;
        if (stock.debtRatio() != null && stock.debtRatio().compareTo(new BigDecimal("70")) > 0) value -= 20;
        if (stock.rsi14() != null && stock.rsi14().compareTo(new BigDecimal("78")) >= 0) value -= 15;
        return Math.max(0, Math.min(100, value));
    }

    private int scoreForWeight(int weight, int percent) {
        return BigDecimal.valueOf(Math.max(0, Math.min(100, percent))).multiply(BigDecimal.valueOf(weight))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).intValue();
    }

    private BigDecimal positiveOr(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private String technicalTrend(StockMarket stock) {
        if (stock == null || stock.price() == null) return "无法判断";
        boolean above20 = stock.ma20() == null || stock.price().compareTo(stock.ma20()) >= 0;
        boolean above60 = stock.ma60() == null || stock.price().compareTo(stock.ma60()) >= 0;
        return above20 && above60 ? "上行倾向" : !above20 && !above60 ? "下行风险" : "震荡";
    }

    private String valueText(BigDecimal value) { return value == null ? "暂无" : value.toPlainString(); }

    private String safeText(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    private String compactText(String value, String fallback) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (text.isBlank()) return fallback;
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }

    private String companyInput(StockMarket stock, List<NewsHotspotStore.News> news) {
        StringBuilder input = new StringBuilder();
        input.append("代码=").append(stock.code()).append(",名称=").append(stock.name())
                .append(",行业=").append(stock.industry()).append(",当前价=").append(stock.price())
                .append(",涨跌幅=").append(stock.changePercent()).append(",MA20=").append(stock.ma20())
                .append(",MA60=").append(stock.ma60()).append(",RSI14=").append(stock.rsi14());
        relevantNews(stock, news).forEach(item -> input.append("\n")
                .append(newsMatchesStock(stock, item) ? "新闻/公告=" : "市场新闻参考=")
                .append(item.title()).append("；摘要=").append(item.aiSummary()));
        return input.toString();
    }

    private String realtimeInput(StockMarket stock, AiCompanyAnalysis profile,
                                 List<NewsHotspotStore.News> news) {
        StringBuilder input = new StringBuilder(companyInput(stock, news));
        if (profile != null && profile.available()) {
            input.append("\n缓存主营业务=").append(profile.businessDescription())
                    .append("\n缓存公司前景=").append(profile.outlook())
                    .append("\n缓存趋势=").append(profile.futureTrend())
                    .append("\n缓存风险=").append(profile.risk());
        } else {
            input.append("\n缓存公司资料=不可用，本次不补充公司事实");
        }
        return input.toString();
    }

    private AiCompanyAnalysis parseCompanyAnalysis(JsonNode json) {
        String trend = text(json, "futureTrend", "无法判断");
        if (!List.of("上行倾向", "震荡", "下行风险", "无法判断").contains(trend)) trend = "无法判断";
        return new AiCompanyAnalysis(true,
                text(json, "businessDescription", "主营信息不可用"),
                text(json, "outlook", "无法判断"), trend,
                text(json, "risk", "暂无明确风险结论"),
                number(json, "confidence", new BigDecimal("0.5")).max(BigDecimal.ZERO).min(BigDecimal.ONE)
                        .setScale(4, RoundingMode.HALF_UP));
    }

    private AiFactorScores parseAiFactorScores(JsonNode row, ScoringModel scoringModel) {
        JsonNode scores = row.path("scores");
        if (!scores.isObject()) throw new IllegalStateException("AI批量返回缺少评分结果");
        return new AiFactorScores(
                requiredScore(scores, "businessModel", scoringModel.businessModelWeight()),
                requiredScore(scores, "industryProspect", scoringModel.industryProspectWeight()),
                requiredScore(scores, "competitiveAdvantage", scoringModel.competitiveAdvantageWeight()),
                requiredScore(scores, "financialQuality", scoringModel.financialQualityWeight()),
                requiredScore(scores, "growth", scoringModel.growthWeight()),
                requiredScore(scores, "valuation", scoringModel.valuationWeight()),
                requiredScore(scores, "catalyst", scoringModel.catalystWeight()),
                requiredScore(scores, "risk", scoringModel.riskWeight()), scoringModel.version());
    }

    private ScoringModel activeScoringModel() {
        return scoringModelStore.loadLatest().orElse(ScoringModel.defaultModel());
    }

    private String scoreSchema(ScoringModel model) {
        return "{businessModel:0-" + model.businessModelWeight()
                + ",industryProspect:0-" + model.industryProspectWeight()
                + ",competitiveAdvantage:0-" + model.competitiveAdvantageWeight()
                + ",financialQuality:0-" + model.financialQualityWeight()
                + ",growth:0-" + model.growthWeight()
                + ",valuation:0-" + model.valuationWeight()
                + ",catalyst:0-" + model.catalystWeight()
                + ",risk:0-" + model.riskWeight() + "}";
    }

    private int requiredScore(JsonNode node, String field, int maximum) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) throw new IllegalStateException("AI批量返回评分不是整数：" + field);
        int score = value.asInt();
        if (score < 0 || score > maximum) {
            throw new IllegalStateException("AI批量返回评分超出范围：" + field + "=" + score);
        }
        return score;
    }

    public record BatchRealtimeAnalysis(Map<String, AiTradeAdvice> priceAdvices,
                                        Map<String, AiFactorScores> factorScores,
                                        Map<String, TradeSuccessRateModel> successRateModels,
                                        Map<String, TradeAnalysisModel> analysisModels) {}

    public record BatchCompanyAnalysis(Map<String, AiCompanyAnalysis> companyAnalyses,
                                       Map<String, AiTradeAdvice> priceAdvices,
                                       Map<String, AiFactorScores> factorScores,
                                       Map<String, TradeSuccessRateModel> successRateModels,
                                       Map<String, TradeAnalysisModel> analysisModels) {}

    public record AiFactorScores(int businessModel, int industryProspect, int competitiveAdvantage,
                                 int financialQuality, int growth, int valuation, int catalyst, int risk,
                                 int modelVersion) {
        public AiFactorScores(int businessModel, int industryProspect, int competitiveAdvantage,
                              int financialQuality, int growth, int valuation, int catalyst, int risk) {
            this(businessModel, industryProspect, competitiveAdvantage, financialQuality, growth,
                    valuation, catalyst, risk, 0);
        }
    }

    public record ModelAdjustment(boolean available, int businessModelWeight, int industryProspectWeight,
                                  int competitiveAdvantageWeight, int financialQualityWeight, int growthWeight,
                                  int valuationWeight, int catalystWeight, int riskWeight,
                                  String summary, List<String> reasons,
                                  String credibilityBasis, BigDecimal confidence) {
        public static ModelAdjustment unavailable(String reason) {
            return new ModelAdjustment(false, 0, 0, 0, 0, 0, 0, 0, 0,
                    "本轮未调整模型", List.of(reason == null ? "AI不可用" : reason),
                    "未完成可信度评估", BigDecimal.ZERO);
        }
    }

    private TradeSuccessRateModel parseTradeSuccessRateModel(JsonNode json, String stockCode,
                                                              String provider, String model) {
        if (!json.isObject()) throw new IllegalStateException("AI返回缺少预计交易成功率模型，股票=" + stockCode);
        BigDecimal min = boundedNumber(json, "minProbability", 0, 100);
        BigDecimal max = boundedNumber(json, "maxProbability", 0, 100);
        if (min.compareTo(max) > 0) throw new IllegalStateException("AI成功率模型区间无效，股票=" + stockCode);
        return new TradeSuccessRateModel(stockCode, 1, LocalDateTime.now(), provider, model,
                boundedNumber(json, "baseProbability", 0, 100), boundedNumber(json, "confidenceWeight", 0, 40),
                boundedNumber(json, "aiPriceMatchBonus", 0, 30), boundedNumber(json, "aiPriceMismatchPenalty", 0, 30),
                boundedNumber(json, "technicalMatchBonus", 0, 30), boundedNumber(json, "technicalMismatchPenalty", 0, 30),
                boundedNumber(json, "hardRiskPenalty", 0, 50), boundedNumber(json, "warningPenalty", 0, 10),
                min, max, boundedNumber(json, "confidence", 0, 1),
                text(json, "summary", "AI未提供成功率模型摘要"), textList(json, "reasons"));
    }

    private TradeAnalysisModel parseTradeAnalysisModel(JsonNode json, String stockCode,
                                                        String provider, String model) {
        if (!json.isObject()) throw new IllegalStateException("AI返回缺少计划分析模型，股票=" + stockCode);
        return new TradeAnalysisModel(stockCode, 1, LocalDateTime.now(), provider, model,
                text(json, "summary", "AI未提供计划分析摘要"), limitedTextList(json, "buySuggestions"),
                limitedTextList(json, "sellSuggestions"), limitedTextList(json, "riskWarnings"),
                boundedNumber(json, "confidence", 0, 1));
    }

    private List<String> limitedTextList(JsonNode node, String field) {
        return textList(node, field).stream().limit(5).toList();
    }

    private BigDecimal boundedNumber(JsonNode node, String field, int min, int max) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) throw new IllegalStateException("AI模型字段不是数字：" + field);
        BigDecimal result = value.decimalValue();
        if (result.compareTo(BigDecimal.valueOf(min)) < 0 || result.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalStateException("AI模型字段超出范围：" + field + "=" + result);
        }
        return result.setScale(4, RoundingMode.HALF_UP);
    }

    private boolean aiCallsAvailable() {
        return System.currentTimeMillis() >= aiBlockedUntilMillis;
    }

    private void blockAiForRefresh(Exception ex) {
        aiBlockedUntilMillis = System.currentTimeMillis() + AI_FAILURE_COOLDOWN_MILLIS;
        log.warn("AI本轮分析已熔断 5 分钟，后续推荐继续使用可用的行情/技术数据: {}", ex.getMessage());
    }

    private AiTradeAdvice parseTradeAdvice(JsonNode json, BigDecimal current) {
        BigDecimal buyLow = boundedBuyPrice(json, "buyLow", current);
        BigDecimal buyHigh = boundedBuyPrice(json, "buyHigh", current);
        if (buyLow != null && buyHigh != null && buyLow.compareTo(buyHigh) > 0) {
            BigDecimal swap = buyLow;
            buyLow = buyHigh;
            buyHigh = swap;
        }
        BigDecimal nextSupport = boundedPrice(json, "nextSupportPrice", current);
        BigDecimal takeProfit1 = boundedPrice(json, "takeProfit1", current);
        BigDecimal takeProfit2 = boundedPrice(json, "takeProfit2", current);
        BigDecimal hardStop = boundedPrice(json, "hardStop", current);
        BigDecimal trailingStop = boundedPrice(json, "trailingStop", current);
        String bandAdvice = text(json, "bandAdvice", "AI未提供波段意见");
        List<String> suggestions = new ArrayList<>();
        JsonNode suggestionNode = json.path("suggestions");
        if (suggestionNode.isArray()) {
            suggestionNode.forEach(node -> {
                String value = node.asText("").trim();
                if (!value.isBlank() && suggestions.size() < 5) suggestions.add(value);
            });
        }
        BigDecimal confidence = number(json, "confidence", new BigDecimal("0.5"))
                .max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        boolean available = buyLow != null || buyHigh != null || nextSupport != null || takeProfit1 != null
                || takeProfit2 != null || hardStop != null || trailingStop != null || !suggestions.isEmpty();
        return new AiTradeAdvice(available, buyLow, buyHigh, nextSupport, takeProfit1, takeProfit2,
                hardStop, trailingStop, bandAdvice, suggestions, confidence);
    }

    private BigDecimal boundedPrice(JsonNode json, String name, BigDecimal current) {
        BigDecimal value = number(json, name, null);
        if (value == null || value.signum() <= 0) return null;
        BigDecimal lower = current.multiply(new BigDecimal("0.40"));
        BigDecimal upper = current.multiply(new BigDecimal("1.80"));
        if (value.compareTo(lower) < 0 || value.compareTo(upper) > 0) return null;
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal boundedBuyPrice(JsonNode json, String name, BigDecimal current) {
        BigDecimal value = boundedPrice(json, name, current);
        return value == null ? null : value.min(current).setScale(3, RoundingMode.HALF_UP);
    }

    private String stockAdviceInput(StockMarket stock, List<NewsHotspotStore.News> news) {
        StringBuilder input = new StringBuilder();
        input.append("代码=").append(stock.code()).append(",名称=").append(stock.name())
                .append(",当前价=").append(stock.price()).append(",涨跌幅=").append(stock.changePercent())
                .append(",MA5=").append(stock.ma5()).append(",MA20=").append(stock.ma20())
                .append(",MA60=").append(stock.ma60()).append(",MA120=").append(stock.ma120())
                .append(",20日高点=").append(stock.high20()).append(",60日高点=").append(stock.high60())
                .append(",RSI14=").append(stock.rsi14()).append(",MACD=").append(stock.macd())
                .append(",MACD信号=").append(stock.macdSignal()).append(",量比=").append(stock.volumeRatio());
        relevantNews(stock, news).stream()
                .filter(item -> item.aiSummary() != null && !item.aiSummary().isBlank())
                .forEach(item -> input.append("\n")
                        .append(newsMatchesStock(stock, item) ? "新闻=" : "市场新闻参考=")
                        .append(item.title()).append("；AI摘要=").append(item.aiSummary()));
        return input.toString();
    }

    private List<NewsHotspotStore.News> relevantNews(StockMarket stock, List<NewsHotspotStore.News> news) {
        if (news == null || news.isEmpty()) return List.of();
        List<NewsHotspotStore.News> matched = news.stream()
                .filter(item -> newsMatchesStock(stock, item)).limit(3).toList();
        if (!matched.isEmpty()) return matched;
        // The source may omit a code/name tag. Keep current market context in the
        // AI input, but label it so it is never treated as a stock-specific fact.
        return news.stream().filter(item -> item.title() != null && !item.title().isBlank()).limit(3).toList();
    }

    private boolean newsMatchesStock(StockMarket stock, NewsHotspotStore.News item) {
        if (stock == null || item == null) return false;
        String code = stock.code() == null ? "" : stock.code().trim();
        if (!code.isBlank() && item.code() != null && code.equalsIgnoreCase(item.code().trim())) return true;
        String name = stock.name() == null ? "" : stock.name().trim();
        if (name.length() < 2) return false;
        String text = (item.title() == null ? "" : item.title()) + " "
                + (item.content() == null ? "" : item.content());
        return text.contains(name);
    }

    private AiAnalysisRecordStore.NewsAiResult analyzeNews(NewsHotspotStore.News news,
                                                            AiProviderConfigStore.AiProviderAccess access) throws Exception {
        String input = inputOf(news);
        String system = "你是股票研究系统的新闻公告分析器。只分析新闻事实、情绪和风险，不输出 BUY、SELL、买入数量或交易指令。必须返回 JSON。";
        String user = "请分析以下A股新闻，严格返回 JSON："
                + "{eventType:利好/利空/中性,sentiment:-1到1之间数字,summary:不超过80字,riskLevel:LOW/MEDIUM/HIGH,confidence:0到1之间数字}。"
                + "\n新闻：" + input;
        MapPayload payload = new MapPayload(access.model(), List.of(
                new Message("system", system), new Message("user", user)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(chatEndpoint(access.baseUrl())))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + access.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "DolphinStock/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        logAiRequestConfig(access);
        System.out.println(HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw aiHttpError(response);
        JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) throw new IllegalStateException("AI返回缺少JSON内容");
        System.out.println(stripFence(content.asText()));
        JsonNode json = objectMapper.readTree(stripFence(content.asText()));
        String eventType = text(json, "eventType", "中性");
        if (!List.of("利好", "利空", "中性").contains(eventType)) eventType = "中性";
        BigDecimal sentiment = number(json, "sentiment", BigDecimal.ZERO).max(BigDecimal.ONE.negate()).min(BigDecimal.ONE);
        BigDecimal confidence = number(json, "confidence", new BigDecimal("0.5")).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return new AiAnalysisRecordStore.NewsAiResult(news.code(), eventType, sentiment.setScale(4, RoundingMode.HALF_UP),
                text(json, "summary", "AI未提供摘要"), text(json, "riskLevel", "MEDIUM"), confidence.setScale(4, RoundingMode.HALF_UP));
    }

    private String inputOf(NewsHotspotStore.News news) {
        String content = news.content() == null ? "" : news.content();
        if (content.length() > 4500) content = content.substring(0, 4500);
        return "标题：" + (news.title() == null ? "" : news.title()) + "\n正文：" + content;
    }

    private String chatEndpoint(String baseUrl) {
        String base = baseUrl == null ? "https://api.deepseek.com" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        // DeepSeek 官方 OpenAI 兼容地址就是根地址，客户端请求路径为 /chat/completions。
        // 兼容旧初始化脚本中曾写入的 /v1 配置，避免请求路径重复或失效。
        if ("https://api.deepseek.com/v1".equalsIgnoreCase(base)) {
            base = "https://api.deepseek.com";
        }
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    private IllegalStateException aiHttpError(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().replaceAll("\\s+", " ").trim();
        if (body.length() > 300) body = body.substring(0, 300) + "…";
        return new IllegalStateException("AI HTTP " + response.statusCode()
                + (body.isBlank() ? "" : "：" + body));
    }

    private void logAiRequestConfig(AiProviderConfigStore.AiProviderAccess access) {
        log.info("[AI] 实际请求配置：provider={}, model={}, baseUrl={}, endpoint={}",
                access.provider(), access.model(), access.baseUrl(), chatEndpoint(access.baseUrl()));
    }

    private String stripFence(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        return text.trim();
    }

    private String text(JsonNode node, String name, String fallback) {
        String value = node.path(name).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private List<String> textList(JsonNode node, String name) {
        JsonNode values = node.path(name);
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            String text = value.asText("").trim();
            if (!text.isBlank() && result.size() < 5) result.add(text.length() <= 120 ? text : text.substring(0, 120) + "…");
        });
        return result;
    }

    private List<String> textListOr(JsonNode node, String name, String fallback) {
        List<String> values = textList(node, name);
        return values.isEmpty() ? List.of(fallback) : values;
    }

    private BigDecimal number(JsonNode node, String name, BigDecimal fallback) {
        try {
            String value = node.path(name).asText(fallback == null ? "" : fallback.toPlainString());
            if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return fallback;
            return new BigDecimal(value);
        }
        catch (Exception ex) { return fallback; }
    }

    private record Message(String role, String content) {}
    private record MapPayload(String model, List<Message> messages, double temperature, ResponseFormat response_format) {
        private MapPayload(String model, List<Message> messages) {
            this(model, messages, 0.1, new ResponseFormat("json_object"));
        }
    }
    private record ResponseFormat(String type) {}
}
