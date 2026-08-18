package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.MarketContext;
import com.dolphin.stock.model.StockAnalysisModels.AiTradeAdvice;
import com.dolphin.stock.model.StockAnalysisModels.AiCompanyAnalysis;
import com.dolphin.stock.model.StockAnalysisModels.StockContext;
import com.dolphin.stock.model.StockAnalysisModels.StockMarket;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MarketContextService {
    private static final String DIVIDEND_KEYWORDS = "分红|派息|利润分配|权益分派|股权登记|除权除息";
    private static final String POSITIVE_EVENT_KEYWORDS = "回购|增持|重大合同|并购重组|业绩预增|业绩增长";
    private static final String NEGATIVE_EVENT_KEYWORDS = "减持|立案调查|风险警示|重大诉讼|仲裁|业绩预亏|业绩下滑";
    private final NewsHotspotStore newsHotspotStore;
    private final AiAnalysisService aiAnalysisService;
    private final NewsIngestionService newsIngestionService;
    private volatile TimedNews recentNewsCache;
    private volatile TimedNews importantEventsCache;

    public MarketContextService(NewsHotspotStore newsHotspotStore, AiAnalysisService aiAnalysisService,
                                NewsIngestionService newsIngestionService) {
        this.newsHotspotStore = newsHotspotStore;
        this.aiAnalysisService = aiAnalysisService;
        this.newsIngestionService = newsIngestionService;
    }

    public MarketContext evaluate(List<StockMarket> stocks) {
        List<NewsHotspotStore.News> news = recentNews();
        List<StockMarket> valid = stocks.stream().filter(stock -> stock.price() != null
                && "REALTIME".equals(stock.quoteStatus()) && stock.changePercent() != null).toList();
        if (valid.isEmpty()) {
            int positive = (int) news.stream().filter(this::isPositive).count();
            int negative = (int) news.stream().filter(this::isNegative).count();
            return new MarketContext(false, 0, "UNKNOWN", 0, 0, 0, 0, 0, null, BigDecimal.ZERO,
                    !news.isEmpty(), positive, negative,
                    news.isEmpty() ? "实时行情不可用，新闻获取失败" : "已获取 " + news.size() + " 条新闻，实时行情不可用",
                    BigDecimal.ZERO, List.of("实时行情不可用，暂不判断市场情绪"));
        }
        int rising = (int) valid.stream().filter(s -> s.changePercent().signum() > 0).count();
        int falling = (int) valid.stream().filter(s -> s.changePercent().signum() < 0).count();
        int flat = valid.size() - rising - falling;
        int limitUp = (int) valid.stream().filter(s -> s.changePercent().compareTo(new BigDecimal("9.5")) >= 0).count();
        int limitDown = (int) valid.stream().filter(s -> s.changePercent().compareTo(new BigDecimal("-9.5")) <= 0).count();
        BigDecimal averageChange = valid.stream().map(StockMarket::changePercent).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(valid.size()), 4, RoundingMode.HALF_UP);
        BigDecimal breadth = BigDecimal.valueOf(rising - falling).divide(BigDecimal.valueOf(valid.size()), 6, RoundingMode.HALF_UP);
        int score = BigDecimal.valueOf(50).add(breadth.multiply(new BigDecimal("50")))
                .add(averageChange.multiply(new BigDecimal("2"))).max(BigDecimal.ZERO).min(new BigDecimal("100"))
                .setScale(0, RoundingMode.HALF_UP).intValue();
        String regime = score >= 65 ? "BULL" : score <= 35 ? "BEAR" : "NORMAL";
        BigDecimal adjustment = regime.equals("BULL") ? new BigDecimal("5") : regime.equals("BEAR") ? new BigDecimal("-10") : BigDecimal.ZERO;
        List<String> highlights = new ArrayList<>();
        highlights.add("上涨 " + rising + " 只 / 下跌 " + falling + " 只 / 平盘 " + flat + " 只");
        highlights.add("涨停约 " + limitUp + " 只 / 跌停约 " + limitDown + " 只");
        highlights.add("平均涨跌 " + averageChange + "%，市场环境调整 " + adjustment + " 分");

        int positive = (int) news.stream().filter(this::isPositive).count();
        int negative = (int) news.stream().filter(this::isNegative).count();
        boolean newsAvailable = !news.isEmpty();
        BigDecimal newsAdjustment = newsAvailable && negative > positive
                ? BigDecimal.valueOf(-Math.min(5, negative - positive)) : BigDecimal.ZERO;
        long aiAnalyzed = news.stream().filter(newsItem -> newsItem.aiSummary() != null && !newsItem.aiSummary().isBlank()).count();
        String aiSummary = news.stream().filter(newsItem -> newsItem.aiSummary() != null && !newsItem.aiSummary().isBlank())
                .limit(2).map(NewsHotspotStore.News::aiSummary).reduce((left, right) -> left + "；" + right).orElse("");
        String newsSummary = !newsAvailable ? "近3日未获取到新闻热点（已自动刷新新闻源）"
                : "近3日利好 " + positive + " 条 / 利空 " + negative + " 条，AI已分析 " + aiAnalyzed + " 条，仅作为风险提示";
        highlights.add(newsSummary + "，新闻调整 " + newsAdjustment + " 分");
        if (!aiSummary.isBlank()) highlights.add("AI热点摘要：" + aiSummary);
        return new MarketContext(true, score, regime, rising, falling, flat, limitUp, limitDown, averageChange,
                adjustment, newsAvailable, positive, negative, newsSummary, newsAdjustment, highlights);
    }

    /** Scheduled ingestion is the normal path; an empty cache triggers request-time retrieval. */
    public List<NewsHotspotStore.News> recentNews() {
        TimedNews cached = recentNewsCache;
        if (cached != null && !cached.items().isEmpty()
                && System.currentTimeMillis() - cached.loadedAtMillis() < 30_000L) return cached.items();
        List<NewsHotspotStore.News> items = List.copyOf(newsHotspotStore.recent(200));
        if (items.isEmpty()) {
            // Do not turn a cold cache into a user-facing "no news" result
            // before trying the configured online feeds for this request.
            newsIngestionService.ensureNewsAvailable();
            items = List.copyOf(newsHotspotStore.recent(200));
        }
        recentNewsCache = new TimedNews(System.currentTimeMillis(), items);
        return items;
    }

    /** Replaces only the news part of a previously stored recommendation context. */
    public MarketContext refreshNewsContext(MarketContext current) {
        if (current == null) return null;
        List<NewsHotspotStore.News> news = recentNews();
        if (news.isEmpty()) return current;
        int positive = (int) news.stream().filter(this::isPositive).count();
        int negative = (int) news.stream().filter(this::isNegative).count();
        BigDecimal newsAdjustment = negative > positive
                ? BigDecimal.valueOf(-Math.min(5, negative - positive)) : BigDecimal.ZERO;
        long aiAnalyzed = news.stream().filter(item -> item.aiSummary() != null && !item.aiSummary().isBlank()).count();
        String summary = "近3日利好 " + positive + " 条 / 利空 " + negative + " 条，AI已分析 "
                + aiAnalyzed + " 条，仅作为风险提示";
        List<String> highlights = new ArrayList<>();
        if (current.highlights() != null) highlights.addAll(current.highlights().stream().limit(3).toList());
        highlights.add(summary + "，新闻调整 " + newsAdjustment + " 分");
        news.stream().filter(item -> item.aiSummary() != null && !item.aiSummary().isBlank())
                .limit(2).map(NewsHotspotStore.News::aiSummary)
                .reduce((left, right) -> left + "；" + right)
                .ifPresent(value -> highlights.add("AI热点摘要：" + value));
        return new MarketContext(current.sentimentAvailable(), current.sentimentScore(), current.regime(),
                current.risingCount(), current.fallingCount(), current.flatCount(), current.limitUpCount(),
                current.limitDownCount(), current.averageChangePercent(), current.marketAdjustment(), true,
                positive, negative, summary, newsAdjustment, highlights);
    }

    public List<NewsHotspotStore.News> importantEvents() {
        TimedNews cached = importantEventsCache;
        if (cached != null && !cached.items().isEmpty()
                && System.currentTimeMillis() - cached.loadedAtMillis() < 60_000L) return cached.items();
        // This is another public news read path; a cold cache must trigger the
        // same request-time retrieval before returning an empty list.
        recentNews();
        List<NewsHotspotStore.News> items = List.copyOf(newsHotspotStore.importantEvents(500));
        importantEventsCache = new TimedNews(System.currentTimeMillis(), items);
        return items;
    }

    public List<NewsHotspotStore.News> importantEventsFor(StockMarket stock) {
        return new ArrayList<>(importantEvents());
    }

    public AiCompanyAnalysis analyzeCompany(StockMarket stock, List<NewsHotspotStore.News> news) {
        return aiAnalysisService.analyzeCompany(stock, news);
    }

    public AiAnalysisService.BatchCompanyAnalysis analyzeCompanies(List<StockMarket> stocks,
                                                                   List<NewsHotspotStore.News> news) {
        return aiAnalysisService.analyzeCompanies(stocks, news);
    }

    public Map<String, AiCompanyAnalysis> analyzeCompanyProfiles(List<StockMarket> stocks,
                                                                   List<NewsHotspotStore.News> news,
                                                                   java.time.LocalDate analyzedDate) {
        return aiAnalysisService.analyzeCompanyProfiles(stocks, news, analyzedDate);
    }

    public AiAnalysisService.BatchRealtimeAnalysis analyzeRealtimeCompanies(List<StockMarket> stocks,
                                                                              Map<String, AiCompanyAnalysis> profiles,
                                                                              List<NewsHotspotStore.News> news) {
        return aiAnalysisService.analyzeRealtimeCompanies(stocks, profiles, news);
    }

    /**
     * The quote endpoints do not carry announcements. Attach the latest actionable
     * announcement here so both recommendations and the managed pool use the same
     * event data. Dividend notices are promoted to a positive major event even if
     * the database row has not gone through AI classification yet.
     */
    public StockMarket attachMajorEvent(StockMarket stock, List<NewsHotspotStore.News> events) {
        if (stock == null || stock.majorEventType() != null && !stock.majorEventType().isBlank()
                || events == null || events.isEmpty()) return stock;
        NewsHotspotStore.News event = events.stream()
                .filter(item -> item.code() != null && item.code().equalsIgnoreCase(stock.code()))
                .filter(this::isMajorEvent)
                .findFirst().orElse(null);
        if (event == null) return stock;
        String text = ((event.title() == null ? "" : event.title()) + " "
                + (event.content() == null ? "" : event.content()));
        String eventType = eventType(event, text);
        String summary = event.aiSummary();
        if (summary == null || summary.isBlank()) summary = compact(event.content(), "公告已接入，请打开原文核对分红/权益登记等具体日期");
        return new StockMarket(stock.code(), stock.name(), stock.industry(), stock.price(), stock.changePercent(),
                stock.turnover(), stock.averageTurnover20(), stock.ma5(), stock.ma20(), stock.ma60(), stock.ma120(),
                stock.high20(), stock.high60(), stock.rsi14(), stock.macd(), stock.macdSignal(), stock.volumeRatio(),
                stock.roe(), stock.profitGrowth(), stock.revenueGrowth(), stock.debtRatio(), stock.netInflow(),
                stock.limitUpPrice(), stock.limitDownPrice(), stock.st(), stock.suspended(), stock.listingDays(),
                stock.lastTradingDate(), stock.board(), stock.quoteStatus(), stock.quoteTime(), eventType,
                event.title(), summary, event.publishedAt(), event.url(), stock.dataStatus());
    }

    public StockContext evaluateStock(StockMarket stock, List<NewsHotspotStore.News> allNews) {
        return evaluateStock(stock, allNews, true);
    }

    public StockContext evaluateStock(StockMarket stock, List<NewsHotspotStore.News> allNews, boolean includePriceAdvice) {
        // 页面和实时规则计算不再调用 AI；AI 价格建议由每小时批处理写入缓存后传入本方法。
        AiTradeAdvice priceAdvice = AiTradeAdvice.unavailable();
        return evaluateStock(stock, allNews, priceAdvice);
    }

    public StockContext evaluateStock(StockMarket stock, List<NewsHotspotStore.News> allNews,
                                      AiTradeAdvice priceAdvice) {
        AiTradeAdvice effectivePriceAdvice = aiAnalysisService.ensureTradeAdvice(stock, priceAdvice);
        List<NewsHotspotStore.News> sourceNews = allNews == null || allNews.isEmpty() ? recentNews() : allNews;
        if (stock == null || stock.price() == null || stock.changePercent() == null) {
            return new StockContext(false, 0, "UNKNOWN", BigDecimal.ZERO, false, 0, 0,
                    "个股新闻暂缺", List.of("个股实时行情不可用，不判断个股情绪"), false, 0,
                    "新闻AI未返回，已使用规则兜底",
                    effectivePriceAdvice);
        }
        int score = 50 + stock.changePercent().multiply(new BigDecimal("5")).setScale(0, RoundingMode.HALF_UP).intValue();
        if (stock.ma20() != null) score += stock.price().compareTo(stock.ma20()) >= 0 ? 10 : -10;
        if (stock.ma60() != null) score += stock.price().compareTo(stock.ma60()) >= 0 ? 5 : -5;
        score = Math.max(0, Math.min(100, score));
        String level = score >= 70 ? "偏强" : score <= 30 ? "偏弱" : "中性";
        BigDecimal adjustment = score >= 70 ? new BigDecimal("3") : score <= 30 ? new BigDecimal("-5") : BigDecimal.ZERO;
        List<NewsHotspotStore.News> news = stockNews(stock, sourceNews);
        if (news.isEmpty() && stock.code() != null && !stock.code().isBlank()) {
            // Some feeds contain the company name but omit the stock code, and
            // some only expose stale market headlines. Refresh once, then retry
            // matching by both code and name before reporting no stock news.
            newsIngestionService.refreshOnDemand();
            recentNewsCache = null;
            importantEventsCache = null;
            news = stockNews(stock, recentNews());
        }
        priceAdvice = effectivePriceAdvice;
        int positive = (int) news.stream().filter(this::isPositive).count();
        int negative = (int) news.stream().filter(this::isNegative).count();
        boolean newsAvailable = !news.isEmpty();
        BigDecimal newsAdjustment = newsAvailable && negative > positive
                ? BigDecimal.valueOf(-Math.min(5, negative - positive)) : BigDecimal.ZERO;
        List<NewsHotspotStore.News> aiNews = news.stream()
                .filter(item -> item.aiSummary() != null && !item.aiSummary().isBlank() && item.sentiment() != null)
                .toList();
        boolean aiScoreAvailable = !aiNews.isEmpty();
        int aiScore = 0;
        if (aiScoreAvailable) {
            BigDecimal averageSentiment = aiNews.stream().map(NewsHotspotStore.News::sentiment)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(aiNews.size()), 6, RoundingMode.HALF_UP);
            aiScore = BigDecimal.valueOf(5).add(averageSentiment.multiply(BigDecimal.valueOf(5)))
                    .max(BigDecimal.ZERO).min(BigDecimal.TEN)
                    .setScale(0, RoundingMode.HALF_UP).intValue();
        }
        String aiAdvice = !aiScoreAvailable ? "新闻AI本轮未返回，已使用规则情绪判断，仅作辅助参考"
                : aiScore >= 7 ? "AI新闻判断偏正面，仅作辅助参考"
                : aiScore <= 3 ? "AI新闻判断偏负面，注意风险"
                : "AI新闻判断中性，等待技术确认";
        String aiSummary = news.stream().filter(item -> item.aiSummary() != null && !item.aiSummary().isBlank())
                .limit(2).map(NewsHotspotStore.News::aiSummary).reduce((left, right) -> left + "；" + right).orElse("");
        String summary = !newsAvailable ? "近3日未匹配到该股新闻（已实时查询）"
                : "近3日该股利好 " + positive + " 条 / 利空 " + negative + " 条";
        if (!aiSummary.isBlank()) summary += "；AI摘要：" + aiSummary;
        List<String> highlights = new ArrayList<>();
        highlights.add("个股情绪 " + score + "/100（" + level + "），调整 " + adjustment + " 分");
        highlights.add(summary + "，新闻调整 " + newsAdjustment + " 分");
        highlights.add("AI建议分 " + (aiScoreAvailable ? aiScore + "/10" : "规则兜底") + "：" + aiAdvice);
        highlights.add("AI价格辅助建议：" + priceAdvice.bandAdvice());
        return new StockContext(true, score, level, adjustment.add(newsAdjustment), newsAvailable, positive, negative,
                summary, highlights, aiScoreAvailable, aiScore, aiAdvice, priceAdvice);
    }

    private boolean isPositive(NewsHotspotStore.News news) {
        return "利好".equals(news.eventType()) || news.sentiment() != null && news.sentiment().compareTo(BigDecimal.ZERO) > 0;
    }

    private List<NewsHotspotStore.News> stockNews(StockMarket stock, List<NewsHotspotStore.News> source) {
        if (stock == null || source == null || source.isEmpty()) return List.of();
        String code = stock.code() == null ? "" : stock.code().trim();
        String name = stock.name() == null ? "" : stock.name().trim();
        return source.stream().filter(item -> {
            if (!code.isBlank() && item.code() != null && code.equalsIgnoreCase(item.code().trim())) return true;
            if (name.length() < 2) return false;
            String text = (item.title() == null ? "" : item.title()) + " "
                    + (item.content() == null ? "" : item.content());
            return text.contains(name);
        }).toList();
    }

    private boolean isNegative(NewsHotspotStore.News news) {
        return "利空".equals(news.eventType()) || news.sentiment() != null && news.sentiment().compareTo(BigDecimal.ZERO) < 0;
    }

    private boolean isMajorEvent(NewsHotspotStore.News news) {
        String text = ((news.title() == null ? "" : news.title()) + " "
                + (news.content() == null ? "" : news.content()));
        return "利好".equals(news.eventType()) || "利空".equals(news.eventType())
                || text.matches(".*(" + DIVIDEND_KEYWORDS + "|" + POSITIVE_EVENT_KEYWORDS + "|"
                + NEGATIVE_EVENT_KEYWORDS + ").*");
    }

    private String eventType(NewsHotspotStore.News event, String text) {
        if ("利好".equals(event.eventType()) || "利空".equals(event.eventType())) return event.eventType();
        if (text.matches(".*(" + DIVIDEND_KEYWORDS + "|" + POSITIVE_EVENT_KEYWORDS + ").*")) return "利好";
        if (text.matches(".*(" + NEGATIVE_EVENT_KEYWORDS + ").*")) return "利空";
        return "中性";
    }

    private String compact(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 180 ? compact : compact.substring(0, 180) + "…";
    }

    private record TimedNews(long loadedAtMillis, List<NewsHotspotStore.News> items) {}
}
