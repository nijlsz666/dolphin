package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.AiCompanyAnalysis;
import com.dolphin.stock.model.StockAnalysisModels.AiTradeAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 只在后台批处理阶段写入，页面请求只读取这里的快照。
 */
@Component
public class AiRealtimeCacheStore {
    private static final Logger log = LoggerFactory.getLogger(AiRealtimeCacheStore.class);
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final Map<String, Cached> fallback = new ConcurrentHashMap<>();

    public AiRealtimeCacheStore(ObjectMapper objectMapper, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    public record Cached(String stockCode, LocalDate analyzedDate, int modelVersion, AiCompanyAnalysis companyAnalysis,
                         AiTradeAdvice priceAdvice, AiAnalysisService.AiFactorScores factorScores,
                         LocalDateTime generatedAt) {
        public static Cached unavailable(String code, LocalDate date) {
            return new Cached(code, date, 0, AiCompanyAnalysis.unavailable(), AiTradeAdvice.unavailable(), null, null);
        }
    }

    public Optional<Cached> load(String stockCode, LocalDate asOf) {
        String code = normalize(stockCode);
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        if (code.isBlank()) return Optional.empty();
        String sql = "SELECT analyzed_date, model_version, company_json, price_advice_json, factor_scores_json, generated_at "
                + "FROM ai_realtime_cache WHERE stock_code=? AND analyzed_date<=? "
                + "ORDER BY model_version DESC, analyzed_date DESC, generated_at DESC LIMIT 1";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setObject(2, date);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    String factorJson = row.getString("factor_scores_json");
                    AiAnalysisService.AiFactorScores factorScores = null;
                    if (factorJson != null) {
                        var factorNode = objectMapper.readTree(factorJson);
                        // Old 4-factor cache rows must not be interpreted as zeros
                        // under the new 8-module value-quality model.
                        if (factorNode.has("businessModel") && factorNode.has("industryProspect")
                                && factorNode.has("competitiveAdvantage") && factorNode.has("financialQuality")
                                && factorNode.has("growth") && factorNode.has("valuation")
                                && factorNode.has("catalyst") && factorNode.has("risk")) {
                            factorScores = objectMapper.treeToValue(factorNode, AiAnalysisService.AiFactorScores.class);
                        }
                    }
                    Cached value = new Cached(code, row.getDate("analyzed_date").toLocalDate(), row.getInt("model_version"),
                            objectMapper.readValue(row.getString("company_json"), AiCompanyAnalysis.class),
                            objectMapper.readValue(row.getString("price_advice_json"), AiTradeAdvice.class),
                            factorScores,
                            row.getTimestamp("generated_at") == null ? null : row.getTimestamp("generated_at").toLocalDateTime());
                    fallback.put(key(code, value.analyzedDate()), value);
                    return Optional.of(value);
                }
                return Optional.empty();
            }
        } catch (Exception ex) {
            log.info("AI实时缓存表暂不可用，使用内存缓存：{}", ex.getMessage());
            return fallback.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(code + "|"))
                    .filter(entry -> !entry.getValue().analyzedDate().isAfter(date))
                    .map(Map.Entry::getValue)
                    .max(java.util.Comparator.comparingInt(Cached::modelVersion)
                            .thenComparing(Cached::analyzedDate)
                            .thenComparing(value -> value.generatedAt() == null ? LocalDateTime.MIN : value.generatedAt()));
        }
    }

    public void save(LocalDate date, String stockCode, AiCompanyAnalysis companyAnalysis,
                     AiTradeAdvice priceAdvice, AiAnalysisService.AiFactorScores factorScores) {
        String code = normalize(stockCode);
        LocalDate analyzedDate = date == null ? LocalDate.now() : date;
        if (code.isBlank()) return;
        int modelVersion = nextVersion(code);
        Cached value = new Cached(code, analyzedDate, modelVersion,
                companyAnalysis == null ? AiCompanyAnalysis.unavailable() : companyAnalysis,
                priceAdvice == null ? AiTradeAdvice.unavailable() : priceAdvice,
                factorScores, LocalDateTime.now());
        fallback.put(key(code, analyzedDate), value);
        String sql = "INSERT INTO ai_realtime_cache(stock_code,analyzed_date,model_version,company_json,price_advice_json,factor_scores_json,generated_at) "
                + "VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE model_version=VALUES(model_version),company_json=VALUES(company_json),price_advice_json=VALUES(price_advice_json),"
                + "factor_scores_json=VALUES(factor_scores_json),generated_at=VALUES(generated_at)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setObject(2, analyzedDate);
            statement.setInt(3, modelVersion);
            statement.setString(4, objectMapper.writeValueAsString(value.companyAnalysis()));
            statement.setString(5, objectMapper.writeValueAsString(value.priceAdvice()));
            statement.setString(6, factorScores == null ? null : objectMapper.writeValueAsString(factorScores));
            statement.setObject(7, value.generatedAt());
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("AI实时缓存写入失败，股票={}：{}", code, ex.getMessage());
        }
    }

    private String key(String code, LocalDate date) { return code + "|" + date; }
    private String normalize(String code) { return code == null ? "" : code.trim().toUpperCase(); }
    private Connection open() throws Exception { return dataSource.getConnection(); }

    private int nextVersion(String code) {
        String sql = "SELECT COALESCE(MAX(model_version),0)+1 FROM ai_realtime_cache WHERE stock_code=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) return Math.max(1, row.getInt(1));
            }
        } catch (Exception ex) {
            return fallback.values().stream().filter(value -> code.equals(value.stockCode()))
                    .mapToInt(Cached::modelVersion).max().orElse(0) + 1;
        }
        return 1;
    }
}
