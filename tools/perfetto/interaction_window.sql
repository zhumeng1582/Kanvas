SELECT
  COUNT(*) AS count,
  ROUND((MAX(ts + dur) - MIN(ts)) / 1000000000.0, 3) AS span_s,
  ROUND(SUM(dur) / 1000000.0, 3) AS total_ms,
  ROUND(AVG(dur) / 1000000.0, 3) AS avg_ms,
  ROUND(MAX(dur) / 1000000.0, 3) AS max_ms
FROM slice
WHERE name = 'Kanvas.interaction.draw';
