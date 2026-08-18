package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.StockMarket;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class DemoMarketDataProvider {
    private final RealtimeQuoteClient realtimeQuoteClient;
    private final HistoricalKlineClient historicalKlineClient;

    public DemoMarketDataProvider(RealtimeQuoteClient realtimeQuoteClient, HistoricalKlineClient historicalKlineClient) {
        this.realtimeQuoteClient = realtimeQuoteClient;
        this.historicalKlineClient = historicalKlineClient;
    }

    public List<StockMarket> latest(LocalDate asOf) {
        List<StockMarket> stocks = new ArrayList<>(List.of(
                stock("600519", "贵州茅台", "消费", 1538, 1.84, 920000000, 760000000, 1510, 1480, 1430, 1390, 1560, 1610, 58, 1.20, 0.80, 1.16, 23, 12, 8, 28, 68000000, 1691, 1384, false, false, 6000, asOf),
                stock("300750", "宁德时代", "新能源", 214.5, 3.12, 4800000000L, 4200000000L, 207, 195, 184, 176, 218, 230, 64, 2.40, 1.30, 1.35, 18, 26, 19, 47, 350000000, 236, 193, false, false, 2500, asOf),
                stock("000858", "五粮液", "消费", 142.8, 0.42, 1100000000L, 1300000000L, 144, 146, 149, 151, 153, 158, 48, -0.20, 0.20, 0.86, 16, -3, 4, 36, -22000000, 157, 128, false, false, 7000, asOf),
                stock("601318", "中国平安", "金融", 48.3, 2.06, 2100000000L, 1800000000L, 46.8, 45.1, 43.2, 41.7, 48.8, 50.3, 55, 0.85, 0.45, 1.21, 11, 18, 9, 65, 125000000, 53, 43, false, false, 6800, asOf),
                stock("002594", "比亚迪", "新能源", 248.2, -1.50, 3300000000L, 3500000000L, 252, 258, 241, 226, 273, 285, 72, 3.10, 1.20, 0.92, 17, 31, 24, 52, -180000000, 273, 223, false, false, 5200, asOf),
                stock("688001", "华兴科技", "科技", 89.2, 0.88, 150000000, 90000000, 87, 82, 76, 71, 92, 101, 61, 1.70, 0.90, 1.42, 12, 35, 27, 42, 18000000, 98, 80, false, false, 1700, asOf),
                stock("600000", "浦发银行", "金融", 8.2, -0.25, 230000000, 320000000, 8.3, 8.5, 8.7, 8.9, 9.0, 9.2, 41, -0.15, 0.15, 0.72, 5, -8, -2, 75, -12000000, 9.0, 7.4, false, false, 9000, asOf),
                stock("300001", "ST风险样本", "科技", 12.6, 4.95, 80000000, 70000000, 11.9, 10.8, 9.5, 8.7, 12.8, 13.0, 79, 2.90, 1.10, 1.90, -4, 88, 45, 82, 50000000, 13.86, 11.34, true, false, 1500, asOf)
        ));
        stocks.addAll(List.of(
                supportingStock("600036", "招商银行", "金融", 38.6, 1.26, 41, 48, 54, 57, 13, 9, 85000000, null, null, null, asOf),
                supportingStock("600276", "恒瑞医药", "医药", 52.4, 2.18, 50, 58, 64, 62, 16, 22, 120000000, "利好", "重大利好：创新药研发进展", "研发管线获得阶段性进展，关注公告落地和成交量确认。", asOf),
                supportingStock("000333", "美的集团", "家电", 68.2, 0.74, 66, 73, 79, 57, 18, 12, 68000000, null, null, null, asOf),
                supportingStock("000651", "格力电器", "家电", 41.7, -0.35, 43, 48, 54, 49, 15, 6, -18000000, null, null, null, asOf),
                supportingStock("601088", "中国神华", "能源", 42.8, 1.02, 41, 47, 52, 55, 19, 15, 96000000, null, null, null, asOf),
                supportingStock("601899", "紫金矿业", "有色", 18.9, -2.34, 20, 24, 27, 68, 14, 18, -160000000, "利空", "重大利空：海外项目监管不确定性", "海外项目面临监管变量，短期波动和回撤风险上升。", asOf),
                supportingStock("600030", "中信证券", "金融", 25.6, 1.88, 25, 29, 33, 59, 12, 16, 110000000, "利好", "重大利好：资本市场政策预期改善", "行业政策预期偏暖，但仍需观察成交量持续性。", asOf),
                supportingStock("000063", "中兴通讯", "通信", 29.8, 2.56, 28, 34, 39, 63, 11, 20, 145000000, null, null, null, asOf),
                supportingStock("600089", "特变电工", "电气设备", 23.5, 0.62, 22, 27, 31, 54, 17, 13, 52000000, null, null, null, asOf),
                supportingStock("002415", "海康威视", "安防", 31.4, -1.82, 33, 37, 42, 66, 15, 4, -90000000, "利空", "重大利空：订单与利润预期下修", "短期盈利预期承压，反弹需等待基本面和量价重新确认。", asOf),
                supportingStock("600309", "万华化学", "化工", 78.6, 1.44, 75, 86, 95, 58, 21, 18, 132000000, null, null, null, asOf),
                supportingStock("601012", "隆基绿能", "光伏", 18.2, -1.06, 20, 23, 26, 61, 8, -12, -76000000, "利空", "重大利空：行业产能压力", "行业供需和价格竞争仍有压力，暂不适合追涨。", asOf),
                supportingStock("000100", "TCL科技", "电子", 4.68, 0.86, 4.5, 5.3, 5.9, 56, 9, 15, 45000000, null, null, null, asOf),
                supportingStock("600031", "三一重工", "工程机械", 17.4, 1.37, 17, 20, 23, 57, 12, 19, 78000000, null, null, null, asOf),
                supportingStock("000725", "京东方A", "电子", 4.12, 0.24, 4.0, 4.6, 5.1, 52, 7, 3, 32000000, null, null, null, asOf),
                supportingStock("002352", "顺丰控股", "物流", 38.1, 1.12, 37, 43, 48, 60, 13, 14, 87000000, null, null, null, asOf)
        ));
        return realtimeQuoteClient.enrich(stocks);
    }

    public Optional<StockMarket> find(LocalDate asOf, String code) {
        return latest(asOf).stream().filter(stock -> stock.code().equalsIgnoreCase(code)).findFirst();
    }

    public StockMarket manualPlaceholder(LocalDate asOf, String code, String name, String industry) {
        StockMarket base = stock(code, name == null || name.isBlank() ? "手工股票" : name,
                industry == null || industry.isBlank() ? "未分类" : industry,
                30, 0, 300000000, 250000000, 30, 30, 29, 28, 33, 36, 50,
                0.1, 0.1, 1.0, 10, 0, 0, 50, 0, 33, 27, false, false, 1000, asOf);
        base = withDataStatus(base, "PRICE_ONLY");
        // 手工股票不在本地基础样本列表中，也必须尝试联网获取真实价格。
        // 行情源全部失败时 enrich() 才会返回 NETWORK_ERROR 和空价格。
        StockMarket quoted = realtimeQuoteClient.enrich(List.of(base)).get(0);
        return quoted.price() == null ? quoted : historicalKlineClient.enrich(quoted);
    }

    private StockMarket stock(String code, String name, String industry, double price, double change,
                              double turnover, double avgTurnover, double ma5, double ma20, double ma60,
                              double ma120, double high20, double high60, double rsi, double macd,
                              double signal, double volumeRatio, double roe, double profitGrowth,
                              double revenueGrowth, double debtRatio, double inflow, double limitUp,
                              double limitDown, boolean st, boolean suspended, int listingDays, LocalDate date) {
        return new StockMarket(code, name, industry, bd(price), bd(change), bd(turnover), bd(avgTurnover),
                bd(ma5), bd(ma20), bd(ma60), bd(ma120), bd(high20), bd(high60), bd(rsi), bd(macd), bd(signal),
                bd(volumeRatio), bd(roe), bd(profitGrowth), bd(revenueGrowth), bd(debtRatio), bd(inflow),
                bd(limitUp), bd(limitDown), st, suspended, listingDays, date, boardFor(code), "NETWORK_ERROR", null, null, null, null, null, null, "FULL");
    }

    private StockMarket supportingStock(String code, String name, String industry, double price, double change,
                                         double ma20, double high20, double high60, double rsi, double roe,
                                         double profitGrowth, double inflow, String eventType, String eventTitle,
                                         String eventSummary, LocalDate date) {
        return withEvent(stock(code, name, industry, price, change, 520000000, 360000000,
                ma20 * 1.02, ma20, ma20 * 0.95, ma20 * 0.90, high20, high60, rsi,
                1.20, 0.70, 1.22, roe, profitGrowth, profitGrowth * 0.8, 48, inflow,
                price * 1.10, price * 0.90, false, false, 3000, date), eventType, eventTitle, eventSummary);
    }

    private StockMarket withEvent(StockMarket stock, String type, String title, String summary) {
        return new StockMarket(stock.code(), stock.name(), stock.industry(), stock.price(), stock.changePercent(),
                stock.turnover(), stock.averageTurnover20(), stock.ma5(), stock.ma20(), stock.ma60(), stock.ma120(),
                stock.high20(), stock.high60(), stock.rsi14(), stock.macd(), stock.macdSignal(), stock.volumeRatio(),
                stock.roe(), stock.profitGrowth(), stock.revenueGrowth(), stock.debtRatio(), stock.netInflow(),
                stock.limitUpPrice(), stock.limitDownPrice(), stock.st(), stock.suspended(), stock.listingDays(),
                stock.lastTradingDate(), stock.board(), stock.quoteStatus(), stock.quoteTime(), type, title, summary, null, null, stock.dataStatus());
    }

    private StockMarket withDataStatus(StockMarket stock, String status) {
        return new StockMarket(stock.code(), stock.name(), stock.industry(), stock.price(), stock.changePercent(),
                stock.turnover(), stock.averageTurnover20(), stock.ma5(), stock.ma20(), stock.ma60(), stock.ma120(),
                stock.high20(), stock.high60(), stock.rsi14(), stock.macd(), stock.macdSignal(), stock.volumeRatio(),
                stock.roe(), stock.profitGrowth(), stock.revenueGrowth(), stock.debtRatio(), stock.netInflow(),
                stock.limitUpPrice(), stock.limitDownPrice(), stock.st(), stock.suspended(), stock.listingDays(),
                stock.lastTradingDate(), stock.board(), stock.quoteStatus(), stock.quoteTime(), stock.majorEventType(),
                stock.majorEventTitle(), stock.majorEventSummary(), stock.majorEventTime(), stock.majorEventUrl(), status);
    }

    private String boardFor(String code) {
        if (code != null && code.matches("60[0135]\\d{3}")) return "上海主板";
        if (code != null && code.matches("00[0123]\\d{3}")) return "深圳主板";
        if (code != null && code.matches("68\\d{4}")) return "科创板";
        if (code != null && code.matches("30\\d{4}")) return "创业板";
        return "其他板块";
    }

    private BigDecimal bd(double value) { return BigDecimal.valueOf(value); }
    private BigDecimal bd(long value) { return BigDecimal.valueOf(value); }
}
