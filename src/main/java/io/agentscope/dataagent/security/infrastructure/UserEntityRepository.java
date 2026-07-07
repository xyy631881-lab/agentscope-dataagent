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
package io.agentscope.dataagent.security.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * UserEntityRepository 是"用户表的数据库操作层"——它通过 Spring Data JPA 自动生成 SQL，
 * 提供对 dataagent_user 表的增删改查能力，是整个用户管理体系的底层存储引擎。
 *
 * Spring Data JPA 的魔法：
 * 只要方法名符合命名规范（findBy/existsBy + 字段名 + IgnoreCase），
 * 它就自动生成 SQL 实现，不需要写任何 SQL 语句。
 *
 * -- findByUsernameIgnoreCase
 * SELECT * FROM dataagent_user WHERE LOWER(username) = LOWER(?)
 *
 * -- existsByUsernameIgnoreCase
 * SELECT COUNT(*) > 0 FROM dataagent_user WHERE LOWER(username) = LOWER(?)
 *
 * 整个系统需要管理用户（登录、注册、改密码、分配角色），
 * 这些用户数据必须持久化到数据库。UserEntityRepository 就是这个数据库访问层。
 */
public interface UserEntityRepository extends JpaRepository<UserEntity, String> {

    // 通过用户名查询用户（忽略大小写）
    Optional<UserEntity> findByUsernameIgnoreCase(String username);

    // 检查用户名是否存在（忽略大小写）
    boolean existsByUsernameIgnoreCase(String username);
}
