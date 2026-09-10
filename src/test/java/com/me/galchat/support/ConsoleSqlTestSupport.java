package com.me.galchat.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class ConsoleSqlTestSupport {
    private ConsoleSqlTestSupport() {
    }

    /** Call inside a rollback-only test transaction to isolate the complete schema. */
    public static String initializeSchema(JdbcTemplate jdbc) throws IOException {
        String schema = "console_sql_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA \"" + schema + "\"");
        jdbc.execute("SET LOCAL search_path TO \"" + schema + "\", public");
        jdbc.execute(Files.readString(Path.of("src/test/java/com/me/galchat/init/console.sql")));
        return schema;
    }
}
