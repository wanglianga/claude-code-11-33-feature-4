-- 测试专用：每次启动重建独立 schema，与开发库 public 完全隔离
DROP SCHEMA IF EXISTS recall_test CASCADE;
CREATE SCHEMA recall_test;
