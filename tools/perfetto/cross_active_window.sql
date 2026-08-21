WITH cross_window AS (
  SELECT
    MIN(CASE WHEN name GLOB '*ACTION_DOWN deviceId=-1*' THEN ts END) AS start_ts,
    MAX(CASE WHEN name GLOB '*ACTION_UP deviceId=-1*' THEN ts + dur END) AS end_ts
  FROM slice
), app_slice AS (
  SELECT s.name, s.dur, t.name AS thread_name
  FROM slice s
  JOIN thread_track tt ON s.track_id = tt.id
  JOIN thread t USING (utid)
  JOIN process p USING (upid)
  JOIN cross_window
  WHERE p.name = 'com.zhumeng.kanvas.example'
    AND t.name IN ('.kanvas.example', 'RenderThread')
    AND s.ts < cross_window.end_ts
    AND s.ts + s.dur > cross_window.start_ts
), ranked AS (
  SELECT
    thread_name,
    name,
    COUNT(*) AS count,
    ROUND(SUM(dur) / 1000000.0, 3) AS total_ms,
    ROUND(AVG(dur) / 1000000.0, 3) AS avg_ms,
    ROUND(MAX(dur) / 1000000.0, 3) AS max_ms,
    ROW_NUMBER() OVER (
      PARTITION BY thread_name
      ORDER BY SUM(dur) DESC
    ) AS rank_in_thread
  FROM app_slice
  GROUP BY thread_name, name
  HAVING total_ms >= 1.0
)
SELECT thread_name, name, count, total_ms, avg_ms, max_ms
FROM ranked
WHERE rank_in_thread <= 30
ORDER BY thread_name, total_ms DESC;
