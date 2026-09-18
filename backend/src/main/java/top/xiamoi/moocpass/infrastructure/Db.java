package top.xiamoi.moocpass.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Statement;
import java.util.*;
import java.util.function.Supplier;

@Component
public class Db {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public Db(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }
    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
    public long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    public List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.query(sql, (result, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            var metadata = result.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                String key = metadata.getColumnLabel(i).toLowerCase(Locale.ROOT);
                StringBuilder camel = new StringBuilder();
                boolean upper = false;
                for (char c : key.toCharArray()) {
                    if (c == '_') upper = true;
                    else { camel.append(upper ? Character.toUpperCase(c) : c); upper = false; }
                }
                Object value = result.getObject(i);
                if (value instanceof java.sql.Clob clob) {
                    value = clob.getSubString(1, (int) clob.length());
                } else if (value instanceof java.sql.Timestamp ts) {
                    value = ts.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else if (value instanceof java.time.LocalDateTime ldt) {
                    value = ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else if (value instanceof java.sql.Date d) {
                    value = d.toString();
                }
                row.put(camel.toString(), value);
            }
            return row;
        }, args);
    }
    public Optional<Map<String, Object>> one(String sql, Object... args) {
        return list(sql, args).stream().findFirst();
    }
    public long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
    public <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    public static long id(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    public static String text(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
    public static boolean flag(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
    }
}
