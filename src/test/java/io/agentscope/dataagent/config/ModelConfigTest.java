package io.agentscope.dataagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.dataagent.config.properties.ApiModelProperties;
import org.junit.jupiter.api.Test;

class ModelConfigTest {

    @Test
    void selectsLocalWhenLongCatWasRequestedWithoutAnApiKey() {
        ApiModelProperties properties = new ApiModelProperties();
        properties.setActive("longcat");
        properties.getLongcat().setApiKey("");

        assertThat(ModelConfig.resolveActiveId(properties)).isEqualTo(ModelConfig.LOCAL_MODEL_ID);
    }

    @Test
    void selectsLongCatOnlyWhenTheCredentialExists() {
        ApiModelProperties properties = new ApiModelProperties();
        properties.setActive("longcat");
        properties.getLongcat().setApiKey("configured-key");

        assertThat(ModelConfig.resolveActiveId(properties)).isEqualTo(ModelConfig.LONGCAT_MODEL_ID);
    }
}
