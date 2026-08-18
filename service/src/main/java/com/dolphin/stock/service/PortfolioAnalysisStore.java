package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.PortfolioAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Optional;

@Component
public class PortfolioAnalysisStore {
    private static final Logger log = LoggerFactory.getLogger(PortfolioAnalysisStore.class);
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    public PortfolioAnalysisStore(ObjectMapper objectMapper, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    public Optional<PortfolioAnalysis> load(LocalDate asOf) {
        String sql = "SELECT result_json FROM portfolio_analysis_snapshot WHERE account_id=? AND analyzed_date=? LIMIT 1";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "default");
            statement.setObject(2, asOf == null ? LocalDate.now() : asOf);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) return Optional.of(objectMapper.readValue(row.getString("result_json"), PortfolioAnalysis.class));
            }
        } catch (Exception ex) {
            log.info("组合AI复盘缓存暂不可用：{}", ex.getMessage());
        }
        return Optional.empty();
    }

    public void save(LocalDate asOf, PortfolioAnalysis result) {
        if (result == null) return;
        int version = nextVersion();
        String sql = "INSERT INTO portfolio_analysis_snapshot(account_id,analyzed_date,model_version,generated_at,result_json) VALUES(?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE model_version=VALUES(model_version),generated_at=VALUES(generated_at),result_json=VALUES(result_json)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "default");
            statement.setObject(2, asOf == null ? LocalDate.now() : asOf);
            statement.setInt(3, version);
            statement.setObject(4, result.analyzedAt());
            statement.setString(5, objectMapper.writeValueAsString(result));
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("组合AI复盘缓存写入失败：{}", ex.getMessage());
        }
    }

    private int nextVersion() {
        String sql = "SELECT COALESCE(MAX(model_version),0)+1 FROM portfolio_analysis_snapshot WHERE account_id=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "default");
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) return Math.max(1, row.getInt(1));
            }
        } catch (Exception ex) {
            log.debug("组合复盘版本读取失败：{}", ex.getMessage());
        }
        return 1;
    }

    private Connection open() throws Exception { return dataSource.getConnection(); }
}
