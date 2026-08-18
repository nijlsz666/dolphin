CREATE DATABASE IF NOT EXISTS dolphin_stock DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE dolphin_stock;

CREATE TABLE IF NOT EXISTS stock_basic (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(16) NOT NULL,
    name VARCHAR(64) NOT NULL,
    market VARCHAR(16) NOT NULL,
    industry VARCHAR(64),
    list_date DATE,
    delist_date DATE,
    is_st TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_code (code)
);

CREATE TABLE IF NOT EXISTS daily_quote (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_code VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    open_price DECIMAL(18,4), high_price DECIMAL(18,4), low_price DECIMAL(18,4), close_price DECIMAL(18,4) NOT NULL,
    pre_close DECIMAL(18,4), volume BIGINT, turnover DECIMAL(20,4), turnover_rate DECIMAL(12,6),
    change_percent DECIMAL(12,6), adj_factor DECIMAL(18,8), limit_up_price DECIMAL(18,4), limit_down_price DECIMAL(18,4),
    is_limit_up TINYINT(1) DEFAULT 0, is_limit_down TINYINT(1) DEFAULT 0, is_suspended TINYINT(1) DEFAULT 0,
    UNIQUE KEY uk_daily_quote (stock_code, trade_date), KEY idx_daily_date_code (trade_date, stock_code)
);

CREATE TABLE IF NOT EXISTS minute_quote (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_code VARCHAR(16) NOT NULL, trade_time DATETIME NOT NULL,
    open_price DECIMAL(18,4), high_price DECIMAL(18,4), low_price DECIMAL(18,4), close_price DECIMAL(18,4),
    volume BIGINT, turnover DECIMAL(20,4),
    UNIQUE KEY uk_minute_quote (stock_code, trade_time), KEY idx_minute_code_time (stock_code, trade_time), KEY idx_minute_time (trade_time)
);

CREATE TABLE IF NOT EXISTS adjusted_quote (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, stock_code VARCHAR(16) NOT NULL, trade_date DATE NOT NULL,
    adj_type VARCHAR(16) NOT NULL, open_price DECIMAL(18,4), high_price DECIMAL(18,4), low_price DECIMAL(18,4), close_price DECIMAL(18,4),
    factor DECIMAL(18,8) NOT NULL, UNIQUE KEY uk_adjusted_quote (stock_code, trade_date, adj_type)
);

CREATE TABLE IF NOT EXISTS financial_indicator (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, stock_code VARCHAR(16) NOT NULL, report_period DATE NOT NULL,
    publish_date DATE NOT NULL, available_at DATETIME NOT NULL,
    roe DECIMAL(12,6), net_profit DECIMAL(20,4), net_profit_growth DECIMAL(12,6), revenue_growth DECIMAL(12,6), debt_ratio DECIMAL(12,6),
    source VARCHAR(64), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_financial_period (stock_code, report_period), KEY idx_financial_available (stock_code, available_at)
);

CREATE TABLE IF NOT EXISTS news_announcement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, stock_code VARCHAR(16), published_at DATETIME NOT NULL,
    title VARCHAR(512) NOT NULL, content TEXT, source VARCHAR(128), url VARCHAR(1024), content_hash CHAR(64),
    event_type VARCHAR(64), sentiment DECIMAL(8,4), ai_summary TEXT, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_news_code_time (stock_code, published_at), KEY idx_news_hash (content_hash)
);

CREATE TABLE IF NOT EXISTS factor_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, stock_code VARCHAR(16) NOT NULL, as_of_date DATE NOT NULL,
    trend_score DECIMAL(8,4), momentum_score DECIMAL(8,4), volume_price_score DECIMAL(8,4), fundamental_score DECIMAL(8,4), capital_score DECIMAL(8,4), quality_valuation_score DECIMAL(8,4), ai_suggestion_score DECIMAL(8,4),
    business_model_score DECIMAL(8,4), industry_prospect_score DECIMAL(8,4), competitive_advantage_score DECIMAL(8,4), financial_quality_score DECIMAL(8,4), growth_score DECIMAL(8,4), valuation_score DECIMAL(8,4), catalyst_score DECIMAL(8,4), risk_score DECIMAL(8,4),
    raw_score DECIMAL(8,4), risk_penalty DECIMAL(8,4), final_score DECIMAL(8,4), buy_low DECIMAL(18,4), buy_high DECIMAL(18,4),
    factor_json JSON, data_version VARCHAR(64), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_factor_snapshot (stock_code, as_of_date, data_version)
);

