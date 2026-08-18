package com.dolphin.stock.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dolphin.stock.model.StockAnalysisModels.AnalysisAccuracy;
import com.dolphin.stock.model.StockAnalysisModels.FactorScores;
import com.dolphin.stock.model.StockAnalysisModels.PriceHistoryPoint;
import com.dolphin.stock.model.StockAnalysisModels.StockPoolItem;
import com.dolphin.stock.model.StockAnalysisModels.TradePlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StockPoolStore {
    private static final Logger log = LoggerFactory.getLogger(StockPoolStore.class);
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final String strategyId = "trend-growth-100";

    public StockPoolStore(ObjectMapper objectMapper, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    public record Membership(String code, LocalDate addedAt, String addedBy, String name, String industry) {}

    public List<Membership> loadActive() {
        String sql = "SELECT stock_code, added_at, added_by, note FROM stock_pool_membership "
                + "WHERE strategy_id=? AND removed_at IS NULL ORDER BY added_at, id";
        List<Membership> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, strategyId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, String> note = parseNote(rows.getString("note"));
                    result.add(new Membership(rows.getString("stock_code"),
                            rows.getTimestamp("added_at").toLocalDateTime().toLocalDate(),
                            rows.getString("added_by"), note.get("name"), note.get("industry")));
                }
            }
        } catch (Exception ex) {
            log.info("股票池表暂不可用，使用内存股票池: {}", ex.getMessage());
        }
        return result;
    }

    public void saveMembership(String code, LocalDate addedAt, String addedBy, String name, String industry) {
        String note = writeNote(name, industry);
        String updateSql = "UPDATE stock_pool_membership SET added_by=?, added_at=?, note=? "
                + "WHERE strategy_id=? AND stock_code=? AND removed_at IS NULL";
        String insertSql = "INSERT INTO stock_pool_membership(strategy_id,stock_code,added_by,added_at,note) "
                + "VALUES(?,?,?,?,?)";
        try (Connection connection = open(); PreparedStatement update = connection.prepareStatement(updateSql)) {
            update.setString(1, addedBy);
            update.setObject(2, addedAt);
            update.setString(3, note);
            update.setString(4, strategyId);
            update.setString(5, code);
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    insert.setString(1, strategyId);
                    insert.setString(2, code);
                    insert.setString(3, addedBy);
                    insert.setObject(4, addedAt);
                    insert.setString(5, note);
                    insert.executeUpdate();
                }
            }
        } catch (Exception ex) {
            log.warn("股票池成员写入 stock_pool_membership 失败: {}", ex.getMessage());
        }
    }

    public void removeMembership(String code) {
        String sql = "UPDATE stock_pool_membership SET removed_at=NOW() "
                + "WHERE strategy_id=? AND stock_code=? AND removed_at IS NULL";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, strategyId);
            statement.setString(2, code);
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("股票池成员移除写入失败: {}", ex.getMessage());
        }
    }

    public void saveSnapshot(LocalDate asOf, StockPoolItem item, TradePlan tradePlan) {
        FactorScores score = item.scores();
        String sql = "INSERT INTO factor_snapshot(stock_code,as_of_date,trend_score,momentum_score,"
                + "volume_price_score,fundamental_score,capital_score,quality_valuation_score,ai_suggestion_score,"
                + "business_model_score,industry_prospect_score,competitive_advantage_score,financial_quality_score,growth_score,valuation_score,catalyst_score,risk_score,raw_score,"
                + "risk_penalty,final_score,buy_low,buy_high,factor_json,data_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE trend_score=VALUES(trend_score),momentum_score=VALUES(momentum_score),"
                + "volume_price_score=VALUES(volume_price_score),fundamental_score=VALUES(fundamental_score),"
                + "capital_score=VALUES(capital_score),quality_valuation_score=VALUES(quality_valuation_score),"
                + "ai_suggestion_score=VALUES(ai_suggestion_score),business_model_score=VALUES(business_model_score),"
                + "industry_prospect_score=VALUES(industry_prospect_score),competitive_advantage_score=VALUES(competitive_advantage_score),"
                + "financial_quality_score=VALUES(financial_quality_score),growth_score=VALUES(growth_score),valuation_score=VALUES(valuation_score),"
                + "catalyst_score=VALUES(catalyst_score),risk_score=VALUES(risk_score),"
                + "raw_score=VALUES(raw_score),risk_penalty=VALUES(risk_penalty),final_score=VALUES(final_score),"
                + "buy_low=VALUES(buy_low),buy_high=VALUES(buy_high),factor_json=VALUES(factor_json)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, item.stock().code());
            statement.setObject(2, asOf);
            statement.setInt(3, score.trend());
            statement.setInt(4, score.momentum());
            statement.setInt(5, score.volumePrice());
            statement.setInt(6, score.fundamental());
            statement.setInt(7, score.capital());
            statement.setInt(8, score.qualityValuation());
            statement.setInt(9, score.aiSuggestion());
            statement.setInt(10, score.businessModel());
            statement.setInt(11, score.industryProspect());
            statement.setInt(12, score.competitiveAdvantage());
            statement.setInt(13, score.financialQuality());
            statement.setInt(14, score.growth());
            statement.setInt(15, score.valuation());
            statement.setInt(16, score.catalyst());
            statement.setInt(17, score.risk());
            statement.setInt(18, score.total());
            statement.setInt(19, score.riskPenalty());
            statement.setInt(20, score.finalScore());
            statement.setBigDecimal(21, score.buyLow());
            statement.setBigDecimal(22, score.buyHigh());
            statement.setString(23, writeSnapshot(item, tradePlan));
            statement.setString(24, "value-quality-v4-ai-score");
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("分析快照写入 factor_snapshot 失败: {}", ex.getMessage());
        }
    }

    /** 读取手动计算后保存的准确率快照；股票池加载不会重新计算。 */
    public AnalysisAccuracy loadAccuracy(String stockCode) {
        String sql = "SELECT prediction_samples, prediction_correct, prediction_rate, prediction_label, prediction_method, "
                + "operation_samples, operation_correct, operation_rate, operation_label, operation_method, calculated_at "
                + "FROM accuracy_snapshot WHERE stock_code=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stockCode);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    return new AnalysisAccuracy(row.getInt("prediction_samples"), row.getInt("prediction_correct"),
                            row.getBigDecimal("prediction_rate"), row.getString("prediction_label"), row.getString("prediction_method"),
                            row.getInt("operation_samples"), row.getInt("operation_correct"), row.getBigDecimal("operation_rate"),
                            row.getString("operation_label"), row.getString("operation_method"),
                            row.getTimestamp("calculated_at") == null ? null : row.getTimestamp("calculated_at").toLocalDateTime());
                }
            }
        } catch (Exception ex) {
            log.info("{} 准确率快照暂不可用: {}", stockCode, ex.getMessage());
        }
        return insufficientAccuracy();
    }

    /** 手动计算两类准确率：预测列对当前价、已确认交易对当前价。 */
    public AnalysisAccuracy calculateAccuracy(String stockCode, LocalDate asOf, List<PriceHistoryPoint> priceHistory,
                                               BigDecimal currentPrice, List<TradeExecutionStore.Trade> trades) {
        LocalDate endDate = asOf == null ? LocalDate.now() : asOf;
        List<PriceHistoryPoint> orderedPrices = priceHistory == null ? List.of() : priceHistory.stream()
                .filter(point -> point != null && point.date() != null && point.price() != null)
                .sorted(Comparator.comparing(PriceHistoryPoint::date))
                .toList();
        BigDecimal latestPrice = currentPrice != null && currentPrice.signum() > 0 ? currentPrice
                : orderedPrices.stream().filter(point -> !point.date().isAfter(endDate)).reduce((first, second) -> second)
                .map(PriceHistoryPoint::price).orElse(null);

        int predictionSamples = 0;
        int predictionCorrect = 0;
        String predictionSql = "SELECT as_of_date, factor_json FROM factor_snapshot "
                + "WHERE stock_code=? AND as_of_date>=? AND as_of_date<? ORDER BY as_of_date";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(predictionSql)) {
            statement.setString(1, stockCode);
            statement.setObject(2, endDate.minusDays(120));
            statement.setObject(3, endDate);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    LocalDate snapshotDate = rows.getDate("as_of_date").toLocalDate();
                    String json = rows.getString("factor_json");
                    if (json == null || json.isBlank()) continue;
                    JsonNode snapshot = objectMapper.readTree(json);
                    BigDecimal snapshotPrice = jsonDecimal(snapshot.path("price"));
                    PriceHistoryPoint baseline = orderedPrices.stream().filter(point -> point.date().equals(snapshotDate)).findFirst().orElse(null);
                    if (baseline != null) snapshotPrice = baseline.price();
                    String signal = snapshot.path("tradePlan").path("signal").asText("");
                    String band = snapshot.path("tradePlan").path("band").asText("");
                    if (snapshotPrice == null || latestPrice == null || !("分批买入".equals(signal) || "买入区".equals(band))) continue;
                    boolean actualUp = latestPrice.compareTo(snapshotPrice) > 0;
                    predictionSamples++;
                    if (actualUp) predictionCorrect++;
                }
            }
        } catch (Exception ex) {
            log.info("{} 预测准确率计算失败: {}", stockCode, ex.getMessage());
        }

        int operationSamples = 0;
        int operationCorrect = 0;
        if (trades != null && latestPrice != null) {
            for (TradeExecutionStore.Trade trade : trades) {
                if (trade == null || trade.executedPrice() == null || trade.executedPrice().signum() <= 0
                        || trade.tradeDate() == null || trade.tradeDate().isAfter(endDate)) continue;
                boolean buy = "BUY".equalsIgnoreCase(trade.side());
                boolean correct = buy ? latestPrice.compareTo(trade.executedPrice()) > 0
                        : latestPrice.compareTo(trade.executedPrice()) < 0;
                operationSamples++;
                if (correct) operationCorrect++;
            }
        }
        return new AnalysisAccuracy(predictionSamples, predictionCorrect, accuracyRate(predictionSamples, predictionCorrect),
                predictionSamples >= 5 ? "可参考" : "样本不足", "买入区/买卖提示与当前价比较",
                operationSamples, operationCorrect, accuracyRate(operationSamples, operationCorrect),
                operationSamples > 0 ? "可参考" : "暂无执行记录", "已确认买卖价与当前价比较", LocalDateTime.now());
    }

    public void saveAccuracy(String stockCode, AnalysisAccuracy accuracy) {
        String sql = "INSERT INTO accuracy_snapshot(stock_code,prediction_samples,prediction_correct,prediction_rate,prediction_label,prediction_method,"
                + "operation_samples,operation_correct,operation_rate,operation_label,operation_method,calculated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE prediction_samples=VALUES(prediction_samples), prediction_correct=VALUES(prediction_correct), prediction_rate=VALUES(prediction_rate), "
                + "prediction_label=VALUES(prediction_label), prediction_method=VALUES(prediction_method), operation_samples=VALUES(operation_samples), operation_correct=VALUES(operation_correct), "
                + "operation_rate=VALUES(operation_rate), operation_label=VALUES(operation_label), operation_method=VALUES(operation_method), calculated_at=VALUES(calculated_at)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stockCode);
            statement.setInt(2, accuracy.predictionSamples());
            statement.setInt(3, accuracy.predictionCorrect());
            statement.setBigDecimal(4, accuracy.predictionRate());
            statement.setString(5, accuracy.predictionLabel());
            statement.setString(6, accuracy.predictionMethod());
            statement.setInt(7, accuracy.operationSamples());
            statement.setInt(8, accuracy.operationCorrect());
            statement.setBigDecimal(9, accuracy.operationRate());
            statement.setString(10, accuracy.operationLabel());
            statement.setString(11, accuracy.operationMethod());
            statement.setObject(12, accuracy.calculatedAt());
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("{} 准确率快照写入失败: {}", stockCode, ex.getMessage());
        }
    }

    private BigDecimal accuracyRate(int samples, int correct) {
        return samples == 0 ? null : BigDecimal.valueOf(correct).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(samples), 2, RoundingMode.HALF_UP);
    }

    private Connection open() throws Exception {
        return dataSource.getConnection();
    }

    private String writeNote(String name, String industry) {
        try {
            return objectMapper.writeValueAsString(Map.of("name", name == null ? "" : name,
                    "industry", industry == null ? "" : industry));
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Map<String, String> parseNote(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of("industry", value);
        }
    }

    private String writeSnapshot(StockPoolItem item, TradePlan tradePlan) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("price", item.stock().price());
        snapshot.put("action", item.action());
        snapshot.put("hardFilterPassed", item.hardFilter().passed());
        snapshot.put("filterReasons", item.hardFilter().reasons());
        snapshot.put("explanations", item.scores().explanations());
        snapshot.put("stockContext", item.stockContext());
        snapshot.put("tradePlan", tradePlan);
        snapshot.put("quoteStatus", item.stock().quoteStatus());
        snapshot.put("quoteTime", item.stock().quoteTime());
        snapshot.put("dataStatus", item.stock().dataStatus());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private BigDecimal jsonDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) return null;
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private AnalysisAccuracy insufficientAccuracy() {
        return new AnalysisAccuracy(0, 0, null, "样本不足", "手动计算后显示", 0, 0, null,
                "暂无执行记录", "确认买卖后再计算", null);
    }
}
