package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgSave;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.TrpgAutoSaveMapper;
import com.me.galchat.mapper.TrpgSaveMapper;
import com.me.galchat.service.ITrpgSaveSnapshotService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.impl.group.GroupConversationLockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Uses an isolated fixture table, never application tables. Set GALCHAT_TEST_POSTGRES_URL to opt in. */
@EnabledIfEnvironmentVariable(named = "GALCHAT_TEST_POSTGRES_URL", matches = ".+")
class TrpgSaveIsolationIntegrationTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private String table;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                System.getenv("GALCHAT_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("GALCHAT_TEST_POSTGRES_USER", System.getProperty("user.name")),
                System.getenv().getOrDefault("GALCHAT_TEST_POSTGRES_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        // Reproduce the weaker isolation explicitly, independent of the server's default.
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        table = "save_isolation_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TABLE " + table + " (hp INT, dice_status TEXT, summary TEXT)");
        jdbc.update("INSERT INTO " + table + " VALUES (10, 'pending', 'before')");
        com.me.galchat.support.MybatisPlusTestSupport.initialize(GroupChatTurn.class);
    }

    @AfterEach
    void cleanUp() {
        if (jdbc != null && table != null) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void saveKeepsPreRollStateWhenDiceCommitsBetweenSnapshotQueries() {
        var conversationMapper = mock(GroupConversationMapper.class);
        var snapshotService = mock(ITrpgSaveSnapshotService.class);
        var saveMapper = mock(TrpgSaveMapper.class);
        var lockService = mock(GroupConversationLockService.class);
        when(lockService.tryLock(51L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(conversationMapper.selectById(51L)).thenAnswer(ignored ->
                new GroupConversation().setId(51L).setUserWorldId(12L).setWorldId(4L)
                        .setModuleId(8L).setMode(GroupChatConstant.MODE_TRPG)
                        .setSummary(jdbc.queryForObject("SELECT summary FROM " + table, String.class)));
        when(snapshotService.capture(any())).thenAnswer(invocation -> {
            GroupConversation conversation = invocation.getArgument(0);
            int hp = jdbc.queryForObject("SELECT hp FROM " + table, Integer.class);
            // A second connection commits a roll after character capture and before dice capture.
            var diceTransaction = new TransactionTemplate(transactions.getTransactionManager());
            diceTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            diceTransaction.executeWithoutResult(status -> jdbc.update(
                    "UPDATE " + table + " SET hp = 7, dice_status = 'resolved', summary = 'after'"));
            String diceStatus = jdbc.queryForObject("SELECT dice_status FROM " + table, String.class);
            return new TrpgSaveSnapshotDTO().setFormatVersion(2).setConversationId(51L)
                    .setConversationState(new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                            .setSummary(conversation.getSummary()))
                    .setCharacters(List.of(new CocCharacter().setId(1L).setHpCurrent(hp)))
                    .setLastDice(new TrpgSaveSnapshotDTO.DiceSnapshot()
                            .setSummary(new DiceRollSummary().setId(1L).setStatus(diceStatus)));
        });
        var service = new TrpgSaveServiceImpl(mock(IUserWorldPrefixService.class), conversationMapper,
                mock(GroupChatTurnMapper.class), saveMapper, mock(TrpgAutoSaveMapper.class),
                snapshotService, lockService, transactions);

        service.save(7L, 51L, null);

        var saved = ArgumentCaptor.forClass(TrpgSave.class);
        verify(saveMapper).insert(saved.capture());
        var snapshot = saved.getValue().getSnapshot();
        assertThat(snapshot.getCharacters().getFirst().getHpCurrent()).isEqualTo(10);
        assertThat(snapshot.getLastDice().getSummary().getStatus()).isEqualTo("pending");
        assertThat(snapshot.getConversationState().getSummary()).isEqualTo("before");
        assertThat(jdbc.queryForObject("SELECT hp FROM " + table, Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT dice_status FROM " + table, String.class)).isEqualTo("resolved");
        assertThat(transactions.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
}
