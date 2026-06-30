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
package io.agentscope.dataagent.runtime.marketplace;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.model.skills.Skill;
import com.alibaba.nacos.api.ai.model.skills.SkillResource;
import com.alibaba.nacos.api.ai.model.skills.SkillSummary;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.model.Page;
import com.alibaba.nacos.maintainer.client.ai.AiMaintainerFactory;
import com.alibaba.nacos.maintainer.client.ai.AiMaintainerService;
import com.alibaba.nacos.maintainer.client.ai.SkillMaintainerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nacos 支持的每个用户 marketplace。使用 maintainer 客户端（而非仅暴露下载功能的
 * 常规 AiService 客户端）驱动分页的 {@code listSkills} API，并通过
 * {@code getSkillVersionDetail(..., "LATEST")} 拉取 SKILL.md。
 *
 * <p>分页限制在 {@link #MAX_PAGES} 批 {@link #PAGE_SIZE} 条以内，
 * 防止行为异常的服务器挂起 UI。
 */
public class NacosDataAgentMarketplace implements DataAgentMarketplace {

    private static final Logger logger = LoggerFactory.getLogger(NacosDataAgentMarketplace.class);
    public static final String TYPE = "nacos";

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private static final String LATEST_VERSION = "LATEST";

    private final String id;
    private final String serverAddr;
    private final String namespaceId;
    private final String username;
    private final String accessKey;
    private final AiMaintainerService service;

    public NacosDataAgentMarketplace(
            String id,
            String serverAddr,
            String namespaceId,
            String username,
            String password,
            String accessKey,
            String secretKey) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (serverAddr == null || serverAddr.isBlank()) {
            throw new IllegalArgumentException("serverAddr 不能为空");
        }
        this.id = id;
        this.serverAddr = serverAddr.trim();
        this.namespaceId = (namespaceId == null || namespaceId.isBlank()) ? "public" : namespaceId;
        this.username = blankToNull(username);
        this.accessKey = blankToNull(accessKey);

        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, this.serverAddr);
        props.setProperty(PropertyKeyConst.NAMESPACE, this.namespaceId);
        if (this.username != null) {
            props.setProperty(PropertyKeyConst.USERNAME, this.username);
            if (password != null) {
                props.setProperty(PropertyKeyConst.PASSWORD, password);
            }
        }
        if (this.accessKey != null) {
            props.setProperty(PropertyKeyConst.ACCESS_KEY, this.accessKey);
            if (secretKey != null) {
                props.setProperty(PropertyKeyConst.SECRET_KEY, secretKey);
            }
        }
        try {
            this.service = AiMaintainerFactory.createAiMaintainerService(props);
        } catch (NacosException e) {
            throw new IllegalStateException(
                    "为 marketplace " + id + " (" + this.serverAddr + ") 创建"
                            + " Nacos AiMaintainerService 失败",
                    e);
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayLocation() {
        return serverAddr + " / ns=" + namespaceId;
    }

    @Override
    public List<MarketSkillSummary> list() {
        SkillMaintainerService skillService = service.skill();
        List<MarketSkillSummary> all = new ArrayList<>();
        int pageNo = 1;
        try {
            while (pageNo <= MAX_PAGES) {
                Page<SkillSummary> page =
                        skillService.listSkills(namespaceId, null, null, pageNo, PAGE_SIZE);
                if (page == null || page.getPageItems() == null) {
                    break;
                }
                for (SkillSummary s : page.getPageItems()) {
                    String version =
                            s.getEditingVersion() != null
                                    ? s.getEditingVersion()
                                    : s.getReviewingVersion();
                    all.add(new MarketSkillSummary(s.getName(), s.getDescription(), version));
                }
                if (pageNo >= page.getPagesAvailable() || page.getPageItems().size() < PAGE_SIZE) {
                    break;
                }
                pageNo++;
            }
        } catch (NacosException e) {
            throw new IllegalStateException(
                    "Nacos listSkills 失败: " + serverAddr + "/" + namespaceId, e);
        }
        return all;
    }

    @Override
    public MarketSkillContent fetch(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            Skill skill =
                    service.skill().getSkillVersionDetail(namespaceId, name.trim(), LATEST_VERSION);
            if (skill == null || skill.getSkillMd() == null || skill.getSkillMd().isEmpty()) {
                return null;
            }
            Map<String, String> resources = new LinkedHashMap<>();
            Map<String, SkillResource> upstream = skill.getResource();
            if (upstream != null) {
                upstream.forEach(
                        (key, value) -> {
                            if (value == null || value.getContent() == null) {
                                return;
                            }
                            String path =
                                    (value.getName() != null && !value.getName().isBlank())
                                            ? value.getName()
                                            : key;
                            resources.put(path, value.getContent());
                        });
            }
            return new MarketSkillContent(
                    skill.getName(), skill.getDescription(), skill.getSkillMd(), resources);
        } catch (NacosException e) {
            throw new IllegalStateException(
                    "Nacos getSkillVersionDetail 失败: "
                            + serverAddr + "/" + namespaceId + "/" + name,
                    e);
        }
    }

    @Override
    public void close() {
        logger.debug("关闭 nacos marketplace {} ({})", id, serverAddr);
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
