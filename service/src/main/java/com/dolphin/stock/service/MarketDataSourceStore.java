package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.MarketDataSourceConfig;
import com.dolphin.stock.model.StockAnalysisModels.MarketDataSourceConfigRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class MarketDataSourceStore {
    private static final Logger log = LoggerFactory.getLogger(MarketDataSourceStore.class);
    private final DataSource dataSource;
    private volatile List<MarketDataSourceConfig> fallback = defaults();

    public MarketDataSourceStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<MarketDataSourceConfig> load() {
        String sql = "SELECT source_key, name, purpose, adapter, enabled, priority, endpoint, timeout_seconds, retry_count, user_agent, referer, updated_at "
                + "FROM market_data_source ORDER BY priority, source_key";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<MarketDataSourceConfig> result = new ArrayList<>();
            while (rows.next()) result.add(read(rows));
            if (!result.isEmpty()) fallback = List.copyOf(result);
            return result.isEmpty() ? fallback : List.copyOf(result);
        } catch (Exception ex) {
            log.warn("读取行情源配置表失败，使用内置默认配置: {}", ex.getMessage());
            return fallback;
        }
    }

    public List<MarketDataSourceConfig> active(String purpose) {
        return load().stream().filter(item -> item.enabled() && purpose.equalsIgnoreCase(item.purpose()))
                .sorted(Comparator.comparingInt(MarketDataSourceConfig::priority)).toList();
    }

    public synchronized List<MarketDataSourceConfig> save(List<MarketDataSourceConfigRequest> requests) {
        if (requests == null || requests.isEmpty()) throw new IllegalArgumentException("至少配置一个行情源");
        List<MarketDataSourceConfig> next = requests.stream().map(this::normalize).toList();
        String sql = "INSERT INTO market_data_source(source_key,name,purpose,adapter,enabled,priority,endpoint,timeout_seconds,retry_count,user_agent,referer,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE name=VALUES(name),purpose=VALUES(purpose),adapter=VALUES(adapter),enabled=VALUES(enabled),priority=VALUES(priority),endpoint=VALUES(endpoint),timeout_seconds=VALUES(timeout_seconds),retry_count=VALUES(retry_count),user_agent=VALUES(user_agent),referer=VALUES(referer),updated_at=NOW()";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (MarketDataSourceConfig item : next) {
                statement.setString(1, item.sourceKey());
                statement.setString(2, item.name());
                statement.setString(3, item.purpose());
                statement.setString(4, item.adapter());
                statement.setBoolean(5, item.enabled());
                statement.setInt(6, item.priority());
                statement.setString(7, item.endpoint());
                statement.setInt(8, item.timeoutSeconds());
                statement.setInt(9, item.retryCount());
                statement.setString(10, item.userAgent());
                statement.setString(11, item.referer());
                statement.addBatch();
            }
            statement.executeBatch();
            fallback = List.copyOf(next);
        } catch (Exception ex) {
            log.warn("行情源配置写入数据库失败，暂存内存: {}", ex.getMessage());
            fallback = List.copyOf(next);
        }
        return load();
    }

    private MarketDataSourceConfig normalize(MarketDataSourceConfigRequest request) {
        if (request == null || blank(request.sourceKey()) || blank(request.name()) || blank(request.purpose())
                || blank(request.adapter()) || blank(request.endpoint())) {
            throw new IllegalArgumentException("行情源名称、用途、适配器和接口地址不能为空");
        }
        String purpose = request.purpose().trim().toUpperCase();
        String adapter = request.adapter().trim().toUpperCase();
        if (!List.of("REALTIME", "UNIVERSE").contains(purpose)) throw new IllegalArgumentException("行情源用途只能是 REALTIME 或 UNIVERSE");
        if (!List.of("EASTMONEY", "TENCENT", "SINA").contains(adapter)) throw new IllegalArgumentException("适配器只能是 EASTMONEY、TENCENT 或 SINA");
        return new MarketDataSourceConfig(request.sourceKey().trim(), request.name().trim(), purpose, adapter,
                request.enabled() == null || request.enabled(), Math.max(1, request.priority() == null ? 100 : request.priority()),
                request.endpoint().trim(), Math.max(2, Math.min(60, request.timeoutSeconds() == null ? 8 : request.timeoutSeconds())),
                Math.max(0, Math.min(5, request.retryCount() == null ? 1 : request.retryCount())),
                blank(request.userAgent()) ? "Mozilla/5.0 DolphinStock/1.0" : request.userAgent().trim(),
                blank(request.referer()) ? "" : request.referer().trim(), LocalDateTime.now());
    }

    private MarketDataSourceConfig read(ResultSet row) throws Exception {
        Timestamp updated = row.getTimestamp("updated_at");
        return new MarketDataSourceConfig(row.getString("source_key"), row.getString("name"), row.getString("purpose"),
                row.getString("adapter"), row.getBoolean("enabled"), row.getInt("priority"), row.getString("endpoint"),
                row.getInt("timeout_seconds"), row.getInt("retry_count"), row.getString("user_agent"), row.getString("referer"),
                updated == null ? null : updated.toLocalDateTime());
    }

    private Connection open() throws Exception { return dataSource.getConnection(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    private static List<MarketDataSourceConfig> defaults() {
        return List.of(
                source("eastmoney-realtime", "东方财富实时行情", "REALTIME", "EASTMONEY", 10, "https://push2.eastmoney.com/api/qt/ulist.np/get", "https://quote.eastmoney.com/"),
                source("tencent-realtime", "腾讯实时行情", "REALTIME", "TENCENT", 20, "https://qt.gtimg.cn/q=", "https://gu.qq.com/"),
                source("sina-realtime", "新浪实时行情", "REALTIME", "SINA", 30, "https://hq.sinajs.cn/list=", "https://finance.sina.com.cn/"),
                source("eastmoney-universe", "东方财富全市场扫描", "UNIVERSE", "EASTMONEY", 10, "https://push2.eastmoney.com/api/qt/clist/get", "https://quote.eastmoney.com/"),
                source("tencent-universe", "腾讯全市场扫描", "UNIVERSE", "TENCENT", 20, "https://qt.gtimg.cn/q=", "https://gu.qq.com/"));
    }

    private static MarketDataSourceConfig source(String key, String name, String purpose, String adapter, int priority, String endpoint, String referer) {
        return new MarketDataSourceConfig(key, name, purpose, adapter, true, priority, endpoint, 8, 1,
                "Mozilla/5.0 DolphinStock/1.0", referer, null);
    }
}
