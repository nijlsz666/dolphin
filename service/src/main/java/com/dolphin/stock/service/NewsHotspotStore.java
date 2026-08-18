package com.dolphin.stock.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class NewsHotspotStore {
    private static final Logger log = LoggerFactory.getLogger(NewsHotspotStore.class);
    private final DataSource dataSource;

    public NewsHotspotStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record News(long id, String code, String title, String content, String eventType, BigDecimal sentiment,
                       String aiSummary, LocalDateTime publishedAt, String url) {}

    public record FetchedNews(String code, String title, String content, String source,
                              LocalDateTime publishedAt, String url, String contentHash) {}

    /** Insert an online article once and return its database id for subsequent AI enrichment. */
    public long saveFetched(FetchedNews article) {
        if (article == null || article.title() == null || article.title().isBlank()
                || article.contentHash() == null || article.contentHash().isBlank()) return -1L;
        String find = "SELECT id FROM news_announcement WHERE content_hash=? LIMIT 1";
        String insert = "INSERT INTO news_announcement(stock_code,title,content,source,published_at,url,content_hash) VALUES(?,?,?,?,?,?,?)";
        try (Connection connection = open()) {
            try (PreparedStatement lookup = connection.prepareStatement(find)) {
                lookup.setString(1, article.contentHash());
                try (ResultSet row = lookup.executeQuery()) {
                    if (row.next()) return row.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(insert, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, article.code());
                statement.setString(2, article.title().trim());
                statement.setString(3, article.content());
                statement.setString(4, article.source());
                statement.setObject(5, article.publishedAt() == null ? LocalDateTime.now() : article.publishedAt());
                statement.setString(6, article.url());
                statement.setString(7, article.contentHash());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                }
            }
        } catch (Exception ex) {
            log.warn("网络新闻写入 news_announcement 失败：{}", ex.getMessage());
        }
        return -1L;
    }

    public void saveAiResult(long id, String eventType, BigDecimal sentiment, String summary) {
        String sql = "UPDATE news_announcement SET event_type=?, sentiment=?, ai_summary=? WHERE id=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventType);
            statement.setBigDecimal(2, sentiment);
            statement.setString(3, summary);
            statement.setLong(4, id);
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("AI新闻分析结果写入失败，新闻ID={}: {}", id, ex.getMessage());
        }
    }

    public List<News> recent(int limit) {
        return query("WHERE published_at >= DATE_SUB(NOW(), INTERVAL 3 DAY)", limit);
    }

    /**
     * Returns the small set of announcements that can remain actionable after the
     * three-day news window has elapsed (for example, an announced dividend that
     * is still waiting for its record date). Filtering the title in Java keeps the
     * query portable across the MySQL-compatible databases used by deployments.
     */
    public List<News> importantEvents(int limit) {
        List<News> candidates = query("WHERE published_at >= DATE_SUB(NOW(), INTERVAL 180 DAY)", Math.max(limit * 3, limit));
        return candidates.stream()
                .filter(this::looksLikeImportantEvent)
                .limit(limit)
                .toList();
    }

    private List<News> query(String where, int limit) {
        String sql = "SELECT id, stock_code, title, content, event_type, sentiment, ai_summary, published_at, url FROM news_announcement "
                + where + " "
                + "ORDER BY published_at DESC LIMIT ?";
        List<News> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new News(rows.getLong("id"), rows.getString("stock_code"), rows.getString("title"), rows.getString("content"),
                            rows.getString("event_type"), rows.getBigDecimal("sentiment"), rows.getString("ai_summary"),
                            rows.getTimestamp("published_at") == null ? null : rows.getTimestamp("published_at").toLocalDateTime(),
                            rows.getString("url")));
                }
            }
        } catch (Exception ex) {
            log.info("新闻公告表暂不可用或暂无数据: {}", ex.getMessage());
        }
        return result;
    }

    private boolean looksLikeImportantEvent(News news) {
        if ("利好".equals(news.eventType()) || "利空".equals(news.eventType())) return true;
        String text = ((news.title() == null ? "" : news.title()) + " "
                + (news.content() == null ? "" : news.content())).toLowerCase();
        return text.matches(".*(分红|派息|利润分配|权益分派|股权登记|除权除息|回购|增持|减持|重大合同|重大资产|并购重组|业绩预告|业绩快报|年报|半年报|一季报|三季报|风险警示|立案调查|诉讼|仲裁|停牌|复牌).*" );
    }

    private Connection open() throws Exception { return dataSource.getConnection(); }
}
