import java.sql.*;
import java.nio.file.*;
import java.util.Properties;

/** Read-only connectivity check; never creates a missing database. */
class CheckLocalDatabase {
    public static void main(String[] args) throws Exception {
        boolean mysql = "mysql".equals(args[0]);
        Properties credentials = new Properties();
        credentials.setProperty("user", "sa");
        credentials.setProperty("password", "");
        String url = args[0];
        if (mysql) {
            url = "jdbc:mysql://127.0.0.1:3306/hn_exam?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
            for (String line : Files.readAllLines(Path.of(args[1]))) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    String key = line.substring(0, separator);
                    if (key.equals("user") || key.equals("password")) credentials.setProperty(key, line.substring(separator + 1));
                }
            }
        }
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            connection.setReadOnly(true);
            for (String query : new String[] {
                mysql ? "SELECT DATABASE() AS database_name, VERSION() AS mysql_version" : "SELECT DATABASE_PATH() AS database_path, H2VERSION() AS h2_version",
                "SELECT COUNT(*) AS user_count FROM sys_user",
                "SELECT COUNT(*) AS question_count FROM qb_question",
                "SELECT table_schema, table_name FROM information_schema.tables WHERE table_schema = '" + (mysql ? "hn_exam" : "public") + "' AND table_type = 'BASE TABLE' ORDER BY table_name"
            }) {
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(query)) {
                    while (rows.next()) {
                        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                            System.out.println(rows.getMetaData().getColumnLabel(column) + "=" + rows.getString(column));
                        }
                    }
                }
            }
        }
    }
}
