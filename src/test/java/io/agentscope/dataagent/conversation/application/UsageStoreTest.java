package io.agentscope.dataagent.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.conversation.infrastructure.UsageEventRepository;
import org.junit.jupiter.api.Test;

class UsageStoreTest {

    @Test
    void unwrapsHibernateAggregateProjectionBeforeReadingNumericColumns() {
        UsageEventRepository repository = mock(UsageEventRepository.class);
        when(repository.aggregateAll())
                .thenReturn(new Object[] {new Object[] {12L, 120L, 45L, 7L, 30L, 900L, 3L}});
        when(repository.countSince(org.mockito.ArgumentMatchers.anyLong())).thenReturn(4L);

        UsageStore.UsageSummary summary = new UsageStore(repository).summary();

        assertThat(summary)
                .extracting(
                        UsageStore.UsageSummary::totalTurns,
                        UsageStore.UsageSummary::todayTurns,
                        UsageStore.UsageSummary::inputTokens,
                        UsageStore.UsageSummary::outputTokens,
                        UsageStore.UsageSummary::cachedPromptTokens,
                        UsageStore.UsageSummary::totalTokens,
                        UsageStore.UsageSummary::totalCostMicrousd,
                        UsageStore.UsageSummary::avgDurationMs,
                        UsageStore.UsageSummary::uniqueUsers)
                .containsExactly(12L, 4L, 120L, 45L, 7L, 165L, 30L, 900L, 3L);
    }
}
