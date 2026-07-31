-- Replace the date literals after checking MAX(sale_date).
SELECT
    sale_date,
    total_revenue,
    total_orders,
    total_users,
    avg_order_value
FROM daily_sales
WHERE sale_date >= '2024-12-22'
  AND sale_date < '2024-12-29'
ORDER BY sale_date;
