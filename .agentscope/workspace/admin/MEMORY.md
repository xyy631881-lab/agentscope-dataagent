# MEMORY.md — Long-Term Memory

## Data Sources

### `analytics_db` (MySQL)
- **Coverage**: 2024 full-year e-commerce data
- **Status**: Currently the only configured data source
- **User language**: Chinese (Simplified)

#### Tables Overview
- **`daily_sales`**: Daily aggregated metrics
- **`products`**: 15 items across 5 categories
- **`users`**: 20 people
- **`orders`**: 120+ records

#### Product Categories
1. 电子产品 (electronics)
2. 运动户外 (sports/outdoor)
3. 食品饮料 (food & beverage)
4. 家居办公 (home & office)
5. 图书教育 (books & education)

---

## Table Schemas

### `daily_sales`
- **Columns** (all NOT NULL):
  - `sale_date` (DATE)
  - `total_revenue` (DECIMAL)
  - `total_orders` (INT)
  - `total_users` (INT)
  - `avg_order_value` (DECIMAL)
- **Observations**: Revenue ranges from ~89 to ~598 per day; sample rows show 1 order and 1 user per row (suggesting low daily volume or sample bias)

### `orders`
- **Columns** (all NOT NULL):
  - `id` (INT)
  - `user_id` (INT)
  - `product_id` (INT)
  - `quantity` (INT)
  - `total_amount` (DECIMAL 12) — *distinct from `daily_sales.total_revenue`*
  - `status` (VARCHAR 20) — observed value: `completed`
  - `channel` (VARCHAR 20) — observed values: `app`, `web`
  - `created_at` (DATETIME) — *distinct from `daily_sales.sale_date` (DATE)*