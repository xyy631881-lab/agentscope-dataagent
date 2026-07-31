package io.agentscope.dataagent.capability.preference.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.capability.preference.infrastructure.ChartUsageRepository;
import io.agentscope.dataagent.capability.preference.infrastructure.SqlHistoryRepository;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.Test;

class UserPreferenceServiceTest {

    @Test
    void summarizesSqlAndChartPreferencesForPromptAndApi() {
        SqlHistoryRepository sql = mock(SqlHistoryRepository.class);
        ChartUsageRepository charts = mock(ChartUsageRepository.class);
        SqlHistoryRepository.SqlPatternProjection sqlPattern =
                mock(SqlHistoryRepository.SqlPatternProjection.class);
        when(sqlPattern.getSqlText()).thenReturn("SELECT *\nFROM orders");
        when(sqlPattern.getUseCount()).thenReturn(3L);
        ChartUsageRepository.ChartTypeProjection bar =
                mock(ChartUsageRepository.ChartTypeProjection.class);
        when(bar.getChartType()).thenReturn("bar");
        when(bar.getUseCount()).thenReturn(3L);
        ChartUsageRepository.ChartTypeProjection line =
                mock(ChartUsageRepository.ChartTypeProjection.class);
        when(line.getChartType()).thenReturn("line");
        when(line.getUseCount()).thenReturn(1L);
        when(sql.findTopSqlPatterns("alice", "analyst-agent")).thenReturn(List.of(sqlPattern));
        when(charts.findChartTypeCounts("alice", "analyst-agent"))
                .thenReturn(List.of(bar, line));

        UserPreferenceService service = new UserPreferenceService(sql, charts);

        UserPreferenceService.PreferenceSummary summary =
                service.getSummary("alice", "analyst-agent");
        assertThat(summary.sqlPatterns()).singleElement().satisfies(
                item -> {
                    assertThat(item.sqlPreview()).isEqualTo("SELECT * FROM orders");
                    assertThat(item.sqlText()).isEqualTo("SELECT *\nFROM orders");
                    assertThat(item.useCount()).isEqualTo(3L);
                });
        assertThat(summary.chartPreferences())
                .extracting(UserPreferenceService.ChartPreference::percentage)
                .containsExactly(75L, 25L);
        assertThat(summary.tablePreferences())
                .extracting(UserPreferenceService.TablePreference::tableName)
                .containsExactly("orders");
        assertThat(summary.queryStyles())
                .extracting(UserPreferenceService.QueryStylePreference::label)
                .containsExactly("明细查询");
        assertThat(service.buildPromptFragment("alice", "analyst-agent"))
                .contains("常用 SQL 模式", "SELECT * FROM orders", "bar 图表（75%", "常查数据表", "查询习惯")
                .doesNotContain("SELECT *\nFROM orders");
    }

    @Test
    void pagesSqlPatternsWithoutPuttingTheEntireHistoryInSummary() {
        SqlHistoryRepository sql = mock(SqlHistoryRepository.class);
        ChartUsageRepository charts = mock(ChartUsageRepository.class);
        SqlHistoryRepository.SqlPatternProjection first = mock(SqlHistoryRepository.SqlPatternProjection.class);
        when(first.getSqlText()).thenReturn("SELECT id FROM orders");
        when(first.getUseCount()).thenReturn(4L);
        PageRequest pageRequest = PageRequest.of(0, 2);
        when(sql.findSqlPatterns("alice", "analyst-agent", pageRequest))
                .thenReturn(new PageImpl<>(List.of(first), pageRequest, 3));
        UserPreferenceService service = new UserPreferenceService(sql, charts);

        UserPreferenceService.SqlPatternPage page =
                service.getSqlPatterns("alice", "analyst-agent", 0, 2);

        assertThat(page.total()).isEqualTo(3L);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.sqlText()).isEqualTo("SELECT id FROM orders");
            assertThat(item.useCount()).isEqualTo(4L);
        });
    }

    @Test
    void clearsOnlyRequestedUserAgentPreferences() {
        SqlHistoryRepository sql = mock(SqlHistoryRepository.class);
        ChartUsageRepository charts = mock(ChartUsageRepository.class);
        UserPreferenceService service = new UserPreferenceService(sql, charts);

        service.clear("alice", "analyst-agent");

        verify(sql).deleteByUserIdAndAgentId("alice", "analyst-agent");
        verify(charts).deleteByUserIdAndAgentId("alice", "analyst-agent");
    }
}
