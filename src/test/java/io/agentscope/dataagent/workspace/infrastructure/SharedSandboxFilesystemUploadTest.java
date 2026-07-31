package io.agentscope.dataagent.workspace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedSandboxFilesystemUploadTest {

    @Test
    void splitsLargeBinaryUploadsIntoWindowsSafeDockerExecCommands() {
        RecordingSandbox sandbox = new RecordingSandbox();
        byte[] content = new byte[96 * 1024];

        FileUploadResponse response =
                SharedSandboxFilesystem.uploadFile(
                        sandbox, RuntimeContext.empty(), "knowledge/reference.pdf", content);

        assertThat(response.isSuccess()).isTrue();
        assertThat(sandbox.commands)
                .anyMatch(command -> command.contains("base64 -d >>"))
                .anyMatch(command -> command.startsWith("mv "))
                .allMatch(command -> command.length() < 12_000);
    }

    private static final class RecordingSandbox implements Sandbox {

        private final List<String> commands = new ArrayList<>();

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public SandboxState getState() {
            return null;
        }

        @Override
        public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            commands.add(command);
            return new ExecResult(0, "", "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }
}
