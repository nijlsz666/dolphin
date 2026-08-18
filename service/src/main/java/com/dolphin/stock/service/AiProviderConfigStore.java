package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.AiProviderConfig;
import com.dolphin.stock.model.StockAnalysisModels.AiProviderUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class AiProviderConfigStore {
    private static final Logger log = LoggerFactory.getLogger(AiProviderConfigStore.class);
    private final DataSource dataSource;
    private volatile Stored fallback = new Stored("DeepSeek", "deepseek-v4-pro", "https://api.deepseek.com", "", true, null);

    public AiProviderConfigStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public AiProviderConfig load() {
        String sql = "SELECT provider, model, base_url, api_key, enabled, updated_at FROM ai_provider_config WHERE id=1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                Stored stored = new Stored(result.getString("provider"), result.getString("model"), result.getString("base_url"),
                        result.getString("api_key"), result.getBoolean("enabled"),
                        result.getTimestamp("updated_at") == null ? null : result.getTimestamp("updated_at").toLocalDateTime());
                fallback = stored;
                return view(stored);
            }
        } catch (Exception ex) {
            log.info("AI接入配置表暂不可用，使用内存配置: {}", ex.getMessage());
        }
        return view(fallback);
    }

    public Optional<AiProviderAccess> access() {
        String sql = "SELECT provider, model, base_url, api_key, enabled, updated_at FROM ai_provider_config WHERE id=1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                Stored stored = new Stored(result.getString("provider"), result.getString("model"), result.getString("base_url"),
                        result.getString("api_key"), result.getBoolean("enabled"),
                        result.getTimestamp("updated_at") == null ? null : result.getTimestamp("updated_at").toLocalDateTime());
                fallback = stored;
                return usable(stored);
            }
        } catch (Exception ex) {
            log.warn("读取 AI 配置表失败，本次禁止调用 AI，不使用内存回退配置: {}", ex.getMessage());
            return Optional.empty();
        }
        return Optional.empty();
    }

    public synchronized AiProviderConfig save(AiProviderUpdateRequest request) {
        if (request == null || blank(request.provider()) || blank(request.model()) || blank(request.baseUrl())) {
            throw new IllegalArgumentException("AI供应商、模型和接口地址不能为空");
        }
        Stored current = storedFromView(load());
        String apiKey = blank(request.apiKey()) ? current.apiKey() : request.apiKey().trim();
        Stored next = new Stored(request.provider().trim(), request.model().trim(), request.baseUrl().trim(), apiKey,
                request.enabled() == null || request.enabled(), LocalDateTime.now());
        String sql = "INSERT INTO ai_provider_config(id, provider, model, base_url, api_key, enabled, updated_at) VALUES(1,?,?,?,?,?,NOW()) "
                + "ON DUPLICATE KEY UPDATE provider=VALUES(provider), model=VALUES(model), base_url=VALUES(base_url), "
                + "api_key=VALUES(api_key), enabled=VALUES(enabled), updated_at=NOW()";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, next.provider());
            statement.setString(2, next.model());
            statement.setString(3, next.baseUrl());
            statement.setString(4, next.apiKey());
            statement.setBoolean(5, next.enabled());
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("AI接入配置写入数据库失败，暂存内存: {}", ex.getMessage());
        }
        fallback = next;
        return view(next);
    }

    private AiProviderConfig view(Stored stored) {
        return new AiProviderConfig(stored.provider(), stored.model(), stored.baseUrl(), stored.enabled(),
                stored.apiKey() != null && !stored.apiKey().isBlank(), mask(stored.apiKey()), stored.updatedAt());
    }

    private Stored storedFromView(AiProviderConfig config) {
        return new Stored(config.provider(), config.model(), config.baseUrl(), fallback.apiKey(), config.enabled(), config.updatedAt());
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "未配置";
        if (apiKey.length() <= 8) return "********";
        return apiKey.substring(0, 4) + "********" + apiKey.substring(apiKey.length() - 4);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private Optional<AiProviderAccess> usable(Stored stored) {
        if (stored == null || !stored.enabled() || blank(stored.apiKey())) return Optional.empty();
        return Optional.of(new AiProviderAccess(stored.provider(), stored.model(), stored.baseUrl(), stored.apiKey()));
    }

    public record AiProviderAccess(String provider, String model, String baseUrl, String apiKey) {}

    private record Stored(String provider, String model, String baseUrl, String apiKey, boolean enabled,
                          LocalDateTime updatedAt) {}
}
