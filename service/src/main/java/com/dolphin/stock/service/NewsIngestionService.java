package com.dolphin.stock.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches online news on a schedule and provides a throttled request-time fallback. */
@Service
public class NewsIngestionService {
    private static final Logger log = LoggerFactory.getLogger(NewsIngestionService.class);
    private static final Pattern STOCK_CODE = Pattern.compile("(?<!\\d)([036]\\d{5})(?!\\d)");
    private static final int MAX_ARTICLES_PER_FEED = 80;
    private final NewsHotspotStore newsStore;
    private final AiAnalysisService aiAnalysisService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private final List<String> feedUrls;
    private final Object refreshLock = new Object();
    private volatile long lastOnDemandRefreshMillis;

    /** Prevents multiple page requests from starting the same network refresh at once. */
    private static final long ON_DEMAND_REFRESH_COOLDOWN_MILLIS = TimeUnit.SECONDS.toMillis(15);

    public NewsIngestionService(NewsHotspotStore newsStore, AiAnalysisService aiAnalysisService,
                                @Value("${news.feed-urls:https://finance.sina.com.cn/7x24/rollnews.xml,https://rss.sina.com.cn/finance/stock.xml}") String configuredFeeds) {
        this.newsStore = newsStore;
        this.aiAnalysisService = aiAnalysisService;
        this.feedUrls = List.of(configuredFeeds.split(",")).stream().map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    public Map<String, Object> refreshNow() {
        synchronized (refreshLock) {
            return refreshNowLocked();
        }
    }

    /**
     * Request-time fallback for a cold or expired news cache. Scheduled jobs remain
     * the normal path; this method only performs a throttled refresh when the cache
     * has no recent article at all.
     */
    public boolean ensureNewsAvailable() {
        if (!newsStore.recent(1).isEmpty()) return true;
        refreshOnDemand();
        return !newsStore.recent(1).isEmpty();
    }

    /**
     * Refreshes feeds when a stock has no matching article, even if other market
     * news is already cached. Matching is retried by the caller after this method.
     */
    public void refreshOnDemand() {
        long now = System.currentTimeMillis();
        if (now - lastOnDemandRefreshMillis < ON_DEMAND_REFRESH_COOLDOWN_MILLIS) return;
        synchronized (refreshLock) {
            now = System.currentTimeMillis();
            if (now - lastOnDemandRefreshMillis < ON_DEMAND_REFRESH_COOLDOWN_MILLIS) return;
            lastOnDemandRefreshMillis = now;
            refreshNowLocked();
        }
    }

    private Map<String, Object> refreshNowLocked() {
        long started = System.nanoTime();
        int stored = 0;
        int feedsOk = 0;
        for (String feedUrl : feedUrls) {
            try {
                List<NewsHotspotStore.FetchedNews> articles = fetchFeed(feedUrl);
                if (!articles.isEmpty()) feedsOk++;
                for (NewsHotspotStore.FetchedNews article : articles) {
                    if (newsStore.saveFetched(article) > 0) stored++;
                }
                log.info("[新闻缓存] 网络采集完成：source={}，读取={} 条", feedUrl, articles.size());
            } catch (Exception ex) {
                log.warn("[新闻缓存] 网络采集失败：source={}，{}", feedUrl, ex.getMessage());
            }
        }
        List<NewsHotspotStore.News> cached = newsStore.recent(200);
        long aiStarted = System.nanoTime();
        List<NewsHotspotStore.News> enriched = aiAnalysisService.enrichNews(cached);
        int aiAvailable = (int) enriched.stream().filter(item -> item.aiSummary() != null && !item.aiSummary().isBlank()).count();
        log.info("[新闻缓存] AI新闻分析完成：缓存={} 条，已分析={} 条，耗时={} ms", cached.size(), aiAvailable, elapsedMs(aiStarted));
        log.info("[新闻缓存] 每小时刷新完成：feedsOk={}，入库处理={}，总耗时={} ms", feedsOk, stored, elapsedMs(started));
        return Map.of("feeds", feedUrls.size(), "feedsOk", feedsOk, "stored", stored,
                "cached", cached.size(), "aiAvailable", aiAvailable);
    }

    private List<NewsHotspotStore.FetchedNews> fetchFeed(String feedUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(feedUrl))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", "DolphinStock/1.0 news-cache").GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(response.body())));
        NodeList nodes = document.getElementsByTagName("item");
        boolean atom = nodes.getLength() == 0;
        if (atom) nodes = document.getElementsByTagName("entry");
        List<NewsHotspotStore.FetchedNews> result = new ArrayList<>();
        for (int i = 0; i < Math.min(nodes.getLength(), MAX_ARTICLES_PER_FEED); i++) {
            Element element = (Element) nodes.item(i);
            String title = childText(element, "title");
            String content = cleanHtml(firstNonBlank(childText(element, "description"), childText(element, "summary"), childText(element, "content")));
            String url = childText(element, "link");
            if (atom && (url == null || url.isBlank())) {
                NodeList links = element.getElementsByTagName("link");
                if (links.getLength() > 0) url = ((Element) links.item(0)).getAttribute("href");
            }
            if (title == null || title.isBlank()) continue;
            LocalDateTime publishedAt = parseDate(firstNonBlank(childText(element, "pubDate"), childText(element, "published"), childText(element, "updated")));
            String text = title + " " + (content == null ? "" : content);
            Matcher matcher = STOCK_CODE.matcher(text);
            String code = matcher.find() ? matcher.group(1) : null;
            String hash = sha256(title + "\n" + (url == null ? "" : url) + "\n" + publishedAt);
            result.add(new NewsHotspotStore.FetchedNews(code, title, content, sourceOf(feedUrl), publishedAt, url, hash));
        }
        return result;
    }

    private String childText(Element parent, String name) {
        NodeList children = parent.getElementsByTagName(name);
        if (children.getLength() == 0) return null;
        Node node = children.item(0);
        return node == null ? null : node.getTextContent();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String cleanHtml(String value) {
        if (value == null) return "";
        return value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) return LocalDateTime.now();
        try { return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDateTime(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value.trim()); }
        catch (DateTimeParseException ignored) { return LocalDateTime.now(); }
    }

    private String sourceOf(String feedUrl) {
        try { return URI.create(feedUrl).getHost(); }
        catch (Exception ignored) { return "online-feed"; }
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return String.format("%064x", new BigInteger(1, digest));
    }

    private long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
