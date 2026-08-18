package com.dolphin.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dolphin.stock.model.StockAnalysisModels.MarketDataSourceConfig;
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
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 推荐中心使用的全市场实时行情。源地址、优先级、超时和重试均来自 market_data_source。 */
@Component
public class MarketUniverseClient {
    private static final Logger log = LoggerFactory.getLogger(MarketUniverseClient.class);
    private static final Pattern TENCENT_QUOTE = Pattern.compile("v_([a-z]{2}\\d{6})=\\\"([^\\\"]*)\\\"");
    private final ObjectMapper objectMapper;
    private final MarketDataSourceStore sourceStore;
    private final HttpClient httpClient;
    private final int fallbackBatchSize;
    private final long cacheTtlMillis;
    private volatile CachedUniverse cached;

    public MarketUniverseClient(ObjectMapper objectMapper, MarketDataSourceStore sourceStore,
                                @Value("${market-data.universe-fallback-batch-size:800}") int fallbackBatchSize,
                                @Value("${market-data.universe-cache-seconds:30}") long cacheSeconds) {
        this.objectMapper = objectMapper;
        this.sourceStore = sourceStore;
        this.fallbackBatchSize = Math.max(100, fallbackBatchSize);
        this.cacheTtlMillis = Math.max(5_000L, cacheSeconds * 1000L);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();
    }

    public List<StockMarket> fetch(LocalDate asOf) {
        CachedUniverse current = cached;
        if (current != null && System.currentTimeMillis() - current.loadedAtMillis() < cacheTtlMillis) {
            return current.stocks();
        }
        Exception last = null;
        for (MarketDataSourceConfig source : sourceStore.active("UNIVERSE")) {
            try {
                List<StockMarket> result = fetchWithRetry(source, () -> fetchSource(source, asOf));
                if (!result.isEmpty()) {
                    log.info("推荐中心通过 {} 获取沪深主板实时行情 {} 只", source.name(), result.size());
                    List<StockMarket> immutable = List.copyOf(result);
                    cached = new CachedUniverse(System.currentTimeMillis(), immutable);
                    return immutable;
                }
                last = new IllegalStateException("没有返回有效股票");
            } catch (Exception ex) {
                last = ex;
                log.warn("推荐全市场行情源 {} 获取失败，切换下一个源: {}", source.name(), ex.getMessage());
            }
        }
        if (cached != null) {
            log.warn("全市场行情暂时不可用，使用最近一次缓存：{} 只", cached.stocks().size());
            return cached.stocks();
        }
        throw new IllegalStateException("沪深主板实时行情更新失败：已配置行情源均不可用", last);
    }

    private List<StockMarket> fetchSource(MarketDataSourceConfig source, LocalDate asOf) throws Exception {
        return switch (source.adapter().toUpperCase()) {
            case "EASTMONEY" -> fetchEastMoney(source, asOf);
            case "TENCENT" -> fetchTencent(source, asOf);
            default -> throw new IllegalArgumentException("全市场扫描暂不支持适配器: " + source.adapter());
        };
    }

    private List<StockMarket> fetchEastMoney(MarketDataSourceConfig source, LocalDate asOf) throws Exception {
        String fields = "f12,f13,f14,f2,f3,f4,f5,f6,f15,f16,f17,f18,f20,f21,f23,f24,f25,f100";
        String url = query(source.endpoint(), "pn=1&pz=5000&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:1%2Bt:2%2Cm:0%2Bt:6&fields=" + fields);
        JsonNode diff = objectMapper.readTree(send(source, url, false)).path("data").path("diff");
        List<StockMarket> result = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        if (!diff.isArray()) return result;
        for (JsonNode row : diff) {
            String code = row.path("f12").asText("");
            BigDecimal price = decimal(row, "f2");
            if (code.isBlank() || price == null || price.signum() <= 0 || !isSupportedMainBoard(code)) continue;
            BigDecimal preClose = decimal(row, "f18");
            BigDecimal limitUp = preClose == null ? null : preClose.multiply(new BigDecimal("1.10")).setScale(3, RoundingMode.HALF_UP);
            BigDecimal limitDown = preClose == null ? null : preClose.multiply(new BigDecimal("0.90")).setScale(3, RoundingMode.HALF_UP);
            String name = row.path("f14").asText(code);
            result.add(new StockMarket(code, name, row.path("f100").asText("未分类"), price, decimal(row, "f3"), decimal(row, "f6"), null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, limitUp, limitDown,
                    name.toUpperCase().startsWith("ST"), false, 1000, asOf, board(code), "REALTIME", now,
                    null, null, null, null, null, "QUOTE_ONLY"));
        }
        return result;
    }

