package com.dolphin.stock.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;

@Component
public class AccountAssetStore {
    private static final Logger log = LoggerFactory.getLogger(AccountAssetStore.class);
    private final DataSource dataSource;
    private final String accountId = "default";

    public AccountAssetStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Assets(BigDecimal totalAssets, LocalDateTime updatedAt) {}

    public Assets load() {
        String sql = "SELECT total_assets, updated_at FROM account_profile WHERE account_id=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    return new Assets(row.getBigDecimal("total_assets"),
                            row.getTimestamp("updated_at") == null ? null : row.getTimestamp("updated_at").toLocalDateTime());
                }
            }
        } catch (Exception ex) {
            log.info("账户资产表暂不可用: {}", ex.getMessage());
        }
        return null;
    }

    public Assets save(BigDecimal totalAssets) {
        String sql = "INSERT INTO account_profile(account_id,total_assets) VALUES(?,?) "
                + "ON DUPLICATE KEY UPDATE total_assets=VALUES(total_assets), updated_at=NOW()";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            statement.setBigDecimal(2, totalAssets);
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("账户总资产写入 account_profile 失败: {}", ex.getMessage());
        }
        return new Assets(totalAssets, LocalDateTime.now());
    }

    private Connection open() throws Exception { return dataSource.getConnection(); }
}
