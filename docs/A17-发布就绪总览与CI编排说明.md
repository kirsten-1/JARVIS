# A17 发布就绪总览与 CI 编排说明

## 目标

在 A16（趋势门禁）之后，提供一个统一“发布就绪总览”层：

- 汇总 A08/A11/A13/A15/A16（可选 A14）最新报告
- 输出单一结论（`pass/warn/fail`）
- 提供可自动执行的 CI 编排

## 交付内容

文件：

- `scripts/a17_readiness_bundle.sh`
- `.github/workflows/a17-readiness-bundle.yml`

输出：

- `docs/reports/a17_readiness_bundle_<timestamp>.md`

## 1) A17 汇总脚本

脚本：

- `scripts/a17_readiness_bundle.sh`

能力：

1. 自动发现最新报告并聚合：
   - `a08_prepush_*.md`
   - `a11_baseline_*.md`
   - `a13_baseline_diff_*.md`
   - `a15_baseline_trend_*.md`
   - `a16_trend_gate_*.md`
   - 可选 `a14_gate_*.md`
2. 将各报告原始结论映射为统一状态（pass/warn/fail/missing）。
3. 支持按 required/optional 规则输出最终结论。
4. 在严格模式下（`STRICT=true`）对 `fail` 返回非 0。

关键变量：

- `REQUIRED_CHECKS`：默认 `a08,a11,a13,a15,a16`
- `OPTIONAL_CHECKS`：默认 `a14`
- `FAIL_ON_WARN`：默认 `false`
- `FAIL_ON_MISSING`：默认 `true`
- `STRICT`：默认 `true`
- `OUTPUT_REPORT`：自定义输出路径

## 2) A17 CI workflow

文件：

- `.github/workflows/a17-readiness-bundle.yml`

流程：

1. 启动 `mysql + redis + gateway`
2. 执行 A08 轻量守卫
3. 连续执行多轮 A11 样本
4. 执行 A13 + A15 + A16
5. 执行 A17 汇总并输出 artifact

触发：

- 手动 `workflow_dispatch`
- 每周定时巡检（`cron: 35 3 * * 5`，UTC）

## 使用方式

## 1) 默认执行

```bash
./scripts/a17_readiness_bundle.sh
```

## 2) 严格策略（warn 也失败）

```bash
STRICT=true FAIL_ON_WARN=true ./scripts/a17_readiness_bundle.sh
```

## 3) 调整 required/optional 组合

```bash
REQUIRED_CHECKS='a08,a11,a13,a14,a15,a16' \
OPTIONAL_CHECKS='' \
./scripts/a17_readiness_bundle.sh
```

## 建议

1. 在版本节点前，优先使用 A17 报告作为“是否继续推进”的统一依据。
2. 若团队进入发布冻结期，可临时启用 `FAIL_ON_WARN=true` 强化收敛。
