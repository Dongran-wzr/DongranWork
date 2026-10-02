package com.dongran.work.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@Configuration
public class PersistenceConfiguration {
  @Bean
  Path dataDirectory(@Value("${dongran.data-dir}") String directory) throws Exception {
    Path path = Path.of(directory).toAbsolutePath().normalize();
    Files.createDirectories(path);
    return path;
  }

  @Bean
  DataSource dataSource(Path dataDirectory) throws Exception {
    var config = new HikariConfig();
    config.setJdbcUrl("jdbc:sqlite:" + dataDirectory.resolve("dongran.sqlite"));
    config.setDriverClassName("org.sqlite.JDBC");
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(1);
    config.addDataSourceProperty("journal_mode", "WAL");
    config.addDataSourceProperty("foreign_keys", "true");
    config.addDataSourceProperty("busy_timeout", "5000");
    var source = new HikariDataSource(config);
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
      String[] migrations = {
        "db/V001__initial.sql",
        "db/V002__model_providers.sql",
        "db/V003__knowledge_sources.sql",
        "db/V004__knowledge_retrieval.sql",
        "db/V005__sandbox_commands.sql",
        "db/V006__local_vectors.sql",
        "db/V007__pdf_extraction_status.sql",
        "db/V008__model_capabilities.sql",
        "db/V009__context_memory.sql"
      };
      for (int index = 0; index < migrations.length; index++) {
        int version = index + 1;
        boolean migrated;
        try (var rows =
            statement.executeQuery(
                "SELECT version FROM schema_migrations WHERE version=" + version)) {
          migrated = rows.next();
        }
        if (migrated) continue;
        connection.setAutoCommit(false);
        try {
          ScriptUtils.executeSqlScript(connection, new ClassPathResource(migrations[index]));
          statement.execute(
              "INSERT INTO schema_migrations VALUES (" + version + ",datetime('now'))");
          connection.commit();
        } catch (Exception e) {
          connection.rollback();
          throw e;
        } finally {
          connection.setAutoCommit(true);
        }
      }
      statement.executeUpdate(
          "UPDATE tasks SET status='interrupted',error='应用已退出，任务被中断。' WHERE status IN ('queued','running','awaiting_approval')");
      statement.executeUpdate(
          "UPDATE command_runs SET status='interrupted' WHERE status IN ('queued','running')");
      statement.executeUpdate(
          "UPDATE tool_executions SET status='uncertain' WHERE status='running'");
      statement.executeUpdate("UPDATE approvals SET status='expired' WHERE status='pending'");
    }
    return source;
  }
}
