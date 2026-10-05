package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.constant.FavorBindingType;
import com.me.galchat.service.impl.user.UserCharacterInfoServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Run against an isolated PostgreSQL database with -Dgalchat.test.favor-jdbc-url=jdbc:postgresql://... */
@EnabledIfSystemProperty(named = "galchat.test.favor-jdbc-url", matches = ".+")
class UserCharacterFavorCasIntegrationTest {
    private final String schema = "favor_cas_" + UUID.randomUUID().toString().replace("-", "");
    private SqlSessionFactory sessions;

    @BeforeEach
    void setUp() throws Exception {
        try (var connection = connect(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE TABLE " + schema + ".user_character_info ("
                    + "user_world_id BIGINT, character_id BIGINT, favor_value INTEGER, "
                    + "PRIMARY KEY (user_world_id, character_id))");
            sql.execute("INSERT INTO " + schema + ".user_character_info VALUES (3,7,10),(3,8,30),(4,7,40),(3,9,NULL)");
        }
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("favor-test", new JdbcTransactionFactory(),
                new DriverManagerDataSource(System.getProperty("galchat.test.favor-jdbc-url"))));
        try (var xml = new ClassPathResource("mapper/UserCharacterInfoMapper.xml").getInputStream()) {
            new XMLMapperBuilder(xml, config, "mapper/UserCharacterInfoMapper.xml", config.getSqlFragments()).parse();
        }
        sessions = new MybatisSqlSessionFactoryBuilder().build(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var connection = connect(); var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void conflictingTransactionsReloadFreshStateAfterFailedCasAndCanRollbackTheRetry() throws Exception {
        try (var first = openSession(); var second = openSession(); var executor = Executors.newSingleThreadExecutor()) {
            var a = first.getMapper(UserCharacterInfoMapper.class);
            var b = second.getMapper(UserCharacterInfoMapper.class);
            assertThat(readFavor(a, 3L, 7L)).isEqualTo(10);
            assertThat(readFavor(b, 3L, 7L)).isEqualTo(10); // Also populates MyBatis's local cache.
            assertThat(a.compareAndSetFavorValue(3L, 7L, 10, 15)).isEqualTo(1);
            var started = new CountDownLatch(1);
            var loser = executor.submit(() -> {
                started.countDown();
                return b.compareAndSetFavorValue(3L, 7L, 10, 12);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            first.commit();

            assertThat(loser.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(readFavor(b, 3L, 7L)).isEqualTo(15);
            assertThat(b.compareAndSetFavorValue(3L, 7L, 15, 17)).isEqualTo(1);
            assertThat(readFavor(b, 3L, 7L)).isEqualTo(17);
            second.rollback();
            assertThat(readFavor(b, 3L, 7L)).isEqualTo(15);
        }
    }

    @Test
    void casScopesBothWorldAndCharacterAndHandlesNullAndUnchangedValues() throws Exception {
        try (var session = openSession()) {
            var mapper = session.getMapper(UserCharacterInfoMapper.class);
            assertThat(mapper.compareAndSetFavorValue(3L, 7L, 10, 12)).isEqualTo(1);
            assertThat(mapper.compareAndSetFavorValue(3L, 7L, 10, 15)).isZero();
            assertThat(readFavor(mapper, 3L, 7L)).isEqualTo(12);
            assertThat(readFavor(mapper, 3L, 8L)).isEqualTo(30);
            assertThat(readFavor(mapper, 4L, 7L)).isEqualTo(40);
            assertThat(mapper.compareAndSetFavorValue(3L, 9L, 0, 0)).isEqualTo(1);
            assertThat(readFavor(mapper, 3L, 9L)).isZero();
            assertThat(mapper.compareAndSetFavorValue(3L, 99L, 0, 2)).isZero();
            session.rollback();
        }
    }

    @Test
    void serviceTreatsAnExistingNullFavorAsZeroOnDatabaseFallback() throws Exception {
        try (var session = openSession()) {
            var mapper = session.getMapper(UserCharacterInfoMapper.class);
            var redis = mock(StringRedisTemplate.class);
            when(redis.opsForHash()).thenReturn(mock(HashOperations.class));
            var favors = mock(UserCharacterFavorLogMapper.class);
            var service = new UserCharacterInfoServiceImpl(
                    null, null, redis, favors, null, null, null, null, null, null, null, null, mock(com.me.galchat.service.impl.group.GroupConversationLockService.class));
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
            ReflectionTestUtils.setField(service, "entityClass", UserCharacterInfo.class);

            service.updateFavorValue(3L, 9L, 2, FavorBindingType.SINGLE_MESSAGE, 30L);

            assertThat(readFavor(mapper, 3L, 9L)).isEqualTo(2);
            var log = ArgumentCaptor.forClass(UserCharacterFavorLog.class);
            verify(favors).insert(log.capture());
            assertThat(log.getValue().getFavorUpdate()).isEqualTo(2);
            session.rollback();
        }
    }

    private Integer readFavor(UserCharacterInfoMapper mapper, Long world, Long character) {
        return mapper.selectOne(new LambdaQueryWrapper<UserCharacterInfo>()
                .select(UserCharacterInfo::getFavorValue)
                .eq(UserCharacterInfo::getUserWorldId, world)
                .eq(UserCharacterInfo::getCharacterId, character)).getFavorValue();
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(System.getProperty("galchat.test.favor-jdbc-url"));
    }

    private SqlSession openSession() throws Exception {
        var connection = connect();
        try (var sql = connection.createStatement()) {
            sql.execute("SET search_path TO " + schema);
            sql.execute("SET lock_timeout TO '5s'");
        }
        connection.setAutoCommit(false);
        return sessions.openSession(connection);
    }
}
