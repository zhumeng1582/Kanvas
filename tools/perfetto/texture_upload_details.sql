WITH interaction_window AS (
  SELECT MIN(ts) AS start_ts, MAX(ts + dur) AS end_ts
  FROM slice
  WHERE name = 'Kanvas.interaction.draw'
), uploads AS (
  SELECT s.id, s.ts, s.dur, s.name, s.parent_id, s.arg_set_id, s.track_id
  FROM slice s, interaction_window
  WHERE s.name GLOB 'Texture upload*'
    AND s.ts BETWEEN interaction_window.start_ts AND interaction_window.end_ts
)
SELECT
  u.name,
  p.name AS parent_name,
  gp.name AS grandparent_name,
  a.key,
  a.display_value,
  COUNT(*) AS count
FROM uploads u
LEFT JOIN slice p ON p.id = u.parent_id
LEFT JOIN slice gp ON gp.id = p.parent_id
LEFT JOIN args a ON a.arg_set_id = u.arg_set_id
GROUP BY u.name, parent_name, grandparent_name, a.key, a.display_value
ORDER BY u.name, a.key;
