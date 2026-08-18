package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.ScoringModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Optional;

/** Versioned, validated scoring model snapshots produced by the hourly AI governance job. */
@Component
public class ScoringModelStore {
    private static final Logger log = LoggerFactory.getLogger(ScoringModelStore.class);
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    public ScoringModelStore(ObjectMapper objectMapper, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    public Optional<ScoringModel> loadLatest() {
        String sql = "SELECT version, model_json FROM scoring_model_snapshot WHERE strategy_id=? AND status='EFFECTIVE' "
                + "ORDER BY version DESC";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "value-quality-100");
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    int rowVersion = row.getInt("version");
                    ScoringModel model = objectMapper.readValue(row.getString("model_json"), ScoringModel.class);
                    if (model.totalWeight() == 100 && model.businessModelWeight() + model.industryProspectWeight()
                            + model.competitiveAdvantageWeight() + model.financialQualityWeight() + model.growthWeight()
                            + model.valuationWeight() + model.catalystWeight() + model.riskWeight() == 100) {
                        if (model.version() != rowVersion) {
                            log.warn("评分模型快照版本不一致：数据库版本={}，模型内容版本={}", rowVersion, model.version());
                            continue;
                        }
                        return Optional.of(model);
                    }
                    log.info("数据库中的评分模型不是价值质量8模块模型，改用默认模型");
                }
            }
        } catch (Exception ex) {
            log.info("评分模型快照表暂不可用，使用默认模型：{}", ex.getMessage());
        }
        return Optional.empty();
    }

    public boolean save(ScoringModel model) {
        if (model == null) return false;
        String sql = "INSERT INTO scoring_model_snapshot(strategy_id,version,generated_at,model_json,adjustment_summary,confidence,status) "
                + "VALUES(?,?,?,?,?,?,?)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, model.strategyId());
            statement.setInt(2, model.version());
            statement.setObject(3, model.generatedAt() == null ? LocalDateTime.now() : model.generatedAt());
            statement.setString(4, objectMapper.writeValueAsString(model));
            statement.setString(5, model.adjustmentSummary());
            statement.setBigDecimal(6, model.confidence());
            statement.setString(7, "EFFECTIVE");
            statement.executeUpdate();
            return true;
        } catch (Exception ex) {
            log.warn("评分模型快照写入失败：{}", ex.getMessage());
            return false;
        }
    }

    private Connection open() throws Exception { return dataSource.getConnection(); }
}
