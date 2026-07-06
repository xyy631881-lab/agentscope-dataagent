/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web;

import io.agentscope.dataagent.web.sandbox.SandboxReaperService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * agentscope-dataagent Spring Boot 应用程序的入口点。
 *
 * <p>DataAgent 是一个多租户、可分布式部署的 Agent 产品，构建在
 * {@code HarnessAgent} 之上。它为每个租户开箱即用地提供精选的内置
 * {@code data-agent}（SQL/图表/探索/报表编写器），以及在完全隔离的
 * workspace 中的每个用户自定义数据 Agent。
 *
 * <p>主要用户体验是通过从 {@code classpath:/static/} 提供的 React SPA 实现的。外部
 * IM 和工单系统可以通过侧通道适配器（钉钉、通用 webhook）调用租户的
 * DataAgent，这些调用将落入相同的每个用户会话存储中。
 *
 * <p>沙箱容器生命周期由 {@link SandboxReaperService} 管理：
 * <ul>
 *   <li>启动时：清理 DB 中标记为孤儿的心跳超时容器</li>
 *   <li>运行时：每分钟扫描过期容器并回收</li>
 *   <li>关闭时：优雅停止所有活跃容器</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = "io.agentscope.dataagent")
@EnableScheduling
public class DataAgentApp {

    private static final Logger log = LoggerFactory.getLogger(DataAgentApp.class);

    @Autowired(required = false)
    private SandboxReaperService reaper;

    public static void main(String[] args) {
        SpringApplication.run(DataAgentApp.class, args);
    }

    // ---- 生命周期钩子 ----

    /**
     * 启动时通过 DB 记录清理孤儿容器（进程强杀残留）。
     */
    @PostConstruct
    public void cleanupOnStartup() {
        if (reaper != null) {
            log.info("进程启动：正在清理孤儿沙箱容器...");
            reaper.cleanupOnStartup();
        }
    }

    /**
     * 进程正常关闭时优雅停止所有沙箱容器。
     */
    @PreDestroy
    public void cleanupOnShutdown() {
        if (reaper != null) {
            log.info("进程关闭：正在优雅停止沙箱容器...");
            reaper.shutdownAll();
        }
    }
}
