# A14 参考基线门禁与 CI 接入说明

## 目标

在 A11（单次基线）与 A13（两报告差异）基础上，增加“参考基线门禁”能力：

- 先固化一个参考基线（reference）
- 新报告必须通过参考基线门禁才允许推进

## 交付内容

## 1) 参考基线门禁脚本

文件：

- `scripts/a14_baseline_gate.sh`

模式：

1. `capture`：将指定 A11 报告固化为 reference
2. `check`：将 candidate 报告与 reference 对比并输出门禁结论

关键变量：

- `REFERENCE_REPORT`：默认 `docs/baseline/a11_reference.md`
- `SOURCE_REPORT`：capture 模式来源报告
- `CANDIDATE_REPORT`：check 模式候选报告
- `STRICT`：是否启用严格判定（默认 `false`）
- `FAIL_ON_WARN`：是否将 warn 视为 fail（默认 `false`）

输出：

- 差异报告（由 A13 生成）：`docs/reports/a14_gate_diff_*.md`
- 门禁报告：`docs/reports/a14_gate_*.md`

## 2) CI 门禁 workflow

文件：

- `.github/workflows/a14-baseline-gate.yml`

能力：

- 手动触发 + 每周定时触发
- 自动拉起 `mysql + redis + gateway`
- 自动执行 A11，再执行 A14 门禁检查
- 上传 A11/A13/A14 报告 artifact
- 失败时自动输出容器诊断日志

说明：

- 若仓库不存在 `docs/baseline/a11_reference.md`：
  - 手动触发：直接失败并提示先 capture
  - 定时触发：跳过本次门禁运行

## 使用方式

## 1) 固化 reference（首次执行）

```bash
./scripts/a11_baseline.sh
MODE=capture ./scripts/a14_baseline_gate.sh
```

## 2) 基于 reference 做门禁检查

```bash
./scripts/a11_baseline.sh
MODE=check STRICT=true ./scripts/a14_baseline_gate.sh
```

## 3) 手动触发 CI 门禁

- 打开 `gateway-pro-baseline-gate` workflow
- 可选调整 `samples/warmup/strict/fail_on_warn`

## 建议

1. 先在稳定版本执行一次 `capture` 并提交 `docs/baseline/a11_reference.md`。
2. 后续每个阶段节点都执行 `check`，形成可追溯门禁链路。
