package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.TradeAnalysisModel;
import com.dolphin.stock.model.StockAnalysisModels.TradeSuccessRateModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Hourly AI trade models. Plan requests only read these snapshots; they never call AI. */
@Component
public class TradeModelStore {
    private static final Logger log = LoggerFactory.getLogger(TradeModelStore.class);
    private static final String SUCCESS_RATE = "SUCCESS_RATE";
    private static final String PLAN_ANALYSIS = "PLAN_ANALYSIS";
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final Map<String, TradeSuccessRateModel> successFallback = new ConcurrentHashMap<>();
    private final Map<String, TradeAnalysisModel> analysisFallback = new ConcurrentHashMap<>();

    public TradeModelStore(ObjectMapper objectMapper, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    public Optional<TradeSuccessRateModel> loadLatestSuccessRateModel(String stockCode) {
        String code = normalize(stockCode);
        if (code.isBlank()) return Optional.empty();
        String sql = "SELECT version, model_json FROM trade_ai_model_snapshot WHERE stock_code=? AND model_type=? AND status='EFFECTIVE' "
                + "ORDER BY version DESC, generated_at DESC";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setString(2, SUCCESS_RATE);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    int rowVersion = row.getInt("version");
                    TradeSuccessRateModel model = objectMapper.readValue(row.getString("model_json"), TradeSuccessRateModel.class);
                    if (model.version() != rowVersion) {
                        log.warn("交易成功率模型快照版本不一致：股票={}，数据库版本={}，模型内容版本={}", code, rowVersion, model.version());
                        continue;
                    }
                    successFallback.put(code, model);
                    return Optional.of(model);
                }
            }
        } catch (Exception ex) {
            log.info("交易成功率模型表暂不可用，使用内存快照：{}", ex.getMessage());
            return Optional.ofNullable(successFallback.get(code));
        }
        return Optional.empty();
    }

    public Optional<TradeAnalysisModel> loadLatestAnalysisModel(String stockCode) {
        String code = normalize(stockCode);
        if (code.isBlank()) return Optional.empty();
        String sql = "SELECT version, model_json FROM trade_ai_model_snapshot WHERE stock_code=? AND model_type=? AND status='EFFECTIVE' "
                + "ORDER BY version DESC, generated_at DESC";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setString(2, PLAN_ANALYSIS);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    int rowVersion = row.getInt("version");
                    TradeAnalysisModel model = objectMapper.readValue(row.getString("model_json"), TradeAnalysisModel.class);
                    if (model.version() != rowVersion) {
                        log.warn("交易分析模型快照版本不一致：股票={}，数据库版本={}，模型内容版本={}", code, rowVersion, model.version());
                        continue;
                    }
                    analysisFallback.put(code, model);
                    return Optional.of(model);
                }
            }
        } catch (Exception ex) {
            log.info("交易分析模型表暂不可用，使用内存快照：{}", ex.getMessage());
            return Optional.ofNullable(analysisFallback.get(code));
        }
        return Optional.empty();
    }

    public void saveSuccessRateModel(TradeSuccessRateModel model) {
        if (model == null || normalize(model.stockCode()).isBlank()) return;
        String code = normalize(model.stockCode());
        int version = nextVersion(code, SUCCESS_RATE);
        TradeSuccessRateModel stored = new TradeSuccessRateModel(code, version,
                model.generatedAt() == null ? LocalDateTime.now() : model.generatedAt(), model.provider(), model.model(),
                model.baseProbability(), model.confidenceWeight(), model.aiPriceMatchBonus(), model.aiPriceMismatchPenalty(),
                model.technicalMatchBonus(), model.technicalMismatchPenalty(), model.hardRiskPenalty(), model.warningPenalty(),
                model.minProbability(), model.maxProbability(), model.confidence(), model.summary(), model.reasons());
        successFallback.put(code, stored);
        save(code, SUCCESS_RATE, version, stored.generatedAt(), stored.confidence(), stored);
    }

    public void saveAnalysisModel(TradeAnalysisModel model) {
        if (model == null || normalize(model.stockCode()).isBlank()) return;
        String code = normalize(model.stockCode());
        int version = nextVersion(code, PLAN_ANALYSIS);
        TradeAnalysisModel stored = new TradeAnalysisModel(code, version,
                model.generatedAt() == null ? LocalDateTime.now() : model.generatedAt(), model.provider(), model.model(),
                model.summary(), model.buySuggestions(), model.sellSuggestions(), model.riskWarnings(), model.confidence());
        analysisFallback.put(code, stored);
        save(code, PLAN_ANALYSIS, version, stored.generatedAt(), stored.confidence(), stored);
    }

    private int nextVersion(String code, String type) {
        String sql = "SELECT COALESCE(MAX(version),0)+1 FROM trade_ai_model_snapshot WHERE stock_code=? AND model_type=?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setString(2, type);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) return row.getInt(1);
            }
        } catch (Exception ex) {
            log.info("交易模型版本读取失败，使用内存版本：{}", ex.getMessage());
        }
        return 1;
    }

    private void save(String code, String type, int version, LocalDateTime generatedAt,
                      java.math.BigDecimal confidence, Object model) {
        String sql = "INSERT INTO trade_ai_model_snapshot(stock_code,model_type,version,generated_at,model_json,confidence,status) VALUES(?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setString(2, type);
            statement.setInt(3, version);
            statement.setObject(4, generatedAt);
            statement.setString(5, objectMapper.writeValueAsString(model));
            statement.setBigDecimal(6, confidence == null ? java.math.BigDecimal.ZERO : confidence);
            statement.setString(7, "EFFECTIVE");
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("交易模型写入失败，股票={}，类型={}：{}", code, type, ex.getMessage());
        }
    }

    private String normalize(String code) { return code == null ? "" : code.trim().toUpperCase(); }
}
