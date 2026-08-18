package com.dolphin.stock.service;

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
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PositionStore {
    private static final Logger log = LoggerFactory.getLogger(PositionStore.class);
    private final DataSource dataSource;
    private final Map<String, Holding> fallback = new ConcurrentHashMap<>();
    private final String accountId = "default";

    public PositionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Holding(BigDecimal quantity, BigDecimal availableQuantity, BigDecimal avgCost,
                          BigDecimal highestPrice, LocalDate openedAt) {}

    public record StoredPosition(boolean databaseAvailable, Holding holding, LocalDateTime updatedAt) {}

    public Holding load(String code) {
        return loadStored(code).holding();
    }

    public StoredPosition loadStored(String code) {
        String sql = "SELECT quantity, available_quantity, avg_cost, highest_price, opened_at "
                + ", updated_at FROM simulated_position WHERE account_id=? AND stock_code=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            statement.setString(2, code);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    Holding holding = new Holding(row.getBigDecimal("quantity"), row.getBigDecimal("available_quantity"),
                            row.getBigDecimal("avg_cost"), row.getBigDecimal("highest_price"),
                            row.getDate("opened_at").toLocalDate());
                    java.sql.Timestamp updatedAt = row.getTimestamp("updated_at");
                    return new StoredPosition(true, holding, updatedAt == null ? null : updatedAt.toLocalDateTime());
                }
                return new StoredPosition(true, null, null);
            }
        } catch (Exception ex) {
            log.info("持仓表暂不可用，使用内存持仓: {}", ex.getMessage());
        }
        return new StoredPosition(false, fallback.get(code), null);
    }

    public Map<String, Holding> loadAll() {
        Map<String, Holding> result = new LinkedHashMap<>();
        String sql = "SELECT stock_code, quantity, available_quantity, avg_cost, highest_price, opened_at "
                + "FROM simulated_position WHERE account_id=? AND quantity>0 ORDER BY stock_code";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(rows.getString("stock_code"), new Holding(rows.getBigDecimal("quantity"),
                            rows.getBigDecimal("available_quantity"), rows.getBigDecimal("avg_cost"),
                            rows.getBigDecimal("highest_price"), rows.getDate("opened_at").toLocalDate()));
                }
            }
        } catch (Exception ex) {
            log.info("读取全部持仓失败，使用内存持仓: {}", ex.getMessage());
        }
        fallback.forEach((code, holding) -> {
            if (holding != null && holding.quantity() != null && holding.quantity().signum() > 0) result.putIfAbsent(code, holding);
        });
        return result;
    }

    public void save(String code, Holding holding) {
        fallback.put(code, holding);
        String sql = "INSERT INTO simulated_position(account_id,stock_code,quantity,available_quantity,avg_cost,highest_price,opened_at) "
                + "VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE quantity=VALUES(quantity),available_quantity=VALUES(available_quantity),"
                + "avg_cost=VALUES(avg_cost),highest_price=VALUES(highest_price),opened_at=VALUES(opened_at)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            statement.setString(2, code);
            statement.setBigDecimal(3, holding.quantity());
            statement.setBigDecimal(4, holding.availableQuantity());
            statement.setBigDecimal(5, holding.avgCost());
            statement.setBigDecimal(6, holding.highestPrice());
            statement.setObject(7, holding.openedAt());
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("持仓写入 simulated_position 失败: {}", ex.getMessage());
        }
    }

    public void remove(String code) {
        fallback.remove(code);
        String sql = "DELETE FROM simulated_position WHERE account_id=? AND stock_code=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            statement.setString(2, code);
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("持仓删除失败: {}", ex.getMessage());
        }
    }

    private Connection open() throws Exception {
        return dataSource.getConnection();
    }
}
