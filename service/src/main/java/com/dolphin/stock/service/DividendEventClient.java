package com.dolphin.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Public dividend calendar fallback used when the local announcement table is empty. */
@Component
public class DividendEventClient {
    private static final Logger log = LoggerFactory.getLogger(DividendEventClient.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public DividendEventClient(ObjectMapper objectMapper,
                               @Value("${market-data.dividend-endpoint:https://datacenter-web.eastmoney.com/api/data/v1/get}") String endpoint,
                               @Value("${market-data.timeout-seconds:8}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public List<NewsHotspotStore.News> latest(String code) {
        if (code == null || code.isBlank()) return List.of();
        String normalized = code.trim();
        Cached cached = cache.get(normalized);
        if (cached != null && cached.expiresAt().isAfter(LocalDateTime.now())) return cached.events();
        List<NewsHotspotStore.News> events = fetch(normalized);
        cache.put(normalized, new Cached(LocalDateTime.now().plus(CACHE_TTL), events));
        return events;
    }

    private List<NewsHotspotStore.News> fetch(String code) {
        String filter = "%28SECURITY_CODE%3D%22" + code + "%22%29";
        String url = endpoint + "?reportName=RPT_SHAREBONUS_DET&columns=ALL&filter=" + filter
                + "&pageNumber=1&pageSize=20&sortColumns=EX_DIVIDEND_DATE&sortTypes=-1&source=WEB&client=WEB";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("User-Agent", "Mozilla/5.0 DolphinStock/1.0")
                    .header("Referer", "https://data.eastmoney.com/yjfp/").GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
            JsonNode rows = objectMapper.readTree(response.body()).path("result").path("data");
            if (!rows.isArray()) return List.of();
            LocalDate today = LocalDate.now(SHANGHAI);
            for (JsonNode row : rows) {
                LocalDate recordDate = date(row, "EQUITY_RECORD_DATE");
                LocalDate exDate = date(row, "EX_DIVIDEND_DATE");
                LocalDate noticeDate = date(row, "NOTICE_DATE");
                LocalDate planDate = date(row, "PLAN_NOTICE_DATE");
                LocalDate reportDate = date(row, "REPORT_DATE");
                String progress = row.path("ASSIGN_PROGRESS").asText("");
                // Only surface an event that has not reached its record date, or a
                // recently announced proposal whose dates are not known yet.
                if (recordDate != null && recordDate.isBefore(today)) continue;
                if (recordDate == null && planDate != null && planDate.isBefore(today.minusDays(180))) continue;
                String title = "分红安排：" + row.path("SECURITY_NAME_ABBR").asText(code)
                        + (reportDate == null ? "" : " " + reportDate.getYear() + "年度") + bonus(row.path("PRETAX_BONUS_RMB")) + "，"
                        + dateText("股权登记日", recordDate) + "，" + dateText("除权除息日", exDate)
                        + "，当前进度：" + (progress.isBlank() ? "待实施" : progress);
                String summary = "现金分红 " + bonus(row.path("PRETAX_BONUS_RMB"))
                        + "；" + dateText("股权登记日", recordDate) + "；" + dateText("除权除息日", exDate)
                        + "。请以公司正式公告和交易所最终安排为准。";
                LocalDate published = noticeDate == null ? planDate : noticeDate;
                LocalDateTime publishedAt = published == null ? LocalDateTime.now(SHANGHAI) : published.atStartOfDay();
                long id = -Math.abs((code + "|" + publishedAt + "|dividend").hashCode());
                String detailUrl = "https://data.eastmoney.com/yjfp/detail/" + code + ".html";
                return List.of(new NewsHotspotStore.News(id, code, title, summary, "利好", BigDecimal.ONE,
                        summary, publishedAt, detailUrl));
            }
        } catch (Exception ex) {
            log.info("分红日历暂不可用，股票={}: {}", code, ex.getMessage());
        }
        return List.of();
    }

    private String bonus(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "已公布现金分红";
        return "每10股派" + value.asText() + "元（含税）";
    }

    private String dateText(String label, LocalDate date) { return label + (date == null ? "待公告" : date); }

    private LocalDate date(JsonNode row, String field) {
        String value = row.path(field).asText("");
        if (value.length() < 10) return null;
        try { return LocalDate.parse(value.substring(0, 10)); }
        catch (Exception ignored) { return null; }
    }

    private record Cached(LocalDateTime expiresAt, List<NewsHotspotStore.News> events) {}
}
