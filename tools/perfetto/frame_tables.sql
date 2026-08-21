SELECT 'actual' AS source, name, type
FROM pragma_table_info('actual_frame_timeline_slice')
UNION ALL
SELECT 'expected' AS source, name, type
FROM pragma_table_info('expected_frame_timeline_slice')
UNION ALL
SELECT 'frame_slice' AS source, name, type
FROM pragma_table_info('frame_slice');
