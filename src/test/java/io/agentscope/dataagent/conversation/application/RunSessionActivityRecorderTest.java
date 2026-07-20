package io.agentscope.dataagent.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.agent.application.AgentActivityStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RunSessionActivityRecorderTest {

    @Test
    void busySandboxWriteNeverBlocksOrQueuesTheNextChatRequest() throws Exception {
        AgentActivityStore activity = mock(AgentActivityStore.class);
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        when(activity.actor("admin"))
                .thenReturn(new AgentActivityStore.ActorRef("admin", "admin"));
        doAnswer(
                        invocation -> {
                            writeStarted.countDown();
                            releaseWrite.await();
                            return null;
                        })
                .when(activity)
                .record(
                        anyString(),
                        anyString(),
                        any(AgentActivityStore.ActorRef.class),
                        anyString(),
                        anyString(),
                        isNull());

        RunSessionActivityRecorder recorder = new RunSessionActivityRecorder(activity);
        try {
            assertThat(recorder.tryRecord("admin", "insight-agent", "admin", "gate-1"))
                    .isTrue();
            assertThat(writeStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(recorder.tryRecord("admin", "insight-agent", "admin", "gate-2"))
                    .isFalse();
        } finally {
            releaseWrite.countDown();
            recorder.close();
        }
    }
}
