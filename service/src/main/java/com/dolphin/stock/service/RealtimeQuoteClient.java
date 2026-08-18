package com.dolphin.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dolphin.stock.model.StockAnalysisModels.MarketDataSourceConfig;
import com.dolphin.stock.model.StockAnalysisModels.MarketIndexQuote;
import com.dolphin.stock.model.StockAnalysisModels.StockMarket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RealtimeQuoteClient {
    private static final Logger log = LoggerFactory.getLogger(RealtimeQuoteClient.class);
    private static final Pattern TENCENT_QUOTE = Pattern.compile("v_([a-z]{2}\\d{6})=\\\"([^\\\"]*)\\\"");
    private static final Pattern SINA_QUOTE = Pattern.compile("hq_str_([a-z]{2})(\\d{6})=\\\"([^\\\"]*)\\\"");
    private static final DateTimeFormatter TENCENT_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final ObjectMapper objectMapper;
    private final MarketDataSourceStore sourceStore;
    private final boolean globallyEnabled;
    private final HttpClient httpClient;
    private final long indicesCacheTtlMillis;
    private volatile CachedIndices cachedIndices;

    public RealtimeQuoteClient(ObjectMapper objectMapper, MarketDataSourceStore sourceStore,
                               @Value("${market-data.realtime-enabled:true}") boolean globallyEnabled,
                               @Value("${market-data.indices-cache-seconds:30}") long indicesCacheSeconds) {
        this.objectMapper = objectMapper;
        this.sourceStore = sourceStore;
        this.globallyEnabled = globallyEnabled;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();
        this.indicesCacheTtlMillis = Math.max(5_000L, indicesCacheSeconds * 1000L);
    }

    public List<StockMarket> enrich(List<StockMarket> stocks) {
        if (stocks.isEmpty()) return stocks;
        if (!globallyEnabled) return stocks.stream().map(this::markUnavailable).toList();
        Map<String, LiveQuote> quotes = new HashMap<>();
        for (MarketDataSourceConfig source : sourceStore.active("REALTIME")) {
            try {
                fetchWithRetry(source, () -> fetchRealtime(source, stocks)).forEach(quotes::putIfAbsent);
                log.info("实时行情源 {} 获取 {} / {} 只", source.name(), quotes.size(), stocks.size());
                if (quotes.size() >= stocks.size()) break;
            } catch (Exception ex) {
                log.warn("实时行情源 {} 获取失败，切换下一个源: {}", source.name(), ex.getMessage());
            }
        }
        Map<String, LiveQuote> finalQuotes = quotes;
        return stocks.stream().map(stock -> {
            LiveQuote quote = finalQuotes.get(stock.code());
            return quote == null ? markUnavailable(stock) : withQuote(stock, quote);
        }).toList();
    }

    public List<MarketIndexQuote> fetchIndices() {
        CachedIndices current = cachedIndices;
        if (current != null && System.currentTimeMillis() - current.loadedAtMillis() < indicesCacheTtlMillis) {
            return current.indices();
        }
        Map<String, IndexLiveQuote> quotes = new HashMap<>();
        if (globallyEnabled) {
            for (MarketDataSourceConfig source : sourceStore.active("REALTIME")) {
                try {
                    fetchWithRetry(source, () -> fetchIndices(source)).forEach(quotes::putIfAbsent);
                    if (quotes.size() >= 2) break;
                } catch (Exception ex) {
                    log.warn("指数行情源 {} 获取失败，切换下一个源: {}", source.name(), ex.getMessage());
                }
            }
        }
        List<MarketIndexQuote> result = List.of(indexQuote("000001", "上证指数", quotes.get("000001")),
                indexQuote("399001", "深证成指", quotes.get("399001")));
        if (!quotes.isEmpty()) cachedIndices = new CachedIndices(System.currentTimeMillis(), result);
        return result;
    }

    private Map<String, LiveQuote> fetchRealtime(MarketDataSourceConfig source, List<StockMarket> stocks) throws Exception {
        return switch (source.adapter().toUpperCase()) {
            case "EASTMONEY" -> fetchEastMoney(source, stocks);
            case "TENCENT" -> fetchTencent(source, stocks);
            case "SINA" -> fetchSina(source, stocks);
            default -> throw new IllegalArgumentException("不支持的行情适配器: " + source.adapter());
        };
    }

    private Map<String, IndexLiveQuote> fetchIndices(MarketDataSourceConfig source) throws Exception {
        return switch (source.adapter().toUpperCase()) {
            case "EASTMONEY" -> fetchIndicesEastMoney(source);
            case "TENCENT" -> fetchIndicesTencent(source);
            case "SINA" -> fetchIndicesSina(source);
            default -> throw new IllegalArgumentException("不支持的行情适配器: " + source.adapter());
        };
    }

    private Map<String, LiveQuote> fetchEastMoney(MarketDataSourceConfig source, List<StockMarket> stocks) throws Exception {
        String secids = stocks.stream().map(stock -> marketId(stock.code()) + "." + stock.code()).reduce((a, b) -> a + "," + b).orElse("");
        String url = query(source.endpoint(), "fltt=2&invt=2&fields=f2,f3,f4,f12,f13,f14,f124&secids=" + secids);
        JsonNode diff = objectMapper.readTree(send(source, url, false)).path("data").path("diff");
        Map<String, LiveQuote> result = new HashMap<>();
        if (!diff.isArray()) return result;
        for (JsonNode item : diff) {
            String code = item.path("f12").asText("");
            BigDecimal price = decimal(item, "f2");
            if (!code.isBlank() && price != null && price.signum() >= 0) {
                long timestamp = item.path("f124").asLong(0);
                LocalDateTime time = timestamp > 0 ? LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.of("Asia/Shanghai")) : LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
                result.put(code, new LiveQuote(price, decimal(item, "f3"), time));
            }
        }
        return result;
    }

    private Map<String, IndexLiveQuote> fetchIndicesEastMoney(MarketDataSourceConfig source) throws Exception {
        String url = query(source.endpoint(), "fltt=2&invt=2&fields=f2,f3,f4,f12,f14,f124&secids=1.000001,0.399001");
        JsonNode diff = objectMapper.readTree(send(source, url, false)).path("data").path("diff");
        Map<String, IndexLiveQuote> result = new HashMap<>();
        if (!diff.isArray()) return result;
        for (JsonNode item : diff) {
            String code = item.path("f12").asText("");
            BigDecimal price = decimal(item, "f2");
            BigDecimal changePercent = decimal(item, "f3");
            if (!code.isBlank() && price != null && changePercent != null) {
                long timestamp = item.path("f124").asLong(0);
                LocalDateTime time = timestamp > 0 ? LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.of("Asia/Shanghai")) : LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
                result.put(code, new IndexLiveQuote(price, decimal(item, "f4"), changePercent, time));
            }
        }
        return result;
    }

    private Map<String, LiveQuote> fetchTencent(MarketDataSourceConfig source, List<StockMarket> stocks) throws Exception {
        String symbols = stocks.stream().map(stock -> marketPrefix(stock.code()) + stock.code()).reduce((a, b) -> a + "," + b).orElse("");
        return parseTencent(send(source, source.endpoint() + symbols, true));
    }

    private Map<String, IndexLiveQuote> fetchIndicesTencent(MarketDataSourceConfig source) throws Exception {
        String body = send(source, source.endpoint() + "sh000001,sz399001", true);
        Map<String, IndexLiveQuote> result = new HashMap<>();
        Matcher matcher = TENCENT_QUOTE.matcher(body);
        while (matcher.find()) {
            String symbol = matcher.group(1);
            String[] fields = matcher.group(2).split("~", -1);
            if (fields.length <= 32) continue;
            String code = symbol.endsWith("000001") ? "000001" : symbol.endsWith("399001") ? "399001" : "";
            BigDecimal price = decimal(fields[3]);
            BigDecimal previousClose = decimal(fields[4]);
            BigDecimal changePercent = decimal(fields[32]);
            if (!code.isBlank() && price != null && previousClose != null && changePercent != null) result.put(code, new IndexLiveQuote(price, price.subtract(previousClose), changePercent, parseTencentTime(fields[30])));
        }
        return result;
    }

    private Map<String, LiveQuote> fetchSina(MarketDataSourceConfig source, List<StockMarket> stocks) throws Exception {
        String symbols = stocks.stream().map(stock -> marketPrefix(stock.code()) + stock.code()).reduce((a, b) -> a + "," + b).orElse("");
        return parseSina(send(source, source.endpoint() + symbols, true));
    }

    private Map<String, IndexLiveQuote> fetchIndicesSina(MarketDataSourceConfig source) throws Exception {
        return parseSinaIndices(send(source, source.endpoint() + "sh000001,sz399001", true));
    }

    private Map<String, IndexLiveQuote> parseSinaIndices(String body) {
        Map<String, IndexLiveQuote> result = new HashMap<>();
        Matcher matcher = SINA_QUOTE.matcher(body == null ? "" : body);
        while (matcher.find()) {
            String code = matcher.group(2).endsWith("000001") ? "000001" : matcher.group(2).endsWith("399001") ? "399001" : "";
            String[] fields = matcher.group(3).split(",", -1);
            if (code.isBlank() || fields.length <= 31) continue;
            BigDecimal price = decimal(fields[3]);
            BigDecimal previousClose = decimal(fields[2]);
            if (price == null || previousClose == null || previousClose.signum() == 0) continue;
            BigDecimal changePercent = price.subtract(previousClose).divide(previousClose, 8, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
            LocalDateTime time;
            try { time = LocalDateTime.parse(fields[30] + " " + fields[31], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
            catch (Exception ex) { time = LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
            result.put(code, new IndexLiveQuote(price, price.subtract(previousClose), changePercent, time));
        }
        return result;
    }

    private Map<String, LiveQuote> parseTencent(String body) {
        Map<String, LiveQuote> result = new HashMap<>();
        Matcher matcher = TENCENT_QUOTE.matcher(body);
        while (matcher.find()) {
            String[] fields = matcher.group(2).split("~", -1);
            if (fields.length <= 32) continue;
            BigDecimal price = decimal(fields[3]);
            if (price != null && price.signum() > 0) result.put(fields[2], new LiveQuote(price, decimal(fields[32]), parseTencentTime(fields[30])));
        }
        return result;
    }

    private Map<String, LiveQuote> parseSina(String body) {
        Map<String, LiveQuote> result = new HashMap<>();
        Matcher matcher = SINA_QUOTE.matcher(body);
        while (matcher.find()) {
            String[] fields = matcher.group(3).split(",", -1);
            if (fields.length <= 31) continue;
            BigDecimal price = decimal(fields[3]);
            BigDecimal previousClose = decimal(fields[2]);
            if (price == null || price.signum() <= 0) continue;
            BigDecimal changePercent = previousClose == null || previousClose.signum() == 0 ? null : price.subtract(previousClose).divide(previousClose, 8, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
            LocalDateTime time;
            try { time = LocalDateTime.parse(fields[30] + " " + fields[31], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
            catch (Exception ex) { time = LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
            result.put(matcher.group(2), new LiveQuote(price, changePercent, time));
        }
        return result;
    }

    private <T> T fetchWithRetry(MarketDataSourceConfig source, SupplierWithException<T> operation) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= source.retryCount(); attempt++) {
            try { return operation.get(); }
            catch (Exception ex) {
                last = ex;
                if (attempt < source.retryCount()) Thread.sleep(150L);
            }
        }
        throw last == null ? new IllegalStateException("行情源调用失败") : last;
    }

    private String send(MarketDataSourceConfig source, String url, boolean binary) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(source.timeoutSeconds()))
                .header("User-Agent", source.userAgent());
        if (source.referer() != null && !source.referer().isBlank()) builder.header("Referer", source.referer());
        HttpResponse<?> response = binary ? httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray()) : httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return binary ? new String((byte[]) response.body(), Charset.forName("GBK")) : (String) response.body();
    }

    private String query(String endpoint, String query) { return endpoint + (endpoint.contains("?") ? "&" : "?") + query; }
    private MarketIndexQuote indexQuote(String code, String name, IndexLiveQuote quote) { return quote == null ? new MarketIndexQuote(code, name, null, null, null, "NETWORK_ERROR", null) : new MarketIndexQuote(code, name, quote.price(), quote.change(), quote.changePercent(), "REALTIME", quote.quoteTime()); }
    private StockMarket withQuote(StockMarket stock, LiveQuote quote) { return new StockMarket(stock.code(), stock.name(), stock.industry(), quote.price(), quote.changePercent(), stock.turnover(), stock.averageTurnover20(), stock.ma5(), stock.ma20(), stock.ma60(), stock.ma120(), stock.high20(), stock.high60(), stock.rsi14(), stock.macd(), stock.macdSignal(), stock.volumeRatio(), stock.roe(), stock.profitGrowth(), stock.revenueGrowth(), stock.debtRatio(), stock.netInflow(), stock.limitUpPrice(), stock.limitDownPrice(), stock.st(), stock.suspended(), stock.listingDays(), stock.lastTradingDate(), stock.board(), "REALTIME", quote.quoteTime(), stock.majorEventType(), stock.majorEventTitle(), stock.majorEventSummary(), stock.majorEventTime(), stock.majorEventUrl(), stock.dataStatus()); }
    public StockMarket markUnavailable(StockMarket stock) { return new StockMarket(stock.code(), stock.name(), stock.industry(), null, null, stock.turnover(), stock.averageTurnover20(), stock.ma5(), stock.ma20(), stock.ma60(), stock.ma120(), stock.high20(), stock.high60(), stock.rsi14(), stock.macd(), stock.macdSignal(), stock.volumeRatio(), stock.roe(), stock.profitGrowth(), stock.revenueGrowth(), stock.debtRatio(), stock.netInflow(), stock.limitUpPrice(), stock.limitDownPrice(), stock.st(), stock.suspended(), stock.listingDays(), stock.lastTradingDate(), stock.board(), "NETWORK_ERROR", null, stock.majorEventType(), stock.majorEventTitle(), stock.majorEventSummary(), stock.majorEventTime(), stock.majorEventUrl(), stock.dataStatus()); }
    private BigDecimal decimal(JsonNode item, String field) { JsonNode value = item.get(field); return value == null || value.isNull() ? null : BigDecimal.valueOf(value.asDouble()); }
    private BigDecimal decimal(String value) { try { return value == null || value.isBlank() ? null : new BigDecimal(value); } catch (NumberFormatException ex) { return null; } }
    private LocalDateTime parseTencentTime(String value) { try { return LocalDateTime.parse(value, TENCENT_TIME); } catch (Exception ex) { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); } }
    private int marketId(String code) { return code != null && code.startsWith("6") ? 1 : 0; }
    private String marketPrefix(String code) { return code != null && code.startsWith("6") ? "sh" : "sz"; }
    private record LiveQuote(BigDecimal price, BigDecimal changePercent, LocalDateTime quoteTime) {}
    private record IndexLiveQuote(BigDecimal price, BigDecimal change, BigDecimal changePercent, LocalDateTime quoteTime) {}
    private record CachedIndices(long loadedAtMillis, List<MarketIndexQuote> indices) {}
    @FunctionalInterface private interface SupplierWithException<T> { T get() throws Exception; }
}