    private List<StockMarket> fetchTencent(MarketDataSourceConfig source, LocalDate asOf) throws Exception {
        List<String> codes = new ArrayList<>();
        for (int value = 600000; value <= 605999; value++) codes.add(String.format("%06d", value));
        for (int value = 0; value <= 3999; value++) codes.add(String.format("%06d", value));
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < codes.size(); from += fallbackBatchSize) batches.add(codes.subList(from, Math.min(from + fallbackBatchSize, codes.size())));
        List<CompletableFuture<HttpResponse<byte[]>>> futures = batches.stream().map(batch -> {
            String symbols = batch.stream().map(code -> (code.startsWith("6") ? "sh" : "sz") + code).reduce((a, b) -> a + "," + b).orElse("");
            HttpRequest request = request(source, source.endpoint() + symbols, true);
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).exceptionally(error -> null);
        }).toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        Map<String, StockMarket> unique = new HashMap<>();
        for (CompletableFuture<HttpResponse<byte[]>> future : futures) {
            HttpResponse<byte[]> response = future.getNow(null);
            if (response == null || response.statusCode() / 100 != 2) continue;
            parseTencent(new String(response.body(), Charset.forName("GBK")), asOf).forEach(stock -> unique.put(stock.code(), stock));
        }
        return new ArrayList<>(unique.values());
    }

    private List<StockMarket> parseTencent(String body, LocalDate asOf) {
        List<StockMarket> result = new ArrayList<>();
        Matcher matcher = TENCENT_QUOTE.matcher(body == null ? "" : body);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        while (matcher.find()) {
            String[] fields = matcher.group(2).split("~", -1);
            if (fields.length <= 35 || !isSupportedMainBoard(fields[2])) continue;
            String code = fields[2];
            BigDecimal price = decimal(fields[3]);
            BigDecimal previousClose = decimal(fields[4]);
            BigDecimal changePercent = decimal(fields[32]);
            if (price == null || price.signum() <= 0 || previousClose == null || changePercent == null) continue;
            String name = fields[1] == null || fields[1].isBlank() ? code : fields[1];
            result.add(new StockMarket(code, name, "未分类", price, changePercent, turnover(fields[35]), null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    previousClose.multiply(new BigDecimal("1.10")).setScale(3, RoundingMode.HALF_UP),
                    previousClose.multiply(new BigDecimal("0.90")).setScale(3, RoundingMode.HALF_UP), name.toUpperCase().startsWith("ST"), false, 1000, asOf,
                    board(code), "REALTIME", now, null, null, null, null, null, "QUOTE_ONLY"));
        }
        return result;
    }

    private <T> T fetchWithRetry(MarketDataSourceConfig source, SupplierWithException<T> operation) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= source.retryCount(); attempt++) {
            try { return operation.get(); }
            catch (Exception ex) { last = ex; if (attempt < source.retryCount()) Thread.sleep(150L); }
        }
        throw last == null ? new IllegalStateException("行情源调用失败") : last;
    }

    private String send(MarketDataSourceConfig source, String url, boolean binary) throws Exception {
        if (binary) {
            HttpResponse<byte[]> response = httpClient.send(request(source, url, true), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
            return new String(response.body(), Charset.forName("GBK"));
        }
        HttpResponse<String> response = httpClient.send(request(source, url, false), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body();
    }

    private HttpRequest request(MarketDataSourceConfig source, String url, boolean binary) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(source.timeoutSeconds())).header("User-Agent", source.userAgent());
        if (source.referer() != null && !source.referer().isBlank()) builder.header("Referer", source.referer());
        return builder.GET().build();
    }

    private String query(String endpoint, String query) { return endpoint + (endpoint.contains("?") ? "&" : "?") + query; }
    private BigDecimal turnover(String quote) { if (quote == null) return null; String[] values = quote.split("/"); return values.length >= 3 ? decimal(values[2]) : null; }
    private BigDecimal decimal(String value) { if (value == null || value.isBlank() || "-".equals(value)) return null; try { return new BigDecimal(value); } catch (NumberFormatException ex) { return null; } }
    private BigDecimal decimal(JsonNode row, String field) { JsonNode value = row.get(field); return value == null || value.isNull() || value.asText().isBlank() || "-".equals(value.asText()) ? null : decimal(value.asText()); }
    private boolean isSupportedMainBoard(String code) { return code != null && (code.matches("60[0135]\\d{3}") || code.matches("00[0123]\\d{3}")); }
    private String board(String code) { if (code.matches("60[0135]\\d{3}")) return "上海主板"; if (code.matches("00[0123]\\d{3}")) return "深圳主板"; return "其他板块"; }
    @FunctionalInterface private interface SupplierWithException<T> { T get() throws Exception; }
    private record CachedUniverse(long loadedAtMillis, List<StockMarket> stocks) {}
}
