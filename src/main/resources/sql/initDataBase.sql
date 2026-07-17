
-- 1. 先确保使用正确的数据库
CREATE DATABASE "ai-agent";
\c "ai-agent";

-- 2. 创建必要的扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 3. 验证扩展是否创建成功
SELECT *
FROM pg_extension;

-- 4. 创建 pgvector 向量存储表
-- 当前项目本地配置使用 BAAI/bge-m3，向量维度为 1024
CREATE TABLE public.vector_store_bge_m3
(
    id        UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1024)
);

-- 确认 pgvector 插件已安装
CREATE EXTENSION IF NOT EXISTS vector;
