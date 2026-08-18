package com.dolphin.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dolphin.stock.model.StockAnalysisModels.StrategyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Component
public class StrategyConfigStore {
    private static final Logger log = LoggerFactory.getLogger(StrategyConfigStore.class);
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final String strategyId = "trend-growth-100";

    public StrategyConfigStore(ObjectMapper objectMapper, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    public StrategyConfig load(StrategyConfig fallback) {
        String sql = "SELECT version, config_json FROM strategy_config WHERE strategy_id=? AND effective_to IS NULL ORDER BY version DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, strategyId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    int version = result.getInt(1);
                    JsonNode json = objectMapper.readTree(result.getString(2));
                    StrategyConfig normalized = normalize(objectMapper.treeToValue(json, StrategyConfig.class), fallback);
                    if (version <= 2 && isLegacyDefault(normalized)) {
                        StrategyConfig upgraded = withMaxSinglePosition(normalized, fallback.maxSinglePosition());
                        save(upgraded);
                        return upgraded;
                    }
                    return normalized;
                }
            }
        } catch (Exception ex) {
            log.info("策略配置表暂不可用，使用内存默认配置: {}", ex.getMessage());
        }
        return fallback;
    }

    private boolean isLegacyDefault(StrategyConfig config) {
        return config.maxSinglePosition() != null
                && (config.maxSinglePosition().compareTo(new java.math.BigDecimal("0.10")) == 0
                || config.maxSinglePosition().compareTo(new java.math.BigDecimal("0.15")) == 0)
                && config.maxIndustryPosition() != null
                && config.maxIndustryPosition().compareTo(new java.math.BigDecimal("0.25")) == 0
                && config.maxTotalPosition() != null
                && config.maxTotalPosition().compareTo(new java.math.BigDecimal("0.80")) == 0
                && config.hardStopLoss() != null
                && config.hardStopLoss().compareTo(new java.math.BigDecimal("0.08")) == 0
                && config.trailingStopLoss() != null
                && config.trailingStopLoss().compareTo(new java.math.BigDecimal("0.12")) == 0
                && config.sellScoreThreshold() == 60;
    }

    private StrategyConfig withMaxSinglePosition(StrategyConfig config, java.math.BigDecimal maxSinglePosition) {
        return new StrategyConfig(config.name(), config.minScore(), config.minPrice(), config.maxPrice(),
                config.minAverageTurnover(), config.minListingDays(), maxSinglePosition,
                config.maxIndustryPosition(), config.maxTotalPosition(), config.hardStopLoss(),
                config.trailingStopLoss(), config.sellScoreThreshold());
    }

    public void save(StrategyConfig config) {
        String versionSql = "SELECT COALESCE(MAX(version),0)+1 FROM strategy_config WHERE strategy_id=?";
        String insertSql = "INSERT INTO strategy_config(strategy_id,version,config_json,effective_from) VALUES(?,?,?,NOW())";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement version = connection.prepareStatement(versionSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            version.setString(1, strategyId);
            int nextVersion;
            try (ResultSet result = version.executeQuery()) {
                result.next();
                nextVersion = result.getInt(1);
            }
            insert.setString(1, strategyId);
            insert.setInt(2, nextVersion);
            insert.setString(3, objectMapper.writeValueAsString(config));
            insert.executeUpdate();
        } catch (Exception ex) {
            log.warn("策略价格区间写入 strategy_config 失败: {}", ex.getMessage());
        }
    }

    private StrategyConfig normalize(StrategyConfig loaded, StrategyConfig fallback) {
        return new StrategyConfig(
                loaded.name() == null ? fallback.name() : loaded.name(),
                loaded.minScore() <= 0 ? fallback.minScore() : loaded.minScore(),
                loaded.minPrice() == null ? fallback.minPrice() : loaded.minPrice(),
                loaded.maxPrice() == null ? fallback.maxPrice() : loaded.maxPrice(),
                loaded.minAverageTurnover() == null ? fallback.minAverageTurnover() : loaded.minAverageTurnover(),
                loaded.minListingDays() <= 0 ? fallback.minListingDays() : loaded.minListingDays(),
                loaded.maxSinglePosition() == null ? fallback.maxSinglePosition() : loaded.maxSinglePosition(),
                loaded.maxIndustryPosition() == null ? fallback.maxIndustryPosition() : loaded.maxIndustryPosition(),
                loaded.maxTotalPosition() == null ? fallback.maxTotalPosition() : loaded.maxTotalPosition(),
                loaded.hardStopLoss() == null ? fallback.hardStopLoss() : loaded.hardStopLoss(),
                loaded.trailingStopLoss() == null ? fallback.trailingStopLoss() : loaded.trailingStopLoss(),
                loaded.sellScoreThreshold() <= 0 ? fallback.sellScoreThreshold() : loaded.sellScoreThreshold());
    }
}
