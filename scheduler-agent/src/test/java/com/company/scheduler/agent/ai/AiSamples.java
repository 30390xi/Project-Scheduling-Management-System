package com.company.scheduler.agent.ai;

/** 固定测试样本：真实供应商连通测试独立开启，CI 只用这些固定样本与 mock 网关。 */
public final class AiSamples {

    /** TRD 7.3 示例的完整合法输出（单叶子，不需要拆解）。 */
    public static final String VALID_SINGLE_LEAF = """
            {
              "schemaVersion": "1.0",
              "taskType": "BACKEND_DEVELOPMENT",
              "summary": "实现登录接口并交付接口测试结果",
              "requiresDecomposition": false,
              "estimate": {
                "optimisticMinutes": 180,
                "likelyMinutes": 240,
                "pessimisticMinutes": 360,
                "basis": "采用项目已有鉴权组件；不包含第三方 SSO"
              },
              "requiredSkills": [{"code": "JAVA", "minLevel": 3}],
              "acceptanceCriteria": ["成功登录返回有效会话", "错误密码拒绝登录"],
              "assumptions": [{"text": "项目已有鉴权组件", "confirmed": false}],
              "missingInformation": ["是否需要对接企业 SSO"],
              "confidence": 0.76,
              "subtasks": [],
              "dependencies": []
            }
            """;

    /** 拆解样本：S1→S2 与 S1→S3 两条 FS 依赖。 */
    public static final String VALID_DECOMPOSITION = """
            {
              "schemaVersion": "1.0",
              "taskType": "BACKEND_DEVELOPMENT",
              "summary": "后台小模块：接口开发与页面实现",
              "requiresDecomposition": true,
              "estimate": {
                "optimisticMinutes": 1200,
                "likelyMinutes": 1500,
                "pessimisticMinutes": 1800,
                "basis": "包含接口与页面两条线"
              },
              "requiredSkills": [],
              "acceptanceCriteria": ["接口通过联调", "页面完成验收"],
              "assumptions": [],
              "missingInformation": [],
              "confidence": 0.71,
              "subtasks": [
                {"clientKey": "S1", "title": "接口开发", "scope": "登录与查询接口",
                 "deliverables": ["接口文档", "联调通过的接口"], "acceptanceCriteria": ["接口测试通过"],
                 "estimate": {"optimisticMinutes": 240, "likelyMinutes": 300, "pessimisticMinutes": 420, "basis": "两个接口"},
                 "requiredSkills": [{"code": "JAVA", "minLevel": 3}]},
                {"clientKey": "S2", "title": "页面实现", "scope": "登录页与列表页",
                 "deliverables": ["可访问页面"], "acceptanceCriteria": ["页面走查通过"],
                 "estimate": {"optimisticMinutes": 180, "likelyMinutes": 240, "pessimisticMinutes": 300, "basis": "两个页面"},
                 "requiredSkills": []},
                {"clientKey": "S3", "title": "联调验收", "scope": "接口与页面联调",
                 "deliverables": ["联调报告"], "acceptanceCriteria": ["无阻断缺陷"],
                 "estimate": {"optimisticMinutes": 120, "likelyMinutes": 150, "pessimisticMinutes": 240, "basis": "一轮联调"},
                 "requiredSkills": []}
              ],
              "dependencies": [
                {"from": "S1", "to": "S3", "type": "FS"},
                {"from": "S2", "to": "S3", "type": "FS"}
              ]
            }
            """;

    public static final java.util.List<String> SKILL_CATALOG = java.util.List.of("JAVA", "SQL", "FRONTEND");

    private AiSamples() {}
}
