package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.ModelStatus;
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
import java.util.List;

/**
 * Reads model provenance from the database and records successful model reads.
 * A model is only reported as used after a persisted snapshot has been loaded
 * by the calculation path; generation alone is not treated as usage.
 */
@Component
public class ModelAuditStore {
    private static final Logger log = LoggerFactory.getLogger(ModelAuditStore.class);
    private final DataSource dataSource;

    private record Snapshot(boolean databaseAvailable, int version, LocalDateTime generatedAt,
                            long records, long coveredRecords, double rawScore, String source) {
        private static Snapshot unavailable(String source) {
            return new Snapshot(false, 0, null, 0, 0, 0, source);
        }
    }

    private record Usage(boolean databaseAvailable, long count, long usedRecords, LocalDateTime lastUsedAt) {
        private static Usage unavailable() { return new Usage(false, 0, 0, null); }
    }

    public ModelAuditStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<ModelStatus> loadStatuses(int activePoolCount) {
        int poolCount = Math.max(0, activePoolCount);
        List<ModelStatus> statuses = new ArrayList<>();
        statuses.add(status("stock-score", query(
                "SELECT COALESCE(MAX(version),0), MAX(generated_at), COUNT(*), "
                        + "COUNT(*), COALESCE(AVG(confidence) * 100,0) "
                        + "FROM scoring_model_snapshot WHERE strategy_id=? AND status='EFFECTIVE'",
                "value-quality-100"), poolCount, true));
        statuses.add(status("company-profile", query(
                "SELECT COALESCE(MAX(model_version),0), MAX(updated_at), COUNT(*), "
                        + "COUNT(DISTINCT stock_code), COALESCE(AVG(confidence) * 100,0) "
                        + "FROM company_profile_cache", null), poolCount, false));
        statuses.add(status("realtime-score", query(
                "SELECT COALESCE(MAX(model_version),0), MAX(generated_at), COUNT(*), "
                        + "COUNT(DISTINCT stock_code), 0 FROM ai_realtime_cache", null), poolCount, false));
        // Price advice and realtime factor scores are written atomically to the same cache row.
        statuses.add(status("price-advice", query(
                "SELECT COALESCE(MAX(model_version),0), MAX(generated_at), COUNT(*), "
                        + "COUNT(DISTINCT stock_code), 0 FROM ai_realtime_cache", null), poolCount, false));
        statuses.add(status("success-rate", queryTrade("SUCCESS_RATE"), poolCount, false));
        statuses.add(status("plan-analysis", queryTrade("PLAN_ANALYSIS"), poolCount, false));
        Snapshot rule = query(
                "SELECT COALESCE(MAX(version),0), MAX(created_at), COUNT(*), 1, 100 "
                        + "FROM strategy_config WHERE strategy_id=? AND effective_to IS NULL",
                "trend-growth-100");
        statuses.add(status("position-risk", rule, poolCount, false));
        statuses.add(status("sell-decision", rule, poolCount, false));
        statuses.add(status("portfolio-review", query(
                "SELECT COALESCE(MAX(model_version),0), MAX(generated_at), COUNT(*), "
                        + "COUNT(DISTINCT account_id), COALESCE(AVG(JSON_VALUE(result_json, '$.confidence')) * 100,0) "
                        + "FROM portfolio_analysis_snapshot", null), poolCount, false));
        return statuses;
    }

