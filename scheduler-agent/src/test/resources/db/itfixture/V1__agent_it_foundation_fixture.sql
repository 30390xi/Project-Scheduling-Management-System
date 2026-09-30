-- 仅用于 scheduler-agent 模块集成测试的基础表 fixture：
-- 镜像 scheduler-app foundation V1 中被 agent 外键引用的最小形状（company、company_member 的 FK 目标列）。
-- 生产迁移在 scheduler-app 的 db/migration/foundation 下，agent 模块不可依赖（会形成循环依赖）。
-- 该文件只在 agent 测试 classpath 上，不会出现在应用运行时。

CREATE TABLE company (
    id uuid PRIMARY KEY
);

CREATE TABLE company_member (
    company_id uuid NOT NULL REFERENCES company(id),
    id uuid NOT NULL,
    PRIMARY KEY (company_id, id)
);