CREATE TABLE IF NOT EXISTS ai_analysis_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, stock_code VARCHAR(16) NOT NULL, as_of_date DATE NOT NULL,
    provider VARCHAR(64) NOT NULL DEFAULT 'deepseek', model VARCHAR(128), prompt_version VARCHAR(64) NOT NULL,
    input_hash CHAR(64), input_json JSON, output_json JSON, confidence DECIMAL(8,4), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ai_stock_date (stock_code, as_of_date), KEY idx_ai_prompt_version (prompt_version)
);

CREATE TABLE IF NOT EXISTS trade_signal (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, strategy_id VARCHAR(64) NOT NULL, stock_code VARCHAR(16) NOT NULL,
    signal_date DATE NOT NULL, signal_type VARCHAR(16) NOT NULL, score DECIMAL(8,4), target_price DECIMAL(18,4),
    reason_json JSON, executable TINYINT(1) NOT NULL DEFAULT 0, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_signal_strategy_date (strategy_id, signal_date), KEY idx_signal_stock_date (stock_code, signal_date)
);

CREATE TABLE IF NOT EXISTS simulated_position (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, account_id VARCHAR(64) NOT NULL, stock_code VARCHAR(16) NOT NULL,
    quantity DECIMAL(20,4) NOT NULL DEFAULT 0, available_quantity DECIMAL(20,4) NOT NULL DEFAULT 0,
    avg_cost DECIMAL(18,4) NOT NULL, highest_price DECIMAL(18,4), opened_at DATE NOT NULL, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_position_account_stock (account_id, stock_code)
);

CREATE TABLE IF NOT EXISTS account_profile (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id VARCHAR(64) NOT NULL,
    total_assets DECIMAL(20,4) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_account_profile_account (account_id)
);

CREATE TABLE IF NOT EXISTS ai_provider_config (
    id BIGINT PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    api_key VARCHAR(512),
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS company_profile_cache (
    stock_code VARCHAR(16) PRIMARY KEY,
    analyzed_date DATE NOT NULL, model_version INT NOT NULL DEFAULT 1,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    business_description TEXT NOT NULL,
    outlook TEXT NOT NULL,
    future_trend VARCHAR(32) NOT NULL,
    risk TEXT NOT NULL,
    confidence DECIMAL(8,4) NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_company_profile_date (analyzed_date)
);

CREATE TABLE IF NOT EXISTS ai_realtime_cache (
    stock_code VARCHAR(16) NOT NULL, analyzed_date DATE NOT NULL, model_version INT NOT NULL DEFAULT 1, company_json JSON NOT NULL,
    price_advice_json JSON NOT NULL, factor_scores_json JSON, generated_at DATETIME NOT NULL,
    PRIMARY KEY (stock_code, analyzed_date), KEY idx_ai_realtime_generated (generated_at)
);

CREATE TABLE IF NOT EXISTS analysis_data_snapshot (
    stock_code VARCHAR(16) NOT NULL,
    analyzed_date DATE NOT NULL,
    data_json JSON NOT NULL,
    source_summary VARCHAR(512) NOT NULL,
    generated_at DATETIME NOT NULL,
    PRIMARY KEY (stock_code, analyzed_date),
    KEY idx_analysis_snapshot_generated (generated_at)
);

CREATE TABLE IF NOT EXISTS portfolio_analysis_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, account_id VARCHAR(64) NOT NULL, analyzed_date DATE NOT NULL, model_version INT NOT NULL DEFAULT 1,
    generated_at DATETIME NOT NULL, result_json JSON NOT NULL,
    UNIQUE KEY uk_portfolio_analysis_account_date (account_id, analyzed_date)
);

CREATE TABLE IF NOT EXISTS scoring_model_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, strategy_id VARCHAR(64) NOT NULL, version INT NOT NULL,
    generated_at DATETIME NOT NULL, model_json JSON NOT NULL, adjustment_summary VARCHAR(1024),
    confidence DECIMAL(8,4) NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'EFFECTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_scoring_model_version (strategy_id, version), KEY idx_scoring_model_latest (strategy_id, status, version)
);

CREATE TABLE IF NOT EXISTS trade_ai_model_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, stock_code VARCHAR(16) NOT NULL, model_type VARCHAR(32) NOT NULL,
    version INT NOT NULL, generated_at DATETIME NOT NULL, model_json JSON NOT NULL,
    confidence DECIMAL(8,4) NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'EFFECTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trade_ai_model_version (stock_code, model_type, version),
    KEY idx_trade_ai_model_latest (stock_code, model_type, status, generated_at)
);

CREATE TABLE IF NOT EXISTS model_usage_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    model_key VARCHAR(64) NOT NULL,
    model_version INT NOT NULL,
    stock_code VARCHAR(16) NOT NULL DEFAULT '',
    operation VARCHAR(32) NOT NULL,
    usage_count BIGINT NOT NULL DEFAULT 1,
    first_used_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_usage (model_key, model_version, stock_code, operation),
    KEY idx_model_usage_latest (model_key, model_version, last_used_at)
);

