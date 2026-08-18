package com.dolphin.stock.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/** 生产环境关闭了 Spring SQL init，因此对本功能新增表做幂等初始化。 */
@Component
public class DatabaseSchemaInitializer {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);
    private final DataSource dataSource;

    public DatabaseSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initialize() {
        String planned = "CREATE TABLE IF NOT EXISTS planned_order ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, account_id VARCHAR(64) NOT NULL, stock_code VARCHAR(16) NOT NULL, "
                + "planned_date DATE NOT NULL, side VARCHAR(8) NOT NULL DEFAULT 'BUY', planned_price DECIMAL(18,4) NOT NULL, "
                + "quantity DECIMAL(20,4) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', executed_price DECIMAL(18,4), "
                + "executed_quantity DECIMAL(20,4), analysis_json JSON, confirmed_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "KEY idx_planned_account_stock_status (account_id, stock_code, status), KEY idx_planned_date (planned_date))";
        String trade = "CREATE TABLE IF NOT EXISTS trade_execution ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, account_id VARCHAR(64) NOT NULL, stock_code VARCHAR(16) NOT NULL, "
                + "trade_date DATE NOT NULL, side VARCHAR(8) NOT NULL, planned_price DECIMAL(18,4), executed_price DECIMAL(18,4) NOT NULL, "
                + "quantity DECIMAL(20,4) NOT NULL, amount DECIMAL(20,4) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'MANUAL_CONFIRMED', "
                + "analysis_json JSON, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_trade_account_date (account_id, trade_date), "
                + "KEY idx_trade_stock_date (stock_code, trade_date))";
        String accuracy = "CREATE TABLE IF NOT EXISTS accuracy_snapshot ("
                + "stock_code VARCHAR(16) PRIMARY KEY, prediction_samples INT NOT NULL DEFAULT 0, prediction_correct INT NOT NULL DEFAULT 0, "
                + "prediction_rate DECIMAL(8,2), prediction_label VARCHAR(32) NOT NULL, prediction_method VARCHAR(255) NOT NULL, "
                + "operation_samples INT NOT NULL DEFAULT 0, operation_correct INT NOT NULL DEFAULT 0, operation_rate DECIMAL(8,2), "
                + "operation_label VARCHAR(32) NOT NULL, operation_method VARCHAR(255) NOT NULL, calculated_at DATETIME NOT NULL, "
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, KEY idx_accuracy_calculated_at (calculated_at))";
        String news = "CREATE TABLE IF NOT EXISTS news_announcement ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, stock_code VARCHAR(16), title VARCHAR(512) NOT NULL, content TEXT, "
                + "source VARCHAR(128), published_at DATETIME NOT NULL, url VARCHAR(1024), content_hash CHAR(64), event_type VARCHAR(64), "
                + "sentiment DECIMAL(8,4), ai_summary TEXT, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "KEY idx_news_code_time (stock_code, published_at), KEY idx_news_hash (content_hash))";
        String account = "CREATE TABLE IF NOT EXISTS account_profile ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, account_id VARCHAR(64) NOT NULL, total_assets DECIMAL(20,4) NOT NULL, "
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_account_profile_account (account_id))";
        String aiProvider = "CREATE TABLE IF NOT EXISTS ai_provider_config ("
                + "id BIGINT PRIMARY KEY, provider VARCHAR(64) NOT NULL, model VARCHAR(128) NOT NULL, "
                + "base_url VARCHAR(512) NOT NULL, api_key VARCHAR(512), enabled TINYINT(1) NOT NULL DEFAULT 1, "
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)";
        String companyProfile = "CREATE TABLE IF NOT EXISTS company_profile_cache ("
                + "stock_code VARCHAR(16) PRIMARY KEY, analyzed_date DATE NOT NULL, model_version INT NOT NULL DEFAULT 1, provider VARCHAR(64) NOT NULL, "
                + "model VARCHAR(128) NOT NULL, business_description TEXT NOT NULL, outlook TEXT NOT NULL, "
                + "future_trend VARCHAR(32) NOT NULL, risk TEXT NOT NULL, confidence DECIMAL(8,4) NOT NULL DEFAULT 0, "
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "KEY idx_company_profile_date (analyzed_date))";
        String aiRealtime = "CREATE TABLE IF NOT EXISTS ai_realtime_cache ("
                + "stock_code VARCHAR(16) NOT NULL, analyzed_date DATE NOT NULL, model_version INT NOT NULL DEFAULT 1, company_json JSON NOT NULL, "
                + "price_advice_json JSON NOT NULL, factor_scores_json JSON, generated_at DATETIME NOT NULL, "
                + "PRIMARY KEY (stock_code, analyzed_date), KEY idx_ai_realtime_generated (generated_at))";
        String analysisDataSnapshot = "CREATE TABLE IF NOT EXISTS analysis_data_snapshot ("
                + "stock_code VARCHAR(16) NOT NULL, analyzed_date DATE NOT NULL, data_json JSON NOT NULL, "
                + "source_summary VARCHAR(512) NOT NULL, generated_at DATETIME NOT NULL, PRIMARY KEY (stock_code, analyzed_date), "
                + "KEY idx_analysis_snapshot_generated (generated_at))";
        String portfolioAnalysis = "CREATE TABLE IF NOT EXISTS portfolio_analysis_snapshot ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, account_id VARCHAR(64) NOT NULL, analyzed_date DATE NOT NULL, model_version INT NOT NULL DEFAULT 1, "
                + "generated_at DATETIME NOT NULL, result_json JSON NOT NULL, "
                + "UNIQUE KEY uk_portfolio_analysis_account_date (account_id, analyzed_date))";
        String scoringModel = "CREATE TABLE IF NOT EXISTS scoring_model_snapshot ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, strategy_id VARCHAR(64) NOT NULL, version INT NOT NULL, "
                + "generated_at DATETIME NOT NULL, model_json JSON NOT NULL, adjustment_summary VARCHAR(1024), "
                + "confidence DECIMAL(8,4) NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'EFFECTIVE', "
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_scoring_model_version (strategy_id, version), "
                + "KEY idx_scoring_model_latest (strategy_id, status, version))";
        String tradeAiModel = "CREATE TABLE IF NOT EXISTS trade_ai_model_snapshot ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, stock_code VARCHAR(16) NOT NULL, model_type VARCHAR(32) NOT NULL, "
                + "version INT NOT NULL, generated_at DATETIME NOT NULL, model_json JSON NOT NULL, confidence DECIMAL(8,4) NOT NULL DEFAULT 0, "
                + "status VARCHAR(16) NOT NULL DEFAULT 'EFFECTIVE', created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_trade_ai_model_version (stock_code, model_type, version), "
                + "KEY idx_trade_ai_model_latest (stock_code, model_type, status, generated_at))";
        String modelUsage = "CREATE TABLE IF NOT EXISTS model_usage_log ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, model_key VARCHAR(64) NOT NULL, model_version INT NOT NULL, "
                + "stock_code VARCHAR(16) NOT NULL DEFAULT '', operation VARCHAR(32) NOT NULL, usage_count BIGINT NOT NULL DEFAULT 1, "
                + "first_used_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, last_used_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_model_usage (model_key, model_version, stock_code, operation), KEY idx_model_usage_latest (model_key, model_version, last_used_at))";
        String systemRuntime = "CREATE TABLE IF NOT EXISTS system_runtime_state ("
                + "state_key VARCHAR(64) PRIMARY KEY, initialized_at DATETIME NOT NULL)";
        String recommendation = "CREATE TABLE IF NOT EXISTS recommendation_snapshot ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, strategy_id VARCHAR(64) NOT NULL, snapshot_date DATE NOT NULL, "
                + "slot_code VARCHAR(16) NOT NULL, source VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED', "
                + "min_price DECIMAL(18,4) NOT NULL, max_price DECIMAL(18,4) NOT NULL, generated_at DATETIME NOT NULL, "
                + "status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS', response_json JSON NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "KEY idx_recommendation_latest (strategy_id, snapshot_date, min_price, max_price, generated_at), "
                + "KEY idx_recommendation_slot (strategy_id, snapshot_date, slot_code, generated_at))";
        String marketDataSource = "CREATE TABLE IF NOT EXISTS market_data_source ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, source_key VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL, "
                + "purpose VARCHAR(16) NOT NULL, adapter VARCHAR(32) NOT NULL, enabled TINYINT(1) NOT NULL DEFAULT 1, "
                + "priority INT NOT NULL DEFAULT 100, endpoint VARCHAR(1024) NOT NULL, timeout_seconds INT NOT NULL DEFAULT 8, "
                + "retry_count INT NOT NULL DEFAULT 1, user_agent VARCHAR(512), referer VARCHAR(1024), "
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_market_data_source_key (source_key), KEY idx_market_data_source_purpose (purpose, enabled, priority))";
        String defaultMarketDataSources = "INSERT INTO market_data_source(source_key,name,purpose,adapter,enabled,priority,endpoint,timeout_seconds,retry_count,user_agent,referer) VALUES "
                + "('eastmoney-realtime','东方财富实时行情','REALTIME','EASTMONEY',1,10,'https://push2.eastmoney.com/api/qt/ulist.np/get',8,1,'Mozilla/5.0 DolphinStock/1.0','https://quote.eastmoney.com/'),"
                + "('tencent-realtime','腾讯实时行情','REALTIME','TENCENT',1,20,'https://qt.gtimg.cn/q=',8,1,'Mozilla/5.0 DolphinStock/1.0','https://gu.qq.com/'),"
                + "('sina-realtime','新浪实时行情','REALTIME','SINA',1,30,'https://hq.sinajs.cn/list=',8,1,'Mozilla/5.0 DolphinStock/1.0','https://finance.sina.com.cn/'),"
                + "('eastmoney-universe','东方财富全市场扫描','UNIVERSE','EASTMONEY',1,10,'https://push2.eastmoney.com/api/qt/clist/get',8,1,'Mozilla/5.0 DolphinStock/1.0','https://quote.eastmoney.com/'),"
                + "('tencent-universe','腾讯全市场扫描','UNIVERSE','TENCENT',1,20,'https://qt.gtimg.cn/q=',8,1,'Mozilla/5.0 DolphinStock/1.0','https://gu.qq.com/') "
                + "ON DUPLICATE KEY UPDATE source_key=VALUES(source_key)";
        String defaultAiProvider = "INSERT INTO ai_provider_config(id, provider, model, base_url, api_key, enabled) "
                + "SELECT 1, 'DeepSeek', 'deepseek-v4-pro', 'https://api.deepseek.com', NULL, 1 "
                + "WHERE NOT EXISTS (SELECT 1 FROM ai_provider_config WHERE id=1)";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(planned);
            statement.executeUpdate(trade);
            statement.executeUpdate(accuracy);
            statement.executeUpdate(news);
            statement.executeUpdate(account);
            statement.executeUpdate(aiProvider);
            statement.executeUpdate(companyProfile);
            statement.executeUpdate(aiRealtime);
            statement.executeUpdate(analysisDataSnapshot);
            statement.executeUpdate(portfolioAnalysis);
            statement.executeUpdate(scoringModel);
            statement.executeUpdate(tradeAiModel);
            statement.executeUpdate(modelUsage);
            statement.executeUpdate(systemRuntime);
            statement.executeUpdate(defaultAiProvider);
            statement.executeUpdate(recommendation);
            statement.executeUpdate(marketDataSource);
            statement.executeUpdate(defaultMarketDataSources);
            String[] modelVersionColumns = {
                    "ALTER TABLE company_profile_cache ADD COLUMN model_version INT NOT NULL DEFAULT 1 AFTER analyzed_date",
                    "ALTER TABLE ai_realtime_cache ADD COLUMN model_version INT NOT NULL DEFAULT 1 AFTER analyzed_date",
                    "ALTER TABLE portfolio_analysis_snapshot ADD COLUMN model_version INT NOT NULL DEFAULT 1 AFTER analyzed_date"
            };
            for (String alter : modelVersionColumns) {
                try {
                    statement.executeUpdate(alter);
                } catch (Exception ignored) {
                    // 已存在该列。
                }
            }
            try {
                statement.executeUpdate("ALTER TABLE factor_snapshot ADD COLUMN ai_suggestion_score DECIMAL(8,4) AFTER quality_valuation_score");
            } catch (Exception ignored) {
                // 已存在该列，或当前数据库尚未创建快照表；schema.sql/后续初始化会处理。
            }
            String[] valueModelColumns = {
                    "business_model_score", "industry_prospect_score", "competitive_advantage_score",
                    "financial_quality_score", "growth_score", "valuation_score", "catalyst_score", "risk_score"
            };
            for (String column : valueModelColumns) {
                try {
                    statement.executeUpdate("ALTER TABLE factor_snapshot ADD COLUMN " + column + " DECIMAL(8,4)");
                } catch (Exception ignored) {
                    // 已存在该列。
                }
            }
            log.info("计划操作、交易记录、准确率快照、账户资产、AI接入、公司资料缓存、AI批处理缓存和行情源配置表已就绪");
        } catch (Exception ex) {
            log.warn("新增业务表初始化失败，接口将暂时使用内存回退；请检查数据库连接或执行 db/schema.sql: {}", ex.getMessage());
        }
    }
}
