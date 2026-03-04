# A12 CI 夜间基线回归说明

## 目标

将 A11 性能基线接入自动化流水线，实现：

- 每日定时回归（夜间基线）
- 按需手动触发（参数可调）
- 报告自动归档（artifact）

说明：A12 只做验证与报告，不做 release。

## 交付内容

文件：

- `.github/workflows/a12-baseline.yml`

流程：

1. 拉取代码并准备 `.env.prod`
2. 本地构建 gateway 镜像（`jarvis-gateway-pro:a12-ci`）
3. 拉起 `mysql + redis + gateway`
4. 执行 `scripts/a11_baseline.sh`
5. 上传报告 `docs/reports/a11_baseline_*.md`
6. 失败时输出诊断日志
7. 收尾下线 stack

## 触发方式

## 1) 定时触发

- `cron: 20 2 * * *`（UTC）

## 2) 手动触发（workflow_dispatch）

可选参数：

- `samples`：默认 `20`
- `warmup`：默认 `3`
- `strict`：默认 `false`
- `run_agent_baseline`：默认 `false`

## 使用建议

1. 日常夜间任务建议 `run_agent_baseline=false`，降低波动。
2. 版本节点前可手动触发并开启 `strict=true`。
3. 若后续需要长期趋势图，可将报告摘要写入时序存储或 issue comment。

## 回滚方式

如需临时关闭 A12：

1. 在 GitHub Actions 中禁用 `gateway-pro-baseline` workflow。
2. 或移除 `.github/workflows/a12-baseline.yml`。