INSERT INTO ai_provider_config(id, provider, model, base_url, api_key, enabled)
SELECT 1, 'DeepSeek', 'deepseek-v4-pro', 'https://api.deepseek.com', NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM ai_provider_config WHERE id=1);

CREATE TABLE IF NOT EXISTS market_data_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_key VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    adapter VARCHAR(32) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    priority INT NOT NULL DEFAULT 100,
    endpoint VARCHAR(1024) NOT NULL,
    timeout_seconds INT NOT NULL DEFAULT 8,
    retry_count INT NOT NULL DEFAULT 1,
    user_agent VARCHAR(512),
    referer VARCHAR(1024),
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_market_data_source_key (source_key),
    KEY idx_market_data_source_purpose (purpose, enabled, priority)
);

INSERT INTO market_data_source(source_key,name,purpose,adapter,enabled,priority,endpoint,timeout_seconds,retry_count,user_agent,referer)
SELECT 'eastmoney-realtime','东方财富实时行情','REALTIME','EASTMONEY',1,10,'https://push2.eastmoney.com/api/qt/ulist.np/get',8,1,'Mozilla/5.0 DolphinStock/1.0','https://quote.eastmoney.com/'
WHERE NOT EXISTS (SELECT 1 FROM market_data_source WHERE source_key='eastmoney-realtime');
INSERT INTO market_data_source(source_key,name,purpose,adapter,enabled,priority,endpoint,timeout_seconds,retry_count,user_agent,referer)
SELECT 'tencent-realtime','腾讯实时行情','REALTIME','TENCENT',1,20,'https://qt.gtimg.cn/q=',8,1,'Mozilla/5.0 DolphinStock/1.0','https://gu.qq.com/'
WHERE NOT EXISTS (SELECT 1 FROM market_data_source WHERE source_key='tencent-realtime');
INSERT INTO market_data_source(source_key,name,purpose,adapter,enabled,priority,endpoint,timeout_seconds,retry_count,user_agent,referer)
SELECT 'sina-realtime','新浪实时行情','REALTIME','SINA',1,30,'https://hq.sinajs.cn/list=',8,1,'Mozilla/5.0 DolphinStock/1.0','https://finance.sina.com.cn/'
WHERE NOT EXISTS (SELECT 1 FROM market_data_source WHERE source_key='sina-realtime');
INSERT INTO market_data_source(source_key,name,purpose,adapter,enabled,priority,endpoint,timeout_seconds,retry_count,user_agent,referer)
SELECT 'eastmoney-universe','东方财富全市场扫描','UNIVERSE','EASTMONEY',1,10,'https://push2.eastmoney.com/api/qt/clist/get',8,1,'Mozilla/5.0 DolphinStock/1.0','https://quote.eastmoney.com/'
WHERE NOT EXISTS (SELECT 1 FROM market_data_source WHERE source_key='eastmoney-universe');
INSERT INTO market_data_source(source_key,name,purpose,adapter,enabled,priority,endpoint,timeout_seconds,retry_count,user_agent,referer)
SELECT 'tencent-universe','腾讯全市场扫描','UNIVERSE','TENCENT',1,20,'https://qt.gtimg.cn/q=',8,1,'Mozilla/5.0 DolphinStock/1.0','https://gu.qq.com/'
WHERE NOT EXISTS (SELECT 1 FROM market_data_source WHERE source_key='tencent-universe');

CREATE TABLE IF NOT EXISTS planned_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id VARCHAR(64) NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    planned_date DATE NOT NULL,
    side VARCHAR(8) NOT NULL DEFAULT 'BUY',
    planned_price DECIMAL(18,4) NOT NULL,
    quantity DECIMAL(20,4) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    executed_price DECIMAL(18,4),
    executed_quantity DECIMAL(20,4),
    analysis_json JSON,
    confirmed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_planned_account_stock_status (account_id, stock_code, status),
    KEY idx_planned_date (planned_date)
);

CREATE TABLE IF NOT EXISTS trade_execution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id VARCHAR(64) NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    side VARCHAR(8) NOT NULL,
    planned_price DECIMAL(18,4),
    executed_price DECIMAL(18,4) NOT NULL,
    quantity DECIMAL(20,4) NOT NULL,
    amount DECIMAL(20,4) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'MANUAL_CONFIRMED',
    analysis_json JSON,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_trade_account_date (account_id, trade_date),
    KEY idx_trade_stock_date (stock_code, trade_date)
);

