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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TradeExecutionStore {
    private static final Logger log = LoggerFactory.getLogger(TradeExecutionStore.class);
    private final DataSource dataSource;
    private final String accountId = "default";
    private final Map<String, Trade> fallback = new ConcurrentHashMap<>();

    public TradeExecutionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Trade(String code, LocalDate tradeDate, String side, BigDecimal plannedPrice,
                        BigDecimal executedPrice, BigDecimal quantity, BigDecimal amount, String status) {}

    public Trade loadToday(String code, LocalDate date) {
        String sql = "SELECT trade_date, side, planned_price, executed_price, quantity, amount, status FROM trade_execution "
                + "WHERE account_id=? AND stock_code=? AND trade_date=? ORDER BY id DESC LIMIT 1";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            statement.setString(2, code);
            statement.setObject(3, date);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) return new Trade(code, row.getDate("trade_date").toLocalDate(), row.getString("side"),
                        row.getBigDecimal("planned_price"), row.getBigDecimal("executed_price"),
                        row.getBigDecimal("quantity"), row.getBigDecimal("amount"), row.getString("status"));
            }
        } catch (Exception ex) {
            log.info("交易记录表暂不可用，使用内存交易记录: {}", ex.getMessage());
        }
        Trade trade = fallback.get(code);
        return trade != null && date.equals(trade.tradeDate()) ? trade : null;
    }

    public List<Trade> loadHistory(String code) {
        List<Trade> result = new ArrayList<>();
        String sql = "SELECT trade_date, side, planned_price, executed_price, quantity, amount, status FROM trade_execution "
                + "WHERE account_id=? AND stock_code=? ORDER BY trade_date DESC, id DESC";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            statement.setString(2, code);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new Trade(code, rows.getDate("trade_date").toLocalDate(), rows.getString("side"),
                            rows.getBigDecimal("planned_price"), rows.getBigDecimal("executed_price"),
                            rows.getBigDecimal("quantity"), rows.getBigDecimal("amount"), rows.getString("status")));
                }
            }
            return result;
        } catch (Exception ex) {
            log.info("读取交易历史失败，使用内存交易记录: {}", ex.getMessage());
        }
        Trade fallbackTrade = fallback.get(code);
        return fallbackTrade == null ? List.of() : List.of(fallbackTrade);
    }

    public void save(Trade trade) {
        fallback.put(trade.code(), trade);
        String sql = "INSERT INTO trade_execution(account_id,stock_code,trade_date,side,planned_price,executed_price,quantity,amount,status) "
                + "VALUES(?,?,?,?,?,?,?,?,?)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountId);
            statement.setString(2, trade.code());
            statement.setObject(3, trade.tradeDate());
            statement.setString(4, trade.side());
            statement.setBigDecimal(5, trade.plannedPrice());
            statement.setBigDecimal(6, trade.executedPrice());
            statement.setBigDecimal(7, trade.quantity());
            statement.setBigDecimal(8, trade.amount());
            statement.setString(9, trade.status());
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("交易记录写入 trade_execution 失败: {}", ex.getMessage());
        }
    }

    private Connection open() throws Exception { return dataSource.getConnection(); }
}
