# A10 CI 质量门禁与预推送守卫集成说明

## 目标

将 A08 的安全检查能力接入 GitHub Actions，形成“提交即校验”的基础质量门禁，避免：

- `.env/.env.prod` 被误跟踪后进入远端仓库
- 明文密钥样式字符串混入提交

说明：A10 只做质量验证，不做镜像发布。

## 交付内容

## 1) 新增 CI 守卫脚本

文件：

- `scripts/a10_ci_guard.sh`

行为：

- 调用 A08 守卫脚本的轻量模式：
  - `RUN_M11_SMOKE=false`
  - `RUN_A04_SMOKE=false`
  - `RUN_M13_SMOKE=false`
- 输出最新报告路径（`docs/reports/a08_prepush_*.md`）。

## 2) CI 工作流增强

文件：

- `.github/workflows/ci.yml`

变更：

- 增加并发控制（同分支自动取消旧任务）。
- 新增 `prepush-guard` 任务，作为后续任务前置门禁。
- `backend-test`、`frontend-build` 依赖 `prepush-guard`。
- 上传 A08 报告为 artifact，便于排查失败原因。

## 验收方式

本地：

```bash
./scripts/a10_ci_guard.sh
```

CI：

- push / PR 触发 `gateway-pro-ci`
- 先通过 `prepush-guard`，再执行后续测试与构建。

## 回滚方式

如需临时关闭 A10 门禁：

1. 在 `.github/workflows/ci.yml` 中移除 `prepush-guard` 任务。
2. 删除 `needs: prepush-guard` 依赖。