CREATE TABLE IF NOT EXISTS accuracy_snapshot (
    stock_code VARCHAR(16) PRIMARY KEY,
    prediction_samples INT NOT NULL DEFAULT 0,
    prediction_correct INT NOT NULL DEFAULT 0,
    prediction_rate DECIMAL(8,2),
    prediction_label VARCHAR(32) NOT NULL,
    prediction_method VARCHAR(255) NOT NULL,
    operation_samples INT NOT NULL DEFAULT 0,
    operation_correct INT NOT NULL DEFAULT 0,
    operation_rate DECIMAL(8,2),
    operation_label VARCHAR(32) NOT NULL,
    operation_method VARCHAR(255) NOT NULL,
    calculated_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_accuracy_calculated_at (calculated_at)
);

CREATE TABLE IF NOT EXISTS strategy_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, strategy_id VARCHAR(64) NOT NULL, version INT NOT NULL,
    config_json JSON NOT NULL, effective_from DATETIME NOT NULL, effective_to DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_strategy_version (strategy_id, version), KEY idx_strategy_effective (strategy_id, effective_from, effective_to)
);

CREATE TABLE IF NOT EXISTS stock_pool_membership (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, strategy_id VARCHAR(64) NOT NULL DEFAULT 'trend-growth-100',
    stock_code VARCHAR(16) NOT NULL, added_by VARCHAR(32) NOT NULL DEFAULT 'MANUAL', added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    removed_at DATETIME, note VARCHAR(255),
    KEY idx_pool_strategy_active (strategy_id, removed_at), UNIQUE KEY uk_pool_membership (strategy_id, stock_code, added_at)
);

CREATE TABLE IF NOT EXISTS backtest_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, strategy_id VARCHAR(64) NOT NULL, data_version VARCHAR(64) NOT NULL,
    start_date DATE NOT NULL, end_date DATE NOT NULL, universe_rule VARCHAR(512), status VARCHAR(16) NOT NULL,
    initial_cash DECIMAL(20,4), final_equity DECIMAL(20,4), cagr DECIMAL(12,6), max_drawdown DECIMAL(12,6), sharpe DECIMAL(12,6),
    win_rate DECIMAL(12,6), turnover DECIMAL(20,4), report_json JSON, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS backtest_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, run_id BIGINT NOT NULL, stock_code VARCHAR(16) NOT NULL,
    order_date DATE NOT NULL, side VARCHAR(8) NOT NULL, requested_price DECIMAL(18,4), executed_price DECIMAL(18,4),
    quantity DECIMAL(20,4), commission DECIMAL(18,4), stamp_tax DECIMAL(18,4), slippage DECIMAL(18,4), status VARCHAR(16), reason VARCHAR(255),
    KEY idx_backtest_order_run_date (run_id, order_date)
);

CREATE TABLE IF NOT EXISTS market_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, market VARCHAR(16) NOT NULL, board VARCHAR(32) NOT NULL,
    effective_from DATE NOT NULL, effective_to DATE, limit_percent DECIMAL(8,4) NOT NULL,
    buy_t_plus_days INT NOT NULL DEFAULT 0, stamp_tax_rate DECIMAL(12,8) NOT NULL DEFAULT 0,
    commission_rate DECIMAL(12,8) NOT NULL DEFAULT 0.0003, min_commission DECIMAL(18,4) NOT NULL DEFAULT 5,
    UNIQUE KEY uk_market_rule (market, board, effective_from)
);

INSERT INTO strategy_config(strategy_id, version, config_json, effective_from)
SELECT 'trend-growth-100', 1, '{"minScore":70,"minPrice":0.01,"maxPrice":500,"hardStopLoss":0.08,"trailingStopLoss":0.12,"sellScoreThreshold":60,"maxSinglePosition":1.00,"maxIndustryPosition":0.25,"maxTotalPosition":0.80}', NOW()
WHERE NOT EXISTS (SELECT 1 FROM strategy_config WHERE strategy_id='trend-growth-100' AND version=1);

CREATE TABLE IF NOT EXISTS recommendation_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy_id VARCHAR(64) NOT NULL,
    snapshot_date DATE NOT NULL,
    slot_code VARCHAR(16) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    min_price DECIMAL(18,4) NOT NULL,
    max_price DECIMAL(18,4) NOT NULL,
    generated_at DATETIME NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    response_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_recommendation_latest (strategy_id, snapshot_date, min_price, max_price, generated_at),
    KEY idx_recommendation_slot (strategy_id, snapshot_date, slot_code, generated_at)
);
CREATE TABLE IF NOT EXISTS system_runtime_state (
    state_key VARCHAR(64) PRIMARY KEY,
    initialized_at DATETIME NOT NULL
);
