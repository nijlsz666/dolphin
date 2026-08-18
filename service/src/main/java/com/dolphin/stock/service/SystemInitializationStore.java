package com.dolphin.stock.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;

/** Stores the last successful manual system initialization time. */
@Component
public class SystemInitializationStore {
    private static final Logger log = LoggerFactory.getLogger(SystemInitializationStore.class);
    private final DataSource dataSource;

    public SystemInitializationStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public LocalDateTime load() {
        String sql = "SELECT initialized_at FROM system_runtime_state WHERE state_key=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "system-initialization");
            try (ResultSet row = statement.executeQuery()) {
                if (row.next() && row.getTimestamp("initialized_at") != null) {
                    return row.getTimestamp("initialized_at").toLocalDateTime();
                }
            }
        } catch (Exception ex) {
            log.info("系统初始化状态表暂不可用: {}", ex.getMessage());
        }
        return null;
    }

    public LocalDateTime save(LocalDateTime initializedAt) {
        String sql = "INSERT INTO system_runtime_state(state_key,initialized_at) VALUES(?,?) "
                + "ON DUPLICATE KEY UPDATE initialized_at=VALUES(initialized_at)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "system-initialization");
            statement.setTimestamp(2, java.sql.Timestamp.valueOf(initializedAt));
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("系统初始化时间写入失败: {}", ex.getMessage());
        }
        return initializedAt;
    }

    private Connection open() throws Exception { return dataSource.getConnection(); }
}
