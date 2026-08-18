package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.AiCompanyAnalysis;
import com.dolphin.stock.model.StockAnalysisModels.StockMarket;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores the normalized inputs used by the value-quality model. Network data and
 * AI results are written here by scheduled jobs so request-time analysis can be
 * audited and reproduced from a dated snapshot.
 */
@Component
public class AnalysisDataSnapshotStore {
    private static final Logger log = LoggerFactory.getLogger(AnalysisDataSnapshotStore.class);
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    public AnalysisDataSnapshotStore(ObjectMapper objectMapper, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    public void save(LocalDate date, StockMarket stock, AiCompanyAnalysis company,
                     AiAnalysisService.AiFactorScores scores,
                     List<NewsHotspotStore.News> news, String sourceSummary) {
        if (stock == null || stock.code() == null || stock.code().isBlank()) return;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stock", stock);
            data.put("companyAnalysis", company);
            data.put("modelScores", scores);
            data.put("news", news == null ? List.of() : news.stream()
                    .filter(item -> item.code() == null || item.code().equalsIgnoreCase(stock.code()))
                    .limit(20).toList());
            String sql = "INSERT INTO analysis_data_snapshot(stock_code,analyzed_date,data_json,source_summary,generated_at) "
                    + "VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE data_json=VALUES(data_json),source_summary=VALUES(source_summary),generated_at=VALUES(generated_at)";
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, stock.code());
                statement.setObject(2, date == null ? LocalDate.now() : date);
                statement.setString(3, objectMapper.writeValueAsString(data));
                statement.setString(4, sourceSummary);
                statement.setObject(5, LocalDateTime.now());
                statement.executeUpdate();
            }
        } catch (Exception ex) {
            log.warn("分析数据快照写入失败，股票={}: {}", stock.code(), ex.getMessage());
        }
    }

    public Optional<AiAnalysisService.AiFactorScores> loadModelScores(LocalDate date, String stockCode) {
        String sql = "SELECT data_json FROM analysis_data_snapshot WHERE stock_code=? AND analyzed_date<=? "
                + "ORDER BY analyzed_date DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stockCode);
            statement.setObject(2, date == null ? LocalDate.now() : date);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                var scores = objectMapper.readTree(row.getString("data_json")).path("modelScores");
                if (!scores.isObject()) return Optional.empty();
                return Optional.of(new AiAnalysisService.AiFactorScores(
                        scores.path("businessModel").asInt(), scores.path("industryProspect").asInt(),
                        scores.path("competitiveAdvantage").asInt(), scores.path("financialQuality").asInt(),
                        scores.path("growth").asInt(), scores.path("valuation").asInt(),
                        scores.path("catalyst").asInt(), scores.path("risk").asInt(),
                        scores.path("modelVersion").asInt(0)));
            }
        } catch (Exception ex) {
            log.info("分析数据快照读取失败，股票={}: {}", stockCode, ex.getMessage());
            return Optional.empty();
        }
    }
}
