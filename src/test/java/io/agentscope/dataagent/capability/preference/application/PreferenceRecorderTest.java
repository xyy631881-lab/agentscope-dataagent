package io.agentscope.dataagent.capability.preference.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.capability.preference.infrastructure.ChartUsageEntity;
import io.agentscope.dataagent.capability.preference.infrastructure.ChartUsageRepository;
import io.agentscope.dataagent.capability.preference.infrastructure.SqlHistoryEntity;
import io.agentscope.dataagent.capability.preference.infrastructure.SqlHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PreferenceRecorderTest {

    @Test
    void recordsToolPreferencesAndInvalidatesCachedUserAgent() {
        SqlHistoryRepository sql = mock(SqlHistoryRepository.class);
        ChartUsageRepository charts = mock(ChartUsageRepository.class);
        AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);
        PreferenceRecorder recorder =
                new PreferenceRecorder(sql, charts, new ObjectMapper(), lifecycle);

        recorder.recordSqlExecution(
                        "alice",
                        "analyst-agent",
                        "{\"sql\":\"SELECT * FROM orders\"}",
                        "rows=10")
                .join();
        recorder.recordChartRender(
                        "alice", "analyst-agent", "{\"chart_type\":\"bar\"}")
                .join();

        ArgumentCaptor<SqlHistoryEntity> sqlCapture =
                ArgumentCaptor.forClass(SqlHistoryEntity.class);
        verify(sql).save(sqlCapture.capture());
        assertThat(sqlCapture.getValue().getSqlText()).isEqualTo("SELECT * FROM orders");
        assertThat(sqlCapture.getValue().isSuccess()).isTrue();
        ArgumentCaptor<ChartUsageEntity> chartCapture =
                ArgumentCaptor.forClass(ChartUsageEntity.class);
        verify(charts).save(chartCapture.capture());
        assertThat(chartCapture.getValue().getChartType()).isEqualTo("bar");
        verify(lifecycle, org.mockito.Mockito.times(2))
                .invalidateUca("alice", "analyst-agent");
        recorder.shutdown();
    }
}
