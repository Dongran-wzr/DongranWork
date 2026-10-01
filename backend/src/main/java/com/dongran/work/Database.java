package com.dongran.work;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

@Component
public class Database {
    public final JdbcTemplate jdbc;
    public final ObjectMapper mapper;
    public Database(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public static String now() { return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.now()); }
    public static String id() { return UUID.randomUUID().toString(); }
    public String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid JSON",e); } }
    public Map<String,Object> object(String value) { try { return mapper.readValue(value,new TypeReference<>() {}); } catch(Exception e) { throw new IllegalStateException("Invalid stored document",e); } }
    public Map<String,Object> one(String sql,Object... args) {
        var rows=jdbc.queryForList(sql,args);
        if(rows.isEmpty())throw ApiException.missing("记录不存在。");
        return rows.getFirst();
    }
    public static String text(Map<String,?> data,String key,String fallback) { Object value=data.get(key); return value instanceof String s ? s : fallback; }
    public static String required(Map<String,?> data,String key,int max) {
        Object value=data.get(key);
        if(!(value instanceof String s)||s.isBlank()||s.length()>max||s.indexOf('\0')>=0)throw ApiException.bad(key+" 不能为空或超过长度限制。");
        return s.trim();
    }
    public static boolean bool(Map<String,?> data,String key,boolean fallback) { return data.get(key) instanceof Boolean b ? b : fallback; }
    public static int number(Map<String,?> data,String key,int fallback,int min,int max) {
        Object value=data.get(key); if(value==null)return fallback;
        if(!(value instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<min||n.intValue()>max)throw ApiException.bad(key+" 超出允许范围。");
        return n.intValue();
    }

    @Configuration
    static class ConfigurationBeans {
        @Bean Path dataDirectory(@Value("${dongran.data-dir}") String directory) throws Exception {
            Path path=Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(path); return path;
        }
        @Bean DataSource dataSource(Path dataDirectory) throws Exception {
            var config=new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:"+dataDirectory.resolve("dongran.sqlite"));
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(1); config.setMinimumIdle(1);
            config.addDataSourceProperty("journal_mode","WAL");
            config.addDataSourceProperty("foreign_keys","true");
            config.addDataSourceProperty("busy_timeout","5000");
            var source=new HikariDataSource(config);
            try(var connection=source.getConnection();var statement=connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
                boolean migrated;
                try(var rows=statement.executeQuery("SELECT version FROM schema_migrations WHERE version=1")) { migrated=rows.next(); }
                if(!migrated) {
                    connection.setAutoCommit(false);
                    try {
                        ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/V001__initial.sql"));
                        statement.execute("INSERT INTO schema_migrations VALUES (1,datetime('now'))");
                        connection.commit();
                    } catch(Exception e) { connection.rollback(); throw e; }
                    finally { connection.setAutoCommit(true); }
                }
                statement.executeUpdate("UPDATE tasks SET status='interrupted',error='应用已退出，任务被中断。' WHERE status IN ('queued','running','awaiting_approval')");
                statement.executeUpdate("UPDATE command_runs SET status='interrupted' WHERE status IN ('queued','running')");
                statement.executeUpdate("UPDATE approvals SET status='expired' WHERE status='pending'");
            }
            return source;
        }
    }
}
