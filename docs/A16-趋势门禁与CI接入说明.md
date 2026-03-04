# A16 趋势门禁与 CI 接入说明

## 目标

在 A15（多报告趋势分析）基础上，增加可执行门禁层：

- 给出明确 gate 结论（`pass/warn/fail`）
- 将趋势门禁接入 CI，支持定时巡检与手动触发

## 交付内容

文件：

- `scripts/a16_trend_gate.sh`
- `.github/workflows/a16-trend-gate.yml`

输出：

- A15 趋势报告：`docs/reports/a15_baseline_trend_*.md`
- A16 门禁报告：`docs/reports/a16_trend_gate_*.md`

## 1) A16 门禁脚本

脚本：

- `scripts/a16_trend_gate.sh`

能力：

1. 读取多份 A11 报告（默认 `docs/reports/a11_baseline_*.md`）。
2. 调用 A15 生成趋势报告。
3. 将趋势结论映射为门禁结论并输出 A16 报告。
4. 失败时返回非 0，便于 CI 直接阻断。

关键变量：

- `REPORT_PATTERN`：默认 `docs/reports/a11_baseline_*.md`
- `MIN_REPORTS`：默认 `3`
- `STRICT`：默认 `true`
- `FAIL_ON_WARN`：默认 `false`
- `TREND_REPORT`：A15 报告输出路径
- `GATE_REPORT`：A16 报告输出路径

## 2) A16 CI workflow

文件：

- `.github/workflows/a16-trend-gate.yml`

流程：

1. 启动 `mysql + redis + gateway`
2. 连续执行多轮 A11（默认 3 轮）生成趋势样本
3. 运行 A16 趋势门禁脚本
4. 上传 A11/A15/A16 报告 artifact

触发：

- 手动 `workflow_dispatch`
- 每周定时巡检（`cron: 10 3 * * 3`，UTC）

## 使用方式

## 1) 本地执行 A16

```bash
./scripts/a16_trend_gate.sh
```

## 2) 严格门禁（warn 也阻断）

```bash
STRICT=true FAIL_ON_WARN=true ./scripts/a16_trend_gate.sh
```

## 3) 指定报告来源

```bash
REPORT_PATTERN='/tmp/a11_baseline_*.md' \
MIN_REPORTS=3 \
./scripts/a16_trend_gate.sh
```

## 建议

1. 日常可用 A16 做趋势巡检，提前发现性能漂移。
2. 关键版本节点建议启用 `FAIL_ON_WARN=true` 强化门禁。
