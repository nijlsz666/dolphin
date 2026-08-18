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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PlannedOrderStore {
    private static final Logger log = LoggerFactory.getLogger(PlannedOrderStore.class);
    private final DataSource dataSource;
    private final String accountId = "default";
    private final Map<String, Plan> fallback = new ConcurrentHashMap<>();

    public PlannedOrderStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Plan(String code, String side, BigDecimal plannedPrice, BigDecimal quantity, LocalDate tradeDate, String status) {}

    public Plan loadDraft(String code) {
        String sql = "SELECT side, planned_price, quantity, planned_date, status FROM planned_order "
                + "WHERE account_id=? AND stock_code=? AND status='DRAFT' ORDER BY id DESC LIMIT 1";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            statement.setString(2, code);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    return new Plan(code, normalizeSide(row.getString("side")), row.getBigDecimal("planned_price"), row.getBigDecimal("quantity"),
                            row.getDate("planned_date").toLocalDate(), row.getString("status"));
                }
            }
        } catch (Exception ex) {
            log.info("计划操作表暂不可用，使用内存计划: {}", ex.getMessage());
        }
        return fallback.get(code);
    }

    public void saveDraft(Plan plan) {
        fallback.put(plan.code(), plan);
        String cancel = "UPDATE planned_order SET status='CANCELLED' WHERE account_id=? AND stock_code=? AND status='DRAFT'";
        String insert = "INSERT INTO planned_order(account_id,stock_code,planned_date,side,planned_price,quantity,status) VALUES(?,?,?,?,?,?, 'DRAFT')";
        try (Connection connection = open(); PreparedStatement cancelStatement = connection.prepareStatement(cancel);
             PreparedStatement insertStatement = connection.prepareStatement(insert)) {
            cancelStatement.setString(1, accountId);
            cancelStatement.setString(2, plan.code());
            cancelStatement.executeUpdate();
            insertStatement.setString(1, accountId);
            insertStatement.setString(2, plan.code());
            insertStatement.setObject(3, plan.tradeDate());
            insertStatement.setString(4, normalizeSide(plan.side()));
            insertStatement.setBigDecimal(5, plan.plannedPrice());
            insertStatement.setBigDecimal(6, plan.quantity());
            insertStatement.executeUpdate();
        } catch (Exception ex) {
            log.warn("计划操作写入 planned_order 失败: {}", ex.getMessage());
        }
    }

    public void clearDraft(String code) {
        fallback.remove(code);
        String sql = "UPDATE planned_order SET status='CANCELLED' WHERE account_id=? AND stock_code=? AND status='DRAFT'";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            statement.setString(2, code);
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("计划操作清除失败: {}", ex.getMessage());
        }
    }

    public void confirmDraft(String code, BigDecimal executedPrice, BigDecimal executedQuantity, LocalDate date) {
        fallback.remove(code);
        String sql = "UPDATE planned_order SET status='CONFIRMED', executed_price=?, executed_quantity=?, confirmed_at=NOW() "
                + "WHERE account_id=? AND stock_code=? AND status='DRAFT'";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBigDecimal(1, executedPrice);
            statement.setBigDecimal(2, executedQuantity);
            statement.setString(3, accountId);
            statement.setString(4, code);
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("计划确认写入失败: {}", ex.getMessage());
        }
    }

    private Connection open() throws Exception { return dataSource.getConnection(); }

    private String normalizeSide(String side) { return "SELL".equalsIgnoreCase(side) ? "SELL" : "BUY"; }
}
