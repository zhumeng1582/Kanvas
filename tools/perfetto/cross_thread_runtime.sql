WITH interaction_window AS (
  SELECT MIN(ts) AS start_ts, MAX(ts + dur) AS end_ts
  FROM slice
  WHERE name = 'Kanvas.interaction.draw'
),
app_threads AS (
  SELECT t.utid, t.name AS thread_name
  FROM thread t
  JOIN process p USING (upid)
  WHERE p.name = 'com.zhumeng.kanvas.example'
)
SELECT
  app_threads.thread_name,
  ROUND(SUM(sched_slice.dur) / 1000000.0, 3) AS total_cpu_ms,
  COUNT(*) AS run_slices,
  ROUND(AVG(sched_slice.dur) / 1000000.0, 3) AS avg_run_ms,
  ROUND(MAX(sched_slice.dur) / 1000000.0, 3) AS max_run_ms
FROM sched_slice
JOIN app_threads USING (utid)
JOIN interaction_window
WHERE sched_slice.ts < interaction_window.end_ts
  AND sched_slice.ts + sched_slice.dur > interaction_window.start_ts
GROUP BY app_threads.thread_name
ORDER BY total_cpu_ms DESC
LIMIT 30;
