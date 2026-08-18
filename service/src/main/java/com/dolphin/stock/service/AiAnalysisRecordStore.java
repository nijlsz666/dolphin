package com.dolphin.stock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dolphin.stock.model.StockAnalysisModels.AiTradeAdvice;
import com.dolphin.stock.model.StockAnalysisModels.AiCompanyAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.Map;

@Component
public class AiAnalysisRecordStore {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisRecordStore.class);
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    public AiAnalysisRecordStore(ObjectMapper objectMapper, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    public void saveNews(long newsId, NewsAiResult result, String input, String provider, String model, String promptVersion) {
        String sql = "INSERT INTO ai_analysis_record(stock_code,as_of_date,provider,model,prompt_version,input_hash,input_json,output_json,confidence) "
                + "VALUES(?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, result.stockCode() == null || result.stockCode().isBlank() ? "MARKET" : result.stockCode());
            statement.setObject(2, LocalDate.now());
            statement.setString(3, provider);
            statement.setString(4, model);
            statement.setString(5, promptVersion);
            statement.setString(6, Integer.toHexString(input.hashCode()) + "-news-" + newsId);
            statement.setString(7, objectMapper.writeValueAsString(Map.of("newsId", newsId, "input", input)));
            statement.setString(8, objectMapper.writeValueAsString(result));
            statement.setBigDecimal(9, result.confidence());
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("AI分析记录写入失败，新闻ID={}: {}", newsId, ex.getMessage());
        }
    }

    public void saveStockAdvice(String stockCode, AiTradeAdvice result, String input,
                                String provider, String model, String promptVersion) {
        String sql = "INSERT INTO ai_analysis_record(stock_code,as_of_date,provider,model,prompt_version,input_hash,input_json,output_json,confidence) "
                + "VALUES(?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stockCode == null || stockCode.isBlank() ? "UNKNOWN" : stockCode);
            statement.setObject(2, LocalDate.now());
            statement.setString(3, provider);
            statement.setString(4, model);
            statement.setString(5, promptVersion);
            statement.setString(6, Integer.toHexString(input.hashCode()) + "-price");
            statement.setString(7, objectMapper.writeValueAsString(Map.of("stockCode", stockCode, "input", input)));
            statement.setString(8, objectMapper.writeValueAsString(result));
            statement.setBigDecimal(9, result.confidence());
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("AI价格建议记录写入失败，股票={}: {}", stockCode, ex.getMessage());
        }
    }

    public void saveCompanyAnalysis(String stockCode, AiCompanyAnalysis result, String input,
                                    String provider, String model, String promptVersion) {
        String sql = "INSERT INTO ai_analysis_record(stock_code,as_of_date,provider,model,prompt_version,input_hash,input_json,output_json,confidence) "
                + "VALUES(?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stockCode == null || stockCode.isBlank() ? "UNKNOWN" : stockCode);
            statement.setObject(2, LocalDate.now());
            statement.setString(3, provider);
            statement.setString(4, model);
            statement.setString(5, promptVersion);
            statement.setString(6, Integer.toHexString(input.hashCode()) + "-company");
            statement.setString(7, objectMapper.writeValueAsString(Map.of("stockCode", stockCode, "input", input)));
            statement.setString(8, objectMapper.writeValueAsString(result));
            statement.setBigDecimal(9, result.confidence());
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("AI公司分析记录写入失败，股票={}: {}", stockCode, ex.getMessage());
        }
    }

    public record NewsAiResult(String stockCode, String eventType, BigDecimal sentiment, String summary,
                               String riskLevel, BigDecimal confidence) {}
}
