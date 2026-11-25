# 独立 AI 原型

这是独立 Spring Boot 工程，根项目 `neighbor-life` 的 POM 没有将它声明为模块。当前交付暂不启用预约和 AI 顾问，也没有验证本工程的运行或接口。

`src/main/resources/application.example.yml` 是可分享的配置示例。本机 `application.yml` 保留在 `.gitignore` 中；如需单独研究此原型，复制示例后通过 `DASHSCOPE_API_KEY`、`SPRING_DATASOURCE_PASSWORD` 环境变量提供配置，避免把密钥提交到 Git。
