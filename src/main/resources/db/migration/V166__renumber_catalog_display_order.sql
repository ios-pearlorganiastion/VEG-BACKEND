-- V166: one-time clean-up of catalog display order.
--
-- Until now display_order was a free number typed in by the admin: almost every row was 0
-- (so rows tied), and duplicates/gaps were possible. From this release the order is managed
-- only by the server (append on create, PATCH .../move to reorder) and is stored as a dense
-- 1..n sequence, so the numbers an admin sees match real positions.
--
-- This renumbers existing rows 1..n while KEEPING the order they currently display in
-- (display_order, then name, then id as tie-breakers):
--   * categories:    one sequence across all categories
--   * subcategories: one sequence per category

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (ORDER BY display_order ASC, name ASC, id ASC) AS rn
    FROM catalog_categories
)
UPDATE catalog_categories c
SET display_order = ranked.rn
FROM ranked
WHERE c.id = ranked.id;

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY category_id ORDER BY display_order ASC, name ASC, id ASC) AS rn
    FROM catalog_subcategories
)
UPDATE catalog_subcategories s
SET display_order = ranked.rn
FROM ranked
WHERE s.id = ranked.id;

-- Support the ordered list / move queries.
CREATE INDEX IF NOT EXISTS idx_catalog_categories_display_order
    ON catalog_categories (display_order);
CREATE INDEX IF NOT EXISTS idx_catalog_subcategories_category_display_order
    ON catalog_subcategories (category_id, display_order);
