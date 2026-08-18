package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.StockPoolResponse;
import com.dolphin.stock.model.StockAnalysisModels.StockPoolItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class RecommendationSnapshotStore {
    private static final Logger log = LoggerFactory.getLogger(RecommendationSnapshotStore.class);
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    public RecommendationSnapshotStore(ObjectMapper objectMapper, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    /**
     * Reports which parts of the AI result are missing from a recommendation.
     * A cache row can exist while containing an unavailable result, so checking
     * only whether a row was loaded is not sufficient.
     */
    public AiAnalysisStatus inspect(StockPoolResponse response) {
        if (response == null || response.items() == null) {
            return new AiAnalysisStatus(false, 0, 0, List.of("response"), List.of("response"));
        }
        int complete = 0;
        List<String> missingCompany = new java.util.ArrayList<>();
        List<String> missingPrice = new java.util.ArrayList<>();
        for (StockPoolItem item : response.items()) {
            String code = item == null || item.stock() == null || item.stock().code() == null
                    ? "unknown" : item.stock().code();
            boolean companyAvailable = item != null && item.companyAnalysis() != null
                    && item.companyAnalysis().available();
            boolean priceAvailable = item != null && item.stockContext() != null
                    && item.stockContext().priceAdvice() != null
                    && item.stockContext().priceAdvice().available();
            if (!companyAvailable) missingCompany.add(code);
            if (!priceAvailable) missingPrice.add(code);
            if (companyAvailable && priceAvailable) complete++;
        }
        return new AiAnalysisStatus(complete == response.items().size(), response.items().size(), complete,
                List.copyOf(missingCompany), List.copyOf(missingPrice));
    }

    public record AiAnalysisStatus(boolean complete, int total, int completeCount,
                                   List<String> missingCompanyAnalysis,
                                   List<String> missingPriceAdvice) {}

    public boolean save(StockPoolResponse response, LocalDate asOf, BigDecimal minPrice, BigDecimal maxPrice,
                        String slot, String source) {
        AiAnalysisStatus aiStatus = inspect(response);
        if (!aiStatus.complete()) {
            log.error("推荐快照拒绝写入：AI结果不完整，总数={}，完整={}，公司分析缺失={}，价格分析缺失={}",
                    aiStatus.total(), aiStatus.completeCount(), aiStatus.missingCompanyAnalysis(),
                    aiStatus.missingPriceAdvice());
            return false;
        }
        String sql = "INSERT INTO recommendation_snapshot(strategy_id,snapshot_date,slot_code,source,min_price,max_price,generated_at,status,response_json) "
                + "VALUES(?,?,?,?,?,?,?,?,?)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "trend-growth-100");
            statement.setObject(2, asOf);
            statement.setString(3, slot);
            statement.setString(4, source);
            statement.setBigDecimal(5, minPrice);
            statement.setBigDecimal(6, maxPrice);
            statement.setObject(7, response.snapshot() == null || response.snapshot().generatedAt() == null
                    ? LocalDateTime.now() : response.snapshot().generatedAt());
            statement.setString(8, "SUCCESS");
            statement.setString(9, objectMapper.writeValueAsString(response));
            statement.executeUpdate();
            return true;
        } catch (Exception ex) {
            log.warn("推荐中心分析结果写入数据库失败: {}", ex.getMessage());
            return false;
        }
    }

    public Optional<StockPoolResponse> latest(LocalDate asOf, BigDecimal minPrice, BigDecimal maxPrice) {
        String sql = "SELECT response_json FROM recommendation_snapshot "
                + "WHERE strategy_id=? AND snapshot_date=? AND min_price=? AND max_price=? AND status='SUCCESS' "
                + "ORDER BY generated_at DESC, id DESC LIMIT 1";
        return load(sql, statement -> {
            statement.setString(1, "trend-growth-100");
            statement.setObject(2, asOf);
            statement.setBigDecimal(3, minPrice);
            statement.setBigDecimal(4, maxPrice);
        });
    }

    public Optional<StockPoolResponse> latestSlot(LocalDate asOf, BigDecimal minPrice, BigDecimal maxPrice, String slot) {
        String sql = "SELECT response_json FROM recommendation_snapshot "
                + "WHERE strategy_id=? AND snapshot_date=? AND min_price=? AND max_price=? AND slot_code=? AND status='SUCCESS' "
                + "ORDER BY generated_at DESC, id DESC LIMIT 1";
        return load(sql, statement -> {
            statement.setString(1, "trend-growth-100");
            statement.setObject(2, asOf);
            statement.setBigDecimal(3, minPrice);
            statement.setBigDecimal(4, maxPrice);
            statement.setString(5, slot);
        });
    }

    private Optional<StockPoolResponse> load(String sql, Binder binder) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    StockPoolResponse response = objectMapper.readValue(result.getString("response_json"), StockPoolResponse.class);
                    AiAnalysisStatus aiStatus = inspect(response);
                    if (!aiStatus.complete()) {
                        log.warn("忽略历史推荐快照：AI结果不完整，总数={}，完整={}，公司分析缺失={}，价格分析缺失={}",
                                aiStatus.total(), aiStatus.completeCount(), aiStatus.missingCompanyAnalysis(),
                                aiStatus.missingPriceAdvice());
                        return Optional.empty();
                    }
                    return Optional.of(response);
                }
            }
        } catch (Exception ex) {
            log.info("推荐中心历史快照暂不可用: {}", ex.getMessage());
        }
        return Optional.empty();
    }

    private Connection open() throws Exception { return dataSource.getConnection(); }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement statement) throws Exception; }
}
