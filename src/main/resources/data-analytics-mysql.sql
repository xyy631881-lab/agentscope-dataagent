-- ============================================================
-- AgentScope DataAgent 分析数据库种子脚本 (MySQL 8 版本)
-- 模拟真实电商业务场景: 销售、用户、产品、库存
--
-- 特点:
--   1. 幂等: DROP TABLE IF EXISTS 保证重复执行无副作用
--   2. 标准 MySQL 8 语法: ENGINE=InnoDB, CHARSET=utf8mb4
--   3. 与 data-analytics.sql (H2 版) 数据完全一致
-- ============================================================

-- 先删后建，保证幂等（启动时重复执行无副作用）
DROP TABLE IF EXISTS daily_sales;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS products;

-- 产品表
CREATE TABLE products (
    id          INT PRIMARY KEY,
    name        VARCHAR(200)  NOT NULL,
    category    VARCHAR(50)   NOT NULL,
    subcategory VARCHAR(50),
    unit_price  DECIMAL(10,2) NOT NULL,
    cost        DECIMAL(10,2) NOT NULL,
    supplier    VARCHAR(100),
    created_at  DATE          NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户表
CREATE TABLE users (
    id            INT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(200),
    city          VARCHAR(50),
    province      VARCHAR(50),
    channel       VARCHAR(20) NOT NULL,  -- app / web / miniprogram
    registered_at DATE         NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 订单表
CREATE TABLE orders (
    id            INT PRIMARY KEY,
    user_id       INT           NOT NULL,
    product_id    INT           NOT NULL,
    quantity      INT           NOT NULL,
    total_amount  DECIMAL(12,2) NOT NULL,
    status        VARCHAR(20)   NOT NULL,  -- completed / cancelled / refunded
    channel       VARCHAR(20)   NOT NULL,
    created_at    DATETIME      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每日销售汇总表
CREATE TABLE daily_sales (
    sale_date       DATE          PRIMARY KEY,
    total_revenue   DECIMAL(14,2) NOT NULL,
    total_orders    INT           NOT NULL,
    total_users     INT           NOT NULL,
    avg_order_value DECIMAL(10,2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 种子数据
-- ============================================================

-- 产品数据 (15 款，覆盖 5 大品类)
INSERT INTO products VALUES (1,  '无线蓝牙耳机 Pro',    '电子产品', '音频',   299.00, 180.00, '深圳声学科技',  '2024-03-01');
INSERT INTO products VALUES (2,  '智能手表 S3',         '电子产品', '穿戴',   899.00, 520.00, '深圳声学科技',  '2024-05-15');
INSERT INTO products VALUES (3,  '机械键盘 K8',          '电子产品', '外设',   399.00, 220.00, '东莞键鼠制造',  '2024-01-10');
INSERT INTO products VALUES (4,  '27寸 4K 显示器',       '电子产品', '显示',  2499.00, 1600.00,'京东方科技',    '2024-04-20');
INSERT INTO products VALUES (5,  '便携充电宝 20000mAh',  '电子产品', '配件',   129.00, 65.00,  '深圳电池科技',  '2024-02-15');
INSERT INTO products VALUES (6,  '瑜伽垫 (加厚)',         '运动户外', '瑜伽',    89.00, 45.00,  '义乌体育用品',  '2024-03-10');
INSERT INTO products VALUES (7,  '跑步鞋 AirRun',         '运动户外', '跑步',   599.00, 320.00, '福建鞋业集团',  '2024-05-01');
INSERT INTO products VALUES (8,  '运动水壶 750ml',        '运动户外', '配件',    69.00, 32.00,  '义乌体育用品',  '2024-04-12');
INSERT INTO products VALUES (9,  '有机绿茶礼盒',          '食品饮料', '茶叶',   188.00, 95.00,  '杭州龙井茶业',  '2024-01-20');
INSERT INTO products VALUES (10, '坚果混合装 1kg',       '食品饮料', '零食',    79.00, 40.00,  '三只仓鼠食品',  '2024-03-05');
INSERT INTO products VALUES (11, '云南咖啡豆 500g',      '食品饮料', '咖啡',    99.00, 52.00,  '云南咖啡庄园',  '2024-06-01');
INSERT INTO products VALUES (12, '办公椅 (人体工学)',     '家居办公', '家具',  1299.00, 750.00, '佛山家具制造',  '2024-02-01');
INSERT INTO products VALUES (13, 'LED 护眼台灯',          '家居办公', '灯具',   199.00, 98.00,  '中山照明科技',  '2024-03-20');
INSERT INTO products VALUES (14, 'Python 编程入门 (书籍)', '图书教育', '编程',    59.00, 28.00,  '机械工业出版',  '2024-01-05');
INSERT INTO products VALUES (15, '深度学习实战 (书籍)',    '图书教育', 'AI',      89.00, 45.00,  '人民邮电出版',  '2024-06-15');

-- 用户数据 (20 人，分布在全国 6 个城市)
INSERT INTO users VALUES (1,  '张三', 'zhangsan@qq.com',   '北京', '北京',   'app',         '2024-01-15');
INSERT INTO users VALUES (2,  '李四', 'lisi@163.com',      '上海', '上海',   'web',         '2024-02-20');
INSERT INTO users VALUES (3,  '王五', 'wangwu@gmail.com',  '广州', '广东',   'miniprogram', '2024-03-10');
INSERT INTO users VALUES (4,  '赵六', 'zhaoliu@qq.com',    '深圳', '广东',   'app',         '2024-03-15');
INSERT INTO users VALUES (5,  '孙七', 'sunqi@163.com',     '杭州', '浙江',   'web',         '2024-04-01');
INSERT INTO users VALUES (6,  '周八', 'zhouba@gmail.com',  '成都', '四川',   'miniprogram', '2024-04-10');
INSERT INTO users VALUES (7,  '吴九', 'wujiu@qq.com',      '北京', '北京',   'app',         '2024-05-05');
INSERT INTO users VALUES (8,  '郑十', 'zhengshi@163.com',  '上海', '上海',   'web',         '2024-05-20');
INSERT INTO users VALUES (9,  '刘一', 'liuyi@gmail.com',   '广州', '广东',   'miniprogram', '2024-06-01');
INSERT INTO users VALUES (10, '陈二', 'chener@qq.com',     '深圳', '广东',   'app',         '2024-06-10');
INSERT INTO users VALUES (11, '黄三', 'huangsan@163.com',  '杭州', '浙江',   'web',         '2024-07-01');
INSERT INTO users VALUES (12, '林四', 'linsi@gmail.com',   '成都', '四川',   'miniprogram', '2024-07-15');
INSERT INTO users VALUES (13, '何五', 'hewu@qq.com',       '北京', '北京',   'app',         '2024-08-01');
INSERT INTO users VALUES (14, '马六', 'maliu@163.com',     '上海', '上海',   'web',         '2024-08-20');
INSERT INTO users VALUES (15, '杨七', 'yangqi@gmail.com',  '广州', '广东',   'miniprogram', '2024-09-01');
INSERT INTO users VALUES (16, '朱八', 'zhuba@qq.com',      '深圳', '广东',   'app',         '2024-09-15');
INSERT INTO users VALUES (17, '秦九', 'qinjiu@163.com',    '杭州', '浙江',   'web',         '2024-10-01');
INSERT INTO users VALUES (18, '许十', 'xushi@gmail.com',   '成都', '四川',   'miniprogram', '2024-10-15');
INSERT INTO users VALUES (19, '韩一', 'hanyi@qq.com',      '北京', '北京',   'app',         '2024-11-01');
INSERT INTO users VALUES (20, '唐二', 'tanger@163.com',    '上海', '上海',   'web',         '2024-11-15');

-- 订单数据 (120 条，覆盖 2024 年全年)
-- Q1 (1-3月): 业务起步期
INSERT INTO orders VALUES (1,  1,  3,  1, 399.00,  'completed',  'app',         '2024-01-20 10:30:00');
INSERT INTO orders VALUES (2,  2,  1,  2, 598.00,  'completed',  'web',         '2024-02-25 14:15:00');
INSERT INTO orders VALUES (3,  3,  14, 1, 59.00,   'completed',  'miniprogram', '2024-03-10 09:00:00');
INSERT INTO orders VALUES (4,  4,  5,  3, 387.00,  'completed',  'app',         '2024-03-15 16:45:00');
INSERT INTO orders VALUES (5,  1,  6,  1, 89.00,   'completed',  'app',         '2024-03-20 11:20:00');
INSERT INTO orders VALUES (6,  5,  9,  2, 376.00,  'completed',  'web',         '2024-04-05 08:30:00');
INSERT INTO orders VALUES (7,  6,  7,  1, 599.00,  'completed',  'miniprogram', '2024-04-10 13:00:00');
INSERT INTO orders VALUES (8,  7,  2,  1, 899.00,  'completed',  'app',         '2024-05-05 10:10:00');
INSERT INTO orders VALUES (9,  8,  4,  1, 2499.00, 'completed',  'web',         '2024-05-20 15:30:00');
INSERT INTO orders VALUES (10, 9,  10, 3, 237.00,  'completed',  'miniprogram', '2024-06-01 09:45:00');
INSERT INTO orders VALUES (11, 10, 11, 2, 198.00,  'completed',  'app',         '2024-06-10 14:20:00');
INSERT INTO orders VALUES (12, 11, 12, 1, 1299.00, 'completed',  'web',         '2024-07-01 11:00:00');
INSERT INTO orders VALUES (13, 12, 13, 1, 199.00,  'completed',  'miniprogram', '2024-07-15 16:30:00');
INSERT INTO orders VALUES (14, 13, 15, 1, 89.00,   'completed',  'app',         '2024-08-01 10:00:00');
INSERT INTO orders VALUES (15, 14, 8,  2, 138.00,  'completed',  'web',         '2024-08-20 13:45:00');
INSERT INTO orders VALUES (16, 15, 1,  1, 299.00,  'completed',  'miniprogram', '2024-09-01 09:15:00');
INSERT INTO orders VALUES (17, 16, 3,  2, 798.00,  'completed',  'app',         '2024-09-15 15:00:00');
INSERT INTO orders VALUES (18, 17, 5,  1, 129.00,  'completed',  'web',         '2024-10-01 12:30:00');
INSERT INTO orders VALUES (19, 18, 6,  2, 178.00,  'completed',  'miniprogram', '2024-10-15 10:45:00');
INSERT INTO orders VALUES (20, 19, 7,  1, 599.00,  'completed',  'app',         '2024-11-01 14:00:00');
INSERT INTO orders VALUES (21, 20, 9,  1, 188.00,  'completed',  'web',         '2024-11-15 11:30:00');
INSERT INTO orders VALUES (22, 1,  10, 2, 158.00,  'completed',  'app',         '2024-01-28 09:00:00');
INSERT INTO orders VALUES (23, 3,  2,  1, 899.00,  'completed',  'miniprogram', '2024-03-18 14:30:00');
INSERT INTO orders VALUES (24, 5,  4,  1, 2499.00, 'completed',  'web',         '2024-04-12 10:00:00');
INSERT INTO orders VALUES (25, 7,  8,  3, 207.00,  'completed',  'app',         '2024-05-12 16:00:00');
INSERT INTO orders VALUES (26, 9,  12, 1, 1299.00, 'cancelled',  'miniprogram', '2024-06-08 11:30:00');
INSERT INTO orders VALUES (27, 2,  13, 1, 199.00,  'completed',  'web',         '2024-03-25 09:15:00');
INSERT INTO orders VALUES (28, 4,  14, 2, 118.00,  'completed',  'app',         '2024-04-18 14:45:00');
INSERT INTO orders VALUES (29, 6,  15, 1, 89.00,   'refunded',   'miniprogram', '2024-05-08 10:30:00');
INSERT INTO orders VALUES (30, 8,  1,  1, 299.00,  'completed',  'web',         '2024-06-15 13:00:00');
INSERT INTO orders VALUES (31, 10, 3,  1, 399.00,  'completed',  'app',         '2024-07-05 09:30:00');
INSERT INTO orders VALUES (32, 12, 5,  2, 258.00,  'completed',  'miniprogram', '2024-08-10 11:15:00');
INSERT INTO orders VALUES (33, 14, 7,  1, 599.00,  'completed',  'web',         '2024-09-08 14:00:00');
INSERT INTO orders VALUES (34, 16, 9,  3, 564.00,  'completed',  'app',         '2024-10-05 10:45:00');
INSERT INTO orders VALUES (35, 18, 11, 1, 99.00,   'completed',  'miniprogram', '2024-11-05 15:30:00');
INSERT INTO orders VALUES (36, 20, 2,  1, 899.00,  'completed',  'web',         '2024-12-01 09:00:00');
INSERT INTO orders VALUES (37, 1,  4,  1, 2499.00, 'completed',  'app',         '2024-12-10 14:15:00');
INSERT INTO orders VALUES (38, 3,  6,  1, 89.00,   'completed',  'miniprogram', '2024-02-28 11:00:00');
INSERT INTO orders VALUES (39, 5,  10, 1, 79.00,   'completed',  'web',         '2024-07-20 16:45:00');
INSERT INTO orders VALUES (40, 7,  13, 1, 199.00,  'completed',  'app',         '2024-09-20 10:30:00');
INSERT INTO orders VALUES (41, 9,  1,  2, 598.00,  'completed',  'miniprogram', '2024-11-20 13:15:00');
INSERT INTO orders VALUES (42, 11, 3,  1, 399.00,  'completed',  'web',         '2024-08-25 09:45:00');
INSERT INTO orders VALUES (43, 13, 5,  1, 129.00,  'completed',  'app',         '2024-12-15 11:00:00');
INSERT INTO orders VALUES (44, 15, 8,  2, 138.00,  'completed',  'miniprogram', '2024-10-20 14:30:00');
INSERT INTO orders VALUES (45, 17, 12, 1, 1299.00, 'cancelled',  'web',         '2024-12-20 10:00:00');
INSERT INTO orders VALUES (46, 19, 14, 3, 177.00,  'completed',  'app',         '2024-12-25 15:15:00');
INSERT INTO orders VALUES (47, 2,  7,  1, 599.00,  'completed',  'web',         '2024-06-25 09:00:00');
INSERT INTO orders VALUES (48, 4,  9,  1, 188.00,  'completed',  'app',         '2024-07-25 13:45:00');
INSERT INTO orders VALUES (49, 6,  11, 2, 198.00,  'completed',  'miniprogram', '2024-08-30 10:30:00');
INSERT INTO orders VALUES (50, 8,  15, 1, 89.00,   'completed',  'web',         '2024-09-25 15:00:00');
INSERT INTO orders VALUES (51, 10, 2,  1, 899.00,  'completed',  'app',         '2024-10-25 11:15:00');
INSERT INTO orders VALUES (52, 12, 4,  1, 2499.00, 'completed',  'miniprogram', '2024-11-25 09:30:00');
INSERT INTO orders VALUES (53, 14, 1,  1, 299.00,  'completed',  'web',         '2024-12-28 14:45:00');
INSERT INTO orders VALUES (54, 16, 10, 4, 316.00,  'completed',  'app',         '2024-05-28 10:00:00');
INSERT INTO orders VALUES (55, 18, 6,  1, 89.00,   'completed',  'miniprogram', '2024-07-28 13:30:00');
INSERT INTO orders VALUES (56, 20, 13, 2, 398.00,  'completed',  'web',         '2024-09-28 16:15:00');
INSERT INTO orders VALUES (57, 1,  8,  1, 69.00,   'completed',  'app',         '2024-04-22 09:45:00');
INSERT INTO orders VALUES (58, 3,  4,  1, 2499.00, 'completed',  'miniprogram', '2024-06-22 11:30:00');
INSERT INTO orders VALUES (59, 5,  11, 1, 99.00,   'completed',  'web',         '2024-08-22 14:00:00');
INSERT INTO orders VALUES (60, 7,  14, 2, 118.00,  'completed',  'app',         '2024-10-22 10:15:00');
INSERT INTO orders VALUES (61, 9,  7,  1, 599.00,  'completed',  'miniprogram', '2024-12-22 13:45:00');
INSERT INTO orders VALUES (62, 11, 9,  2, 376.00,  'completed',  'web',         '2024-03-30 09:30:00');
INSERT INTO orders VALUES (63, 13, 12, 1, 1299.00, 'completed',  'app',         '2024-05-30 15:00:00');
INSERT INTO orders VALUES (64, 15, 1,  1, 299.00,  'refunded',   'miniprogram', '2024-07-30 11:15:00');
INSERT INTO orders VALUES (65, 17, 3,  2, 798.00,  'completed',  'web',         '2024-09-30 10:45:00');
INSERT INTO orders VALUES (66, 19, 5,  1, 129.00,  'completed',  'app',         '2024-11-30 14:30:00');
INSERT INTO orders VALUES (67, 2,  6,  1, 89.00,   'completed',  'web',         '2024-01-25 09:00:00');
INSERT INTO orders VALUES (68, 4,  8,  3, 207.00,  'completed',  'app',         '2024-02-22 13:30:00');
INSERT INTO orders VALUES (69, 6,  10, 2, 158.00,  'completed',  'miniprogram', '2024-04-28 10:00:00');
INSERT INTO orders VALUES (70, 8,  13, 1, 199.00,  'completed',  'web',         '2024-06-28 14:15:00');

-- Q3-Q4 密集订单，模拟业务增长
INSERT INTO orders VALUES (71, 1,  1,  2, 598.00,  'completed',  'app',         '2024-07-08 10:30:00');
INSERT INTO orders VALUES (72, 2,  1,  1, 299.00,  'completed',  'web',         '2024-07-08 11:00:00');
INSERT INTO orders VALUES (73, 3,  2,  1, 899.00,  'completed',  'miniprogram', '2024-07-08 14:30:00');
INSERT INTO orders VALUES (74, 4,  3,  1, 399.00,  'completed',  'app',         '2024-07-09 09:15:00');
INSERT INTO orders VALUES (75, 5,  5,  2, 258.00,  'completed',  'web',         '2024-07-09 10:45:00');
INSERT INTO orders VALUES (76, 6,  7,  1, 599.00,  'completed',  'miniprogram', '2024-07-10 13:00:00');
INSERT INTO orders VALUES (77, 7,  9,  1, 188.00,  'completed',  'app',         '2024-07-11 15:30:00');
INSERT INTO orders VALUES (78, 8,  11, 2, 198.00,  'completed',  'web',         '2024-07-12 09:00:00');
INSERT INTO orders VALUES (79, 9,  1,  1, 299.00,  'completed',  'miniprogram', '2024-08-05 10:15:00');
INSERT INTO orders VALUES (80, 10, 2,  1, 899.00,  'completed',  'app',         '2024-08-06 14:30:00');
INSERT INTO orders VALUES (81, 11, 4,  1, 2499.00, 'completed',  'web',         '2024-08-07 11:00:00');
INSERT INTO orders VALUES (82, 12, 6,  2, 178.00,  'completed',  'miniprogram', '2024-08-08 16:15:00');
INSERT INTO orders VALUES (83, 13, 10, 1, 79.00,   'completed',  'app',         '2024-08-09 09:30:00');
INSERT INTO orders VALUES (84, 14, 12, 1, 1299.00, 'completed',  'web',         '2024-08-10 13:45:00');
INSERT INTO orders VALUES (85, 15, 15, 2, 178.00,  'completed',  'miniprogram', '2024-08-11 10:00:00');
INSERT INTO orders VALUES (86, 16, 7,  1, 599.00,  'completed',  'app',         '2024-08-12 14:15:00');
INSERT INTO orders VALUES (87, 17, 3,  1, 399.00,  'completed',  'web',         '2024-09-02 11:30:00');
INSERT INTO orders VALUES (88, 18, 5,  3, 387.00,  'completed',  'miniprogram', '2024-09-03 09:45:00');
INSERT INTO orders VALUES (89, 19, 8,  1, 69.00,   'completed',  'app',         '2024-09-04 15:00:00');
INSERT INTO orders VALUES (90, 20, 13, 1, 199.00,  'completed',  'web',         '2024-09-05 10:30:00');
INSERT INTO orders VALUES (91, 1,  4,  1, 2499.00, 'completed',  'app',         '2024-09-06 14:00:00');
INSERT INTO orders VALUES (92, 2,  6,  1, 89.00,   'completed',  'web',         '2024-09-09 09:15:00');
INSERT INTO orders VALUES (93, 3,  9,  2, 376.00,  'completed',  'miniprogram', '2024-09-10 11:45:00');
INSERT INTO orders VALUES (94, 4,  11, 1, 99.00,   'completed',  'app',         '2024-09-11 13:00:00');
INSERT INTO orders VALUES (95, 5,  1,  1, 299.00,  'completed',  'web',         '2024-10-07 10:15:00');
INSERT INTO orders VALUES (96, 6,  2,  1, 899.00,  'completed',  'miniprogram', '2024-10-08 14:30:00');
INSERT INTO orders VALUES (97, 7,  4,  1, 2499.00, 'completed',  'app',         '2024-10-09 09:00:00');
INSERT INTO orders VALUES (98, 8,  7,  1, 599.00,  'completed',  'web',         '2024-10-10 11:15:00');
INSERT INTO orders VALUES (99, 9,  14, 3, 177.00,  'completed',  'miniprogram', '2024-10-11 15:45:00');
INSERT INTO orders VALUES (100,10, 10, 2, 158.00,  'completed',  'app',         '2024-11-04 09:30:00');
INSERT INTO orders VALUES (101,11, 15, 1, 89.00,   'completed',  'web',         '2024-11-05 13:00:00');
INSERT INTO orders VALUES (102,12, 3,  1, 399.00,  'completed',  'miniprogram', '2024-11-06 10:45:00');
INSERT INTO orders VALUES (103,13, 5,  2, 258.00,  'completed',  'app',         '2024-11-07 14:30:00');
INSERT INTO orders VALUES (104,14, 12, 1, 1299.00, 'completed',  'web',         '2024-11-08 11:00:00');
INSERT INTO orders VALUES (105,15, 1,  1, 299.00,  'completed',  'miniprogram', '2024-11-11 16:15:00');
INSERT INTO orders VALUES (106,16, 6,  2, 178.00,  'completed',  'app',         '2024-11-12 09:45:00');
INSERT INTO orders VALUES (107,17, 9,  1, 188.00,  'completed',  'web',         '2024-12-02 14:00:00');
INSERT INTO orders VALUES (108,18, 13, 1, 199.00,  'completed',  'miniprogram', '2024-12-03 10:30:00');
INSERT INTO orders VALUES (109,19, 7,  1, 599.00,  'completed',  'app',         '2024-12-04 15:15:00');
INSERT INTO orders VALUES (110,20, 2,  1, 899.00,  'completed',  'web',         '2024-12-05 11:45:00');
INSERT INTO orders VALUES (111,1,  10, 3, 237.00,  'completed',  'app',         '2024-12-09 09:00:00');
INSERT INTO orders VALUES (112,3,  4,  1, 2499.00, 'completed',  'miniprogram', '2024-12-10 13:30:00');
INSERT INTO orders VALUES (113,5,  8,  1, 69.00,   'completed',  'web',         '2024-12-11 10:15:00');
INSERT INTO orders VALUES (114,7,  11, 1, 99.00,   'completed',  'app',         '2024-12-12 14:45:00');
INSERT INTO orders VALUES (115,9,  5,  1, 129.00,  'completed',  'miniprogram', '2024-12-16 11:00:00');
INSERT INTO orders VALUES (116,11, 14, 2, 118.00,  'completed',  'web',         '2024-12-17 09:30:00');
INSERT INTO orders VALUES (117,13, 1,  1, 299.00,  'completed',  'app',         '2024-12-18 15:00:00');
INSERT INTO orders VALUES (118,15, 6,  1, 89.00,   'completed',  'miniprogram', '2024-12-19 10:45:00');
INSERT INTO orders VALUES (119,17, 3,  2, 798.00,  'completed',  'web',         '2024-12-23 13:30:00');
INSERT INTO orders VALUES (120,19, 9,  1, 188.00,  'completed',  'app',         '2024-12-24 11:15:00');

-- 每日销售汇总 (从订单数据聚合预计算)
-- MySQL 用 DATE() 函数提取日期部分，比 H2 的 CAST(... AS DATE) 更标准
INSERT INTO daily_sales
SELECT
    DATE(created_at)            AS sale_date,
    SUM(total_amount)           AS total_revenue,
    COUNT(*)                    AS total_orders,
    COUNT(DISTINCT user_id)     AS total_users,
    ROUND(AVG(total_amount), 2) AS avg_order_value
FROM orders
WHERE status = 'completed'
GROUP BY DATE(created_at)
ORDER BY sale_date;
