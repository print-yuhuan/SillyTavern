# 文档索引

本目录文档采用标准化命名：ASCII 短横线小写 + 阅读顺序编号（避免中文文件名在 git/URL/跨平台下的编码问题）。各文档中文标题与正文保持不变。

| 文件 | 中文标题 | 内容 |
|---|---|---|
| [01-implementation-plan.md](01-implementation-plan.md) | 实现方案 | 架构与技术细节来源（总体设计、关键决策、目录布局、CI、风险） |
| [02-work-plan.md](02-work-plan.md) | 工作计划 | 实施顺序与各阶段验收标准 |
| [03-progress.md](03-progress.md) | 实施进度 | 各阶段完成情况、未完成项与下一步 |
| [04-ci-and-release.md](04-ci-and-release.md) | CI 与发布 | GitHub Actions 工作流、libnode 来源、签名与发版流程 |

建议阅读顺序：先 `01` 与 `02` 了解设计与计划，再看 `03` 当前进度，构建/发版查 `04`。

关键约束速记：

- `default/config.yaml` 是随上游服务端代码发布的默认模板，不是实际生效配置。
- Android 运行时实际生效的是 `SILLYTAVERN_CONFIG_FILE`，必须通过 `--configPath` 显式传给 `server.js`。
- `--dataRoot` 指向 App 持久数据目录；`--browserLaunchEnabled=false` 禁止服务端自行拉起外部浏览器。
- CLI 参数优先级高于 `config.yaml`，因此用户可配置项应写入实际配置文件，常规启动不要再用 CLI 覆盖。
- 配置 UI 接管范围见 [01](01-implementation-plan.md) §7（四档：基础 / 高级 / 危险 / App 管理）；保存 `config.yaml` 必须保留未接管的未知字段。
