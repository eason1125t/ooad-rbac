/** 功能：可选连接独立空库验证真实Flyway与约束/Mapper；由scripts/mysql-smoke.ps1提供隔离MySQL。 */
package com.municipal.testing;

import static org.assertj.core.api.Assertions.*;

import com.municipal.rbac.authorization.internal.*;
import com.municipal.rbac.identity.*;
import java.sql.*;
import java.time.Instant;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class MySqlMigrationTest {
    @Test
    void emptyDatabaseMigrationConstraintsAndActualMapper() throws Exception {
        String url = System.getenv("MYSQL_TEST_URL");
        var datasource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, "root", "");
        var flyway =
                Flyway.configure()
                        .dataSource(datasource)
                        .locations("classpath:db/migration")
                        .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        try (var connection = datasource.getConnection();
                var sql = connection.createStatement()) {
            try (var insert =
                    connection.prepareStatement(
                            "INSERT INTO id_user(id,username,password_hash) VALUES(1,?,?)")) {
                insert.setString(1, "employee-1");
                insert.setString(
                        2,
                        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
                                .encode("1234"));
                insert.executeUpdate();
            }
            sql.executeUpdate("INSERT INTO auth_user_role(user_id,role_id) VALUES(1,1)");
            assertThatThrownBy(
                            () ->
                                    sql.executeUpdate(
                                            "INSERT INTO auth_user_role(user_id,role_id) VALUES(999,1)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    sql.executeUpdate(
                                            "UPDATE auth_user_role SET valid_from='2026-01-02',valid_to='2026-01-01' WHERE user_id=1"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    sql.executeUpdate(
                                            "INSERT INTO auth_role(code,name) VALUES('bootstrap_admin','duplicate')"))
                    .isInstanceOf(SQLException.class);
        }
        var config =
                new Configuration(
                        new Environment("mysql-smoke", new JdbcTransactionFactory(), datasource));
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.addMapper(GrantMapper.class);
        config.addMapper(AccountMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            assertThat(session.getMapper(AccountMapper.class).byUsername("employee-1").id())
                    .isEqualTo(1);
            var resolver = new DatabasePermissionResolver(session.getMapper(GrantMapper.class));
            assertThat(resolver.resolve(1, Instant.now()))
                    .containsExactlyInAnyOrder(
                            "admin:console:view", "oa:document:view", "pmis:project:view");
            try (var sql = session.getConnection().createStatement()) {
                Instant boundary = Instant.parse("2026-10-25T00:00:00Z");
                sql.executeUpdate(
                        "UPDATE auth_user_role SET valid_from='2026-10-25 00:00:00', valid_to='2026-10-25 00:00:01' WHERE user_id=1");
                assertThat(resolver.resolve(1, boundary.minusMillis(1))).isEmpty();
                assertThat(resolver.resolve(1, boundary)).contains("oa:document:view");
                assertThat(resolver.resolve(1, boundary.plusSeconds(1))).isEmpty();
                // 第二个角色是独立来源，第一个来源到期后仍然允许；最后再验证停用层级。
                sql.executeUpdate(
                        "INSERT INTO auth_role(id,code,name) VALUES(2,'synthetic_reader','合成角色')");
                sql.executeUpdate("INSERT INTO auth_user_role(user_id,role_id) VALUES(1,2)");
                sql.executeUpdate(
                        "INSERT INTO auth_role_permission(role_id,permission_id) VALUES(2,2)");
                assertThat(resolver.resolve(1, boundary.plusSeconds(1)))
                        .containsExactly("oa:document:view");
                String[][] toggles = {
                    {
                        "UPDATE auth_role SET enabled=FALSE WHERE id=2",
                        "UPDATE auth_role SET enabled=TRUE WHERE id=2"
                    },
                    {
                        "UPDATE auth_permission SET enabled=FALSE WHERE id=2",
                        "UPDATE auth_permission SET enabled=TRUE WHERE id=2"
                    },
                    {
                        "UPDATE auth_application SET enabled=FALSE WHERE id=2",
                        "UPDATE auth_application SET enabled=TRUE WHERE id=2"
                    },
                    {
                        "UPDATE id_user SET enabled=FALSE WHERE id=1",
                        "UPDATE id_user SET enabled=TRUE WHERE id=1"
                    }
                };
                for (String[] toggle : toggles) {
                    sql.executeUpdate(toggle[0]);
                    assertThat(resolver.resolve(1, boundary.plusSeconds(1))).isEmpty();
                    sql.executeUpdate(toggle[1]);
                }
            }
        }
    }
}