    public void recordUsage(String modelKey, int version, String operation, String stockCode) {
        if (modelKey == null || modelKey.isBlank() || version <= 0) return;
        String code = stockCode == null ? "" : stockCode.trim().toUpperCase();
        String action = operation == null || operation.isBlank() ? "READ" : operation;
        String sql = "INSERT INTO model_usage_log(model_key,model_version,stock_code,operation,usage_count,first_used_at,last_used_at) "
                + "VALUES(?,?,?,?,1,NOW(),NOW()) ON DUPLICATE KEY UPDATE usage_count=usage_count+1,last_used_at=VALUES(last_used_at)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, modelKey);
            statement.setInt(2, version);
            statement.setString(3, code);
            statement.setString(4, action);
            statement.executeUpdate();
        } catch (Exception ex) {
            log.debug("模型使用记录写入失败：模型={}，版本={}，{}", modelKey, version, ex.getMessage());
        }
    }

    public int latestVersion(String modelKey) {
        if (modelKey == null) return 0;
        String sql;
        String parameter = null;
        switch (modelKey) {
            case "stock-score" -> { sql = "SELECT COALESCE(MAX(version),0) FROM scoring_model_snapshot WHERE strategy_id=? AND status='EFFECTIVE'"; parameter = "value-quality-100"; }
            case "company-profile" -> sql = "SELECT COALESCE(MAX(model_version),0) FROM company_profile_cache";
            case "realtime-score", "price-advice" -> sql = "SELECT COALESCE(MAX(model_version),0) FROM ai_realtime_cache";
            case "success-rate" -> { sql = "SELECT COALESCE(MAX(version),0) FROM trade_ai_model_snapshot WHERE model_type=? AND status='EFFECTIVE'"; parameter = "SUCCESS_RATE"; }
            case "plan-analysis" -> { sql = "SELECT COALESCE(MAX(version),0) FROM trade_ai_model_snapshot WHERE model_type=? AND status='EFFECTIVE'"; parameter = "PLAN_ANALYSIS"; }
            case "position-risk", "sell-decision" -> { sql = "SELECT COALESCE(MAX(version),0) FROM strategy_config WHERE strategy_id=? AND effective_to IS NULL"; parameter = "trend-growth-100"; }
            case "portfolio-review" -> sql = "SELECT COALESCE(MAX(model_version),0) FROM portfolio_analysis_snapshot";
            default -> { return 0; }
        }
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) statement.setString(1, parameter);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        } catch (Exception ex) {
            return 0;
        }
    }

    private ModelStatus status(String key, Snapshot snapshot, int activePoolCount, boolean confidenceScore) {
        Usage usage = usage(key, snapshot.version());
        boolean persisted = snapshot.databaseAvailable() && snapshot.version() > 0 && snapshot.records() > 0;
        int score;
        if (!persisted) {
            score = 0;
        } else if (confidenceScore && snapshot.rawScore() > 0) {
            score = clampScore(snapshot.rawScore());
        } else if (snapshot.rawScore() > 0) {
            score = clampScore(snapshot.rawScore());
        } else if (activePoolCount > 0) {
            score = clampScore(snapshot.coveredRecords() * 100.0 / activePoolCount);
        } else {
            score = 100;
        }
        return new ModelStatus(key, snapshot.version(), snapshot.generatedAt(), persisted,
                snapshot.databaseAvailable(), snapshot.records(), usage.usedRecords(), usage.count(),
                usage.lastUsedAt(), score, snapshot.source());
    }

    private int clampScore(double value) {
        return (int) Math.max(0, Math.min(100, Math.round(value)));
    }

    private Snapshot queryTrade(String type) {
        return query("SELECT COALESCE(MAX(version),0), MAX(generated_at), COUNT(*), "
                        + "COUNT(DISTINCT stock_code), COALESCE(AVG(confidence) * 100,0) "
                        + "FROM trade_ai_model_snapshot WHERE model_type=? AND status='EFFECTIVE'", type);
    }

    private Snapshot query(String sql, String parameter) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) statement.setString(1, parameter);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Snapshot.unavailable("database");
                return new Snapshot(true, row.getInt(1), dateTime(row.getObject(2)),
                        row.getLong(3), row.getLong(4), row.getDouble(5), sourceFor(sql));
            }
        } catch (Exception ex) {
            log.debug("模型状态查询失败：{}", ex.getMessage());
            return Snapshot.unavailable(sourceFor(sql));
        }
    }

    private Usage usage(String modelKey, int version) {
        if (version <= 0) return Usage.unavailable();
        String sql = "SELECT COALESCE(SUM(usage_count),0), COUNT(DISTINCT NULLIF(stock_code,'')), MAX(last_used_at) "
                + "FROM model_usage_log WHERE model_key=? AND model_version=?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, modelKey);
            statement.setInt(2, version);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Usage.unavailable();
                return new Usage(true, row.getLong(1), row.getLong(2), dateTime(row.getObject(3)));
            }
        } catch (Exception ex) {
            return Usage.unavailable();
        }
    }

    private String sourceFor(String sql) {
        if (sql.contains("scoring_model_snapshot")) return "scoring_model_snapshot";
        if (sql.contains("company_profile_cache")) return "company_profile_cache";
        if (sql.contains("ai_realtime_cache")) return "ai_realtime_cache";
        if (sql.contains("trade_ai_model_snapshot")) return "trade_ai_model_snapshot";
        if (sql.contains("portfolio_analysis_snapshot")) return "portfolio_analysis_snapshot";
        return "strategy_config";
    }

    private LocalDateTime dateTime(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay();
        if (value instanceof LocalDateTime dateTime) return dateTime;
        return null;
    }
}
