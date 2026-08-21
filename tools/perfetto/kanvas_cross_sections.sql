WITH cross_window AS (
  SELECT
    MIN(CASE WHEN name GLOB '*ACTION_DOWN deviceId=-1*' THEN ts END) AS start_ts,
    MAX(CASE WHEN name GLOB '*ACTION_UP deviceId=-1*' THEN ts + dur END) AS end_ts
  FROM slice
), app_slice AS (
  SELECT s.name, s.dur
  FROM slice s
  JOIN thread_track tt ON s.track_id = tt.id
  JOIN thread t USING (utid)
  JOIN process p USING (upid)
  JOIN cross_window
  WHERE p.name = 'com.zhumeng.kanvas.example'
    AND s.name GLOB 'Kanvas.*'
    AND s.ts < cross_window.end_ts
    AND s.ts + s.dur > cross_window.start_ts
)
SELECT
  name,
  COUNT(*) AS count,
  ROUND(SUM(dur) / 1000000.0, 3) AS total_ms,
  ROUND(AVG(dur) / 1000000.0, 3) AS avg_ms,
  ROUND(MAX(dur) / 1000000.0, 3) AS max_ms
FROM app_slice
GROUP BY name
ORDER BY total_ms DESC;
