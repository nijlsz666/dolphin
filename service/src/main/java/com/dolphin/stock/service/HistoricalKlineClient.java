package com.dolphin.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dolphin.stock.model.StockAnalysisModels.PriceHistoryPoint;
import com.dolphin.stock.model.StockAnalysisModels.StockMarket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HistoricalKlineClient {
    private static final Logger log = LoggerFactory.getLogger(HistoricalKlineClient.class);
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String endpoint;
    private final Duration timeout;
    private final long cacheTtlMillis;
    private final Map<String, CachedBars> cache = new ConcurrentHashMap<>();

    public HistoricalKlineClient(ObjectMapper objectMapper,
                                 @Value("${market-data.kline-endpoint:https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=}") String endpoint,
                                 @Value("${market-data.kline-timeout-seconds:3}") long timeoutSeconds,
                                 @Value("${market-data.kline-cache-seconds:300}") long cacheSeconds) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.cacheTtlMillis = Math.max(30_000L, cacheSeconds * 1000L);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).version(HttpClient.Version.HTTP_1_1).build();
    }

    public StockMarket enrich(StockMarket stock) {
        try {
            List<Bar> bars = fetch(stock.code());
            if (bars.size() < 120) {
                log.warn("{} 历史K线不足120个交易日，不生成技术指标", stock.code());
                return stock;
            }
            return withTechnicalData(stock, bars);
        } catch (Exception ex) {
            log.warn("{} 历史K线获取失败，不使用占位指标: {}", stock.code(), ex.getMessage());
            return stock;
        }
    }

    public List<PriceHistoryPoint> recentHistory(String code) {
        try {
            List<Bar> bars = fetch(code);
            int from = Math.max(0, bars.size() - 31);
            return bars.subList(from, bars.size()).stream()
                    .map(bar -> new PriceHistoryPoint(bar.date(), bar.close()))
                    .toList();
        } catch (Exception ex) {
            log.warn("{} 最近一个月历史行情获取失败: {}", code, ex.getMessage());
            return List.of();
        }
    }

    private List<Bar> fetch(String code) throws Exception {
        CachedBars cached = cache.get(code);
        if (cached != null && System.currentTimeMillis() - cached.loadedAtMillis() < cacheTtlMillis) {
            return cached.bars();
        }
        String symbol = (code != null && code.startsWith("6") ? "sh" : "sz") + code;
        String url = endpoint + symbol + ",day,,,320,qfq";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0 DolphinStock/1.0")
                .header("Referer", "https://gu.qq.com/")
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        JsonNode rows = objectMapper.readTree(response.body()).path("data").path(symbol).path("qfqday");
        if (!rows.isArray()) return List.of();
        List<Bar> bars = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 6) continue;
            LocalDate date = date(row.get(0));
            BigDecimal open = decimal(row.get(1));
            BigDecimal close = decimal(row.get(2));
            BigDecimal high = decimal(row.get(3));
            BigDecimal low = decimal(row.get(4));
            BigDecimal volume = decimal(row.get(5));
            if (date != null && close != null && high != null && low != null && volume != null) {
                bars.add(new Bar(date, close, high, low, volume));
            }
        }
        List<Bar> immutable = List.copyOf(bars);
        cache.put(code, new CachedBars(System.currentTimeMillis(), immutable));
        return immutable;
    }

    private StockMarket withTechnicalData(StockMarket stock, List<Bar> bars) {
        BigDecimal close = bars.get(bars.size() - 1).close();
        BigDecimal previousClose = bars.get(bars.size() - 2).close();
        BigDecimal turnover = close.multiply(bars.get(bars.size() - 1).volume()).multiply(new BigDecimal("100"));
        BigDecimal averageTurnover20 = averageTurnover(bars, 20);
        BigDecimal limitUp = previousClose.multiply(new BigDecimal("1.10")).setScale(3, RoundingMode.HALF_UP);
        BigDecimal limitDown = previousClose.multiply(new BigDecimal("0.90")).setScale(3, RoundingMode.HALF_UP);
        return new StockMarket(stock.code(), stock.name(), stock.industry(), stock.price(), stock.changePercent(),
                turnover, averageTurnover20, averageClose(bars, 5), averageClose(bars, 20), averageClose(bars, 60),
                averageClose(bars, 120), highest(bars, 20), highest(bars, 60), rsi(bars, 14), macd(bars),
                macdSignal(bars), volumeRatio(bars, 20), null, null, null, null, null, limitUp, limitDown,
                stock.st(), stock.suspended(), stock.listingDays(), stock.lastTradingDate(), stock.board(),
                stock.quoteStatus(), stock.quoteTime(), stock.majorEventType(), stock.majorEventTitle(),
                stock.majorEventSummary(), stock.majorEventTime(), stock.majorEventUrl(), "TECHNICAL_ONLY");
    }

    private BigDecimal averageClose(List<Bar> bars, int period) {
        return bars.subList(bars.size() - period, bars.size()).stream().map(Bar::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal averageTurnover(List<Bar> bars, int period) {
        return bars.subList(bars.size() - period, bars.size()).stream()
                .map(bar -> bar.close().multiply(bar.volume()).multiply(new BigDecimal("100")))
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal highest(List<Bar> bars, int period) {
        return bars.subList(bars.size() - period, bars.size()).stream().map(Bar::high).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private BigDecimal volumeRatio(List<Bar> bars, int period) {
        BigDecimal current = bars.get(bars.size() - 1).volume();
        BigDecimal average = bars.subList(bars.size() - period - 1, bars.size() - 1).stream().map(Bar::volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
        return average.signum() == 0 ? BigDecimal.ONE : current.divide(average, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal rsi(List<Bar> bars, int period) {
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            BigDecimal change = bars.get(i).close().subtract(bars.get(i - 1).close());
            if (change.signum() > 0) gains = gains.add(change);
            else losses = losses.add(change.abs());
        }
        if (losses.signum() == 0) return new BigDecimal("100");
        BigDecimal rs = gains.divide(losses, 8, RoundingMode.HALF_UP);
        return new BigDecimal("100").subtract(new BigDecimal("100").divide(BigDecimal.ONE.add(rs), 4, RoundingMode.HALF_UP));
    }

    private BigDecimal macd(List<Bar> bars) {
        return ema(bars, 12).subtract(ema(bars, 26));
    }

    private BigDecimal macdSignal(List<Bar> bars) {
        List<BigDecimal> values = new ArrayList<>();
        for (int end = 26; end <= bars.size(); end++) {
            List<Bar> prefix = bars.subList(0, end);
            values.add(ema(prefix, 12).subtract(ema(prefix, 26)));
        }
        return emaValues(values, 9);
    }

    private BigDecimal ema(List<Bar> bars, int period) {
        List<BigDecimal> closes = bars.stream().map(Bar::close).toList();
        return emaValues(closes, period);
    }

    private BigDecimal emaValues(List<BigDecimal> values, int period) {
        BigDecimal alpha = BigDecimal.valueOf(2.0 / (period + 1));
        BigDecimal ema = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            ema = values.get(i).subtract(ema).multiply(alpha).add(ema);
        }
        return ema;
    }

    private BigDecimal decimal(JsonNode value) {
        try {
            return value == null || value.isNull() ? null : new BigDecimal(value.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDate date(JsonNode value) {
        try {
            return value == null || value.isNull() ? null : LocalDate.parse(value.asText());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private record Bar(LocalDate date, BigDecimal close, BigDecimal high, BigDecimal low, BigDecimal volume) {}
    private record CachedBars(long loadedAtMillis, List<Bar> bars) {}
}
