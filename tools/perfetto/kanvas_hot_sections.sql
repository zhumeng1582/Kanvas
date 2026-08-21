SELECT
  s.name,
  COUNT(*) AS count,
  ROUND(AVG(s.dur) / 1000000.0, 3) AS avg_ms,
  ROUND(MAX(s.dur) / 1000000.0, 3) AS max_ms
FROM slice s
WHERE s.name GLOB 'Kanvas.*'
GROUP BY s.name
ORDER BY avg_ms DESC;
