package com.dolphin.stock.service;

import com.dolphin.stock.model.StockAnalysisModels.AiCompanyAnalysis;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Company-level AI information changes much more slowly than quotes and trade
 * decisions. Keep it separately so a pool refresh does not need to regenerate
 * the same business description and outlook.
 */
@Component
public class CompanyProfileStore {
    private static final Logger log = LoggerFactory.getLogger(CompanyProfileStore.class);
    private final DataSource dataSource;
    private final Map<String, Profile> fallback = new ConcurrentHashMap<>();

    public CompanyProfileStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Profile(String stockCode, LocalDate analyzedDate, int version, String provider, String model,
                          AiCompanyAnalysis analysis) {}

    public Optional<Profile> load(String stockCode) {
        String code = normalize(stockCode);
        if (code.isBlank()) return Optional.empty();
        String sql = "SELECT analyzed_date, model_version, provider, model, business_description, outlook, future_trend, risk, confidence "
                + "FROM company_profile_cache WHERE stock_code=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    Profile profile = profile(code, row);
                    fallback.put(code, profile);
                    return Optional.of(profile);
                }
                return Optional.empty();
            }
        } catch (Exception ex) {
            log.info("公司资料缓存表暂不可用，使用内存缓存：{}", ex.getMessage());
            return Optional.ofNullable(fallback.get(code));
        }
    }

    public boolean isFresh(String stockCode, LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        return load(stockCode).map(profile -> profile.analyzedDate() != null
                && !profile.analyzedDate().isBefore(date)).orElse(false);
    }

    public void save(String stockCode, LocalDate analyzedDate, AiCompanyAnalysis analysis,
                     String provider, String model) {
        String code = normalize(stockCode);
        if (code.isBlank() || analysis == null) return;
        LocalDate date = analyzedDate == null ? LocalDate.now() : analyzedDate;
        int version = nextVersion(code);
        Profile profile = new Profile(code, date, version, provider, model, analysis);
        fallback.put(code, profile);
        String sql = "INSERT INTO company_profile_cache(stock_code,analyzed_date,model_version,provider,model,business_description,outlook,future_trend,risk,confidence) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE analyzed_date=VALUES(analyzed_date),model_version=VALUES(model_version),provider=VALUES(provider),"
                + "model=VALUES(model),business_description=VALUES(business_description),outlook=VALUES(outlook),"
                + "future_trend=VALUES(future_trend),risk=VALUES(risk),confidence=VALUES(confidence)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setObject(2, date);
            statement.setInt(3, version);
            statement.setString(4, provider);
            statement.setString(5, model);
            statement.setString(6, analysis.businessDescription());
            statement.setString(7, analysis.outlook());
            statement.setString(8, analysis.futureTrend());
            statement.setString(9, analysis.risk());
            statement.setBigDecimal(10, analysis.confidence() == null ? BigDecimal.ZERO : analysis.confidence());
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("公司资料缓存写入失败，股票={}：{}", code, ex.getMessage());
        }
    }

    private Profile profile(String code, ResultSet row) throws Exception {
        BigDecimal confidence = row.getBigDecimal("confidence");
        return new Profile(code, row.getDate("analyzed_date").toLocalDate(), row.getInt("model_version"), row.getString("provider"), row.getString("model"),
                new AiCompanyAnalysis(true, row.getString("business_description"), row.getString("outlook"),
                        row.getString("future_trend"), row.getString("risk"), confidence == null ? BigDecimal.ZERO : confidence));
    }

    private int nextVersion(String code) {
        String sql = "SELECT COALESCE(MAX(model_version),0)+1 FROM company_profile_cache WHERE stock_code=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) return Math.max(1, row.getInt(1));
            }
        } catch (Exception ex) {
            Profile current = fallback.get(code);
            return current == null ? 1 : current.version() + 1;
        }
        return 1;
    }

    private String normalize(String code) { return code == null ? "" : code.trim().toUpperCase(); }

    private Connection open() throws Exception { return dataSource.getConnection(); }
}
