import { useRef, useState } from "react";
import {
  Button,
  Card,
  Col,
  ConfigProvider,
  Flex,
  Input,
  Layout,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Typography
} from "antd";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

const { Header, Content } = Layout;
const { TextArea } = Input;
const { Title, Paragraph, Text } = Typography;

type Role = "USER" | "ADMIN";

type ApiEnvelope<T> = {
  code: number;
  message: string;
  data: T;
  traceId: string | null;
  timestamp: number;
};

type DevTokenData = {
  token: string;
  userId: number;
  role: Role;
  issuedAt: string;
};

type ConversationData = {
  id: number;
  userId: number;
  workspaceId: number;
  title: string;
  status: string;
  createdAt: string;
  updatedAt: string;
};

type SyncChatData = {
  conversationId: number;
  userMessageId: number;
  assistantMessageId: number;
  assistantContent: string;
  model: string;
  finishReason: string;
};

type DailyCostPoint = {
  date: string;
  requests: number;
  totalTokens: number;
  costUsd: number;
  actualRequests: number;
  estimatedRequests: number;
};

type MetricsOverviewData = {
  workspaceId: number;
  dateFrom: string;
  dateTo: string;
  totalRequests: number;
  successRequests: number;
  failedRequests: number;
  successRate: number;
  fallbackHits: number;
  fallbackRate: number;
  ttftP50Ms: number | null;
  ttftP90Ms: number | null;
  ttftP99Ms: number | null;
  costTrend: DailyCostPoint[];
};

type ProviderMetricsData = {
  provider: string;
  totalRequests: number;
  successRequests: number;
  failedRequests: number;
  successRate: number;
  fallbackHits: number;
  fallbackRate: number;
  ttftP50Ms: number | null;
  ttftP90Ms: number | null;
  ttftP99Ms: number | null;
};

type StreamPhase = "idle" | "running" | "completed" | "error" | "stopped" | "closed";

async function requestApi<T>(
  baseUrl: string,
  path: string,
  method: string,
  payload: unknown,
  token?: string
): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json"
  };

  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers,
    body: payload == null ? undefined : JSON.stringify(payload)
  });

  const text = await response.text();
  let parsed: ApiEnvelope<T> | null = null;

  if (text) {
    parsed = JSON.parse(text) as ApiEnvelope<T>;
  }

  if (!response.ok) {
    throw new Error(parsed?.message || `HTTP ${response.status}`);
  }

  if (!parsed) {
    throw new Error("empty response");
  }

  if (parsed.code !== 0) {
    throw new Error(`${parsed.message} (code=${parsed.code})`);
  }

  return parsed.data;
}

async function requestApiGet<T>(
  baseUrl: string,
  path: string,
  query: Record<string, string | number | undefined>,
  token?: string
): Promise<T> {
  const headers: Record<string, string> = {};
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const queryString = new URLSearchParams(
    Object.entries(query)
      .filter(([, value]) => value !== undefined && value !== null && `${value}`.trim() !== "")
      .map(([key, value]) => [key, String(value)])
  ).toString();

  const response = await fetch(`${baseUrl}${path}${queryString ? `?${queryString}` : ""}`, {
    method: "GET",
    headers,
    cache: "no-store"
  });

  const text = await response.text();
  let parsed: ApiEnvelope<T> | null = null;

  if (text) {
    parsed = JSON.parse(text) as ApiEnvelope<T>;
  }

  if (!response.ok) {
    throw new Error(parsed?.message || `HTTP ${response.status}`);
  }

  if (!parsed) {
    throw new Error("empty response");
  }

  if (parsed.code !== 0) {
    throw new Error(`${parsed.message} (code=${parsed.code})`);
  }

  return parsed.data;
}

async function streamSse(
  baseUrl: string,
  path: string,
  payload: unknown,
  token: string,
  onEvent: (event: string, data: string) => void,
  signal?: AbortSignal
): Promise<void> {
  const response = await fetch(`${baseUrl}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify(payload),
    signal
  });

  if (!response.ok || !response.body) {
    const message = await response.text();
    throw new Error(message || `stream http status ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  const emitBlock = (block: string) => {
    if (!block || !block.trim()) {
      return;
    }

    let event = "message";
    const dataLines: string[] = [];

    for (const rawLine of block.split("\n")) {
      const line = rawLine.replace(/\r$/, "");
      if (line.startsWith("event:")) {
        event = line.slice(6).trim();
      }
      if (line.startsWith("data:")) {
        // Keep original whitespace/newlines for markdown fidelity.
        dataLines.push(line.slice(5).replace(/^ /, ""));
      }
    }

    if (dataLines.length > 0) {
      onEvent(event, dataLines.join("\n"));
    }
  };

  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      emitBlock(buffer);
      break;
    }

    buffer += decoder.decode(value, { stream: true });
    const blocks = buffer.split("\n\n");
    buffer = blocks.pop() || "";
    blocks.forEach(emitBlock);
  }
}

function resolveStreamErrorMessage(raw: string): string {
  try {
    const parsed = JSON.parse(raw) as { message?: string };
    if (parsed.message) {
      return parsed.message;
    }
  } catch (_ignored) {
    // ignore json parse error and fallback to raw text
  }
  return raw;
}

function formatMarkdownForRender(raw: string): string {
  let out = raw.replace(/\r\n/g, "\n");
  out = out
    // Heading markers glued with previous text: "...分析：###1.xxx"
    .replace(/([^\n])(#{1,6})(?=\S)/g, "$1\n\n$2")
    // Ensure heading marker has a space: "###1.xxx" -> "### 1.xxx"
    .replace(/(^|\n)(#{1,6})(\S)/g, "$1$2 $3")
    // Split list items that are glued with Chinese text.
    .replace(/([。；：])\s*-/g, "$1\n-")
    .replace(/([\u4e00-\u9fa5])-(?=[\u4e00-\u9fa5A-Za-z0-9])/g, "$1\n-")
    // Normalize list prefix: "-项" -> "- 项"
    .replace(/(^|\n)-(?=\S)/g, "$1- ");

  out = normalizeTabularRows(out);

  if (out.includes("|")) {
    // If a table starts right after plain text, force a new paragraph.
    out = out.replace(/([^\n])(\|[^|\n]+\|[^|\n]+\|[^|\n]+\|)/g, "$1\n\n$2");

    // Split compact rows: "|...| |...|" or "|...||...|"
    out = out.replace(/\|\s+\|/g, "|\n|");
    out = out.replace(/\|\|/g, "|\n|");

    // Ensure table rows are each on separate lines.
    for (let i = 0; i < 12; i += 1) {
      const prev = out;
      out = out
        .replace(/(\|[^\n]*\|)\s+(\|[-:\s]{3,}\|)/g, "$1\n$2")
        .replace(/(\|[-:\s]{3,}\|)\s+(\|[^\n]*\|)/g, "$1\n$2")
        .replace(/(\|[^\n]*\|)\s+(\|[^\n]*\|)/g, "$1\n$2");
      if (out === prev) {
        break;
      }
    }

    const normalizedLines = out.split("\n").map((line) => {
      const trimmed = line.trim();
      if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
        return trimmed;
      }
      return line;
    });
    out = normalizedLines.join("\n");

    // Keep blank lines around table blocks so markdown parser enters table mode.
    out = out
      .replace(/([^\n])\n(\|[^\n]*\|)/g, "$1\n\n$2")
      .replace(/(\|[^\n]*\|)\n([^\|\n])/g, "$1\n\n$2");
  }

  out = normalizePipeTableBlocks(out);
  out = removeStrayHorizontalRules(out);
  out = out.replace(/\n{3,}/g, "\n\n");
  return out.trim();
}

function normalizeTabularRows(text: string): string {
  const lines = text.split("\n");
  const normalized = lines.map((line) => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("|")) {
      return line;
    }

    const cells = trimmed
      .split(/\t+| {2,}/)
      .map((cell) => cell.trim())
      .filter(Boolean);

    if (cells.length >= 3) {
      return `|${cells.join("|")}|`;
    }

    return line;
  });

  return normalized.join("\n");
}

function isPipeRow(line: string): boolean {
  const trimmed = line.trim();
  return trimmed.startsWith("|") && trimmed.endsWith("|");
}

function buildSeparator(count: number): string {
  if (count <= 0) {
    return "|---|";
  }
  return `|${Array(count).fill("---").join("|")}|`;
}

function parsePipeCells(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, "")
    .replace(/\|$/, "")
    .split("|")
    .map((cell) => cell.trim());
}

function isDashOnlyPipeRow(cells: string[]): boolean {
  if (cells.length === 0) {
    return false;
  }
  return cells.every((cell) => /^:?-{3,}:?$/.test(cell));
}

function normalizePipeTableBlocks(text: string): string {
  const lines = text.split("\n");
  const out: string[] = [];

  for (let i = 0; i < lines.length; i += 1) {
    if (!isPipeRow(lines[i])) {
      out.push(lines[i]);
      continue;
    }

    const block: string[] = [];
    while (i < lines.length && isPipeRow(lines[i])) {
      block.push(lines[i].trim());
      i += 1;
    }
    i -= 1;

    if (block.length === 0) {
      continue;
    }

    const rows = block
      .map(parsePipeCells)
      .filter((cells) => cells.length >= 2);

    if (rows.length < 2) {
      out.push(...block);
      continue;
    }

    const maxCols = rows.reduce((max, row) => Math.max(max, row.length), 0);
    const normalizeRow = (row: string[]) => {
      if (row.length === maxCols) {
        return row;
      }
      return [...row, ...Array(maxCols - row.length).fill("")];
    };

    const header = normalizeRow(rows[0]);
    const body = rows
      .slice(1)
      .map(normalizeRow)
      .filter((row) => !isDashOnlyPipeRow(row));

    const normalizedBlock = [
      `|${header.join("|")}|`,
      buildSeparator(maxCols),
      ...body.map((row) => `|${row.join("|")}|`)
    ];

    if (out.length > 0 && out[out.length - 1].trim() !== "") {
      out.push("");
    }
    out.push(...normalizedBlock);
    if (i + 1 < lines.length && lines[i + 1].trim() !== "") {
      out.push("");
    }
  }

  return out.join("\n");
}

function removeStrayHorizontalRules(text: string): string {
  const lines = text.split("\n");
  const cleaned = lines.filter((line) => {
    const trimmed = line.trim();
    if (!trimmed) {
      return true;
    }

    const looksLikeHr = /^[-*_]{3,}$/.test(trimmed);
    const looksLikeDashColumns = /^[-:]{3,}(?:\s+[-:]{3,})+$/.test(trimmed);
    return !looksLikeHr && !looksLikeDashColumns;
  });

  return cleaned.join("\n");
}

export default function App() {
  const [baseUrl, setBaseUrl] = useState("http://localhost:8080");
  const [userId, setUserId] = useState("1001");
  const [role, setRole] = useState<Role>("USER");
  const [token, setToken] = useState("");

  const [provider, setProvider] = useState("glm");
  const [model, setModel] = useState("glm-4.6v-flashx");

  const [conversationTitle, setConversationTitle] = useState("GLM 联调会话");
  const [conversationId, setConversationId] = useState("");
  const [workspaceId, setWorkspaceId] = useState("");
  const [message, setMessage] = useState("你好，请你做一个简短自我介绍。");
  const [metricsFrom, setMetricsFrom] = useState("");
  const [metricsTo, setMetricsTo] = useState("");

  const [syncResult, setSyncResult] = useState("");
  const [reasoningResult, setReasoningResult] = useState("");
  const [streamResult, setStreamResult] = useState("");
  const [metaResult, setMetaResult] = useState("");
  const [overviewResult, setOverviewResult] = useState<MetricsOverviewData | null>(null);
  const [providerMetricsResult, setProviderMetricsResult] = useState<ProviderMetricsData[]>([]);
  const [streamPhase, setStreamPhase] = useState<StreamPhase>("idle");
  const [streamHint, setStreamHint] = useState("未开始");
  const [status, setStatus] = useState("Ready");
  const [statusLevel, setStatusLevel] = useState<"success" | "warning" | "error" | "info">("info");

  const abortRef = useRef<AbortController | null>(null);
  const stopRequestedRef = useRef(false);

  const canRequest = token.trim().length > 10;

  function applyStatus(level: "success" | "warning" | "error" | "info", text: string) {
    setStatusLevel(level);
    setStatus(text);
  }

  async function issueToken() {
    applyStatus("info", "正在签发 dev token...");
    try {
      const data = await requestApi<DevTokenData>(
        baseUrl,
        "/api/v1/auth/dev-token",
        "POST",
        {
          userId: Number(userId),
          role
        }
      );
      setToken(data.token);
      applyStatus("success", `token ready: userId=${data.userId}, role=${data.role}`);
    } catch (error) {
      applyStatus("error", `token failed: ${(error as Error).message}`);
    }
  }

  async function createConversation() {
    if (!canRequest) {
      applyStatus("warning", "请先获取 token");
      return;
    }

    applyStatus("info", "正在创建会话...");
    try {
      if (workspaceId.trim() && !/^\d+$/.test(workspaceId.trim())) {
        applyStatus("warning", "workspaceId 必须是数字");
        return;
      }
      const data = await requestApi<ConversationData>(
        baseUrl,
        "/api/v1/conversations",
        "POST",
        {
          userId: Number(userId),
          workspaceId: workspaceId.trim() ? Number(workspaceId.trim()) : undefined,
          title: conversationTitle
        },
        token
      );
      setConversationId(String(data.id));
      setWorkspaceId(String(data.workspaceId));
      applyStatus("success", `会话创建成功: conversationId=${data.id}, workspaceId=${data.workspaceId}`);
    } catch (error) {
      applyStatus("error", `创建会话失败: ${(error as Error).message}`);
    }
  }

  function resolveMetricsWorkspaceId(): number | null {
    const text = workspaceId.trim();
    if (!text) {
      applyStatus("warning", "请先填写 workspaceId（可先创建会话自动回填）");
      return null;
    }
    if (!/^\d+$/.test(text)) {
      applyStatus("warning", "workspaceId 必须是数字");
      return null;
    }
    return Number(text);
  }

  async function loadMetricsOverview() {
    if (!canRequest) {
      applyStatus("warning", "请先获取 token");
      return;
    }
    const targetWorkspaceId = resolveMetricsWorkspaceId();
    if (targetWorkspaceId == null) {
      return;
    }

    applyStatus("info", "正在查询运营总览...");
    try {
      const data = await requestApiGet<MetricsOverviewData>(
        baseUrl,
        "/api/v1/metrics/overview",
        {
          workspaceId: targetWorkspaceId,
          userId: Number(userId),
          from: metricsFrom || undefined,
          to: metricsTo || undefined,
          _ts: Date.now()
        },
        token
      );
      setOverviewResult(data);
      applyStatus("success", `总览查询成功: total=${data.totalRequests}, successRate=${(data.successRate * 100).toFixed(2)}%`);
    } catch (error) {
      applyStatus("error", `总览查询失败: ${(error as Error).message}`);
    }
  }

  async function loadProviderMetrics() {
    if (!canRequest) {
      applyStatus("warning", "请先获取 token");
      return;
    }
    const targetWorkspaceId = resolveMetricsWorkspaceId();
    if (targetWorkspaceId == null) {
      return;
    }

    applyStatus("info", "正在查询 Provider 指标...");
    try {
      const data = await requestApiGet<ProviderMetricsData[]>(
        baseUrl,
        "/api/v1/metrics/providers",
        {
          workspaceId: targetWorkspaceId,
          userId: Number(userId),
          from: metricsFrom || undefined,
          to: metricsTo || undefined,
          _ts: Date.now()
        },
        token
      );
      setProviderMetricsResult(data);
      applyStatus("success", `Provider 指标查询成功: providers=${data.length}`);
    } catch (error) {
      applyStatus("error", `Provider 指标查询失败: ${(error as Error).message}`);
    }
  }

  async function sendSyncChat() {
    if (!canRequest) {
      applyStatus("warning", "请先获取 token");
      return;
    }
    if (!conversationId) {
      applyStatus("warning", "请先创建会话或填写 conversationId");
      return;
    }
    if (!/^\d+$/.test(conversationId.trim())) {
      applyStatus("warning", "conversationId 必须是数字（请填会话ID，不是token）");
      return;
    }

    applyStatus("info", "正在发送同步聊天...");
    setSyncResult("");

    try {
      const data = await requestApi<SyncChatData>(
        baseUrl,
        `/api/v1/conversations/${conversationId}/chat`,
        "POST",
        {
          userId: Number(userId),
          message,
          provider,
          model
        },
        token
      );
      setSyncResult(data.assistantContent);
      applyStatus("success", `同步聊天成功: model=${data.model}`);
    } catch (error) {
      applyStatus("error", `同步聊天失败: ${(error as Error).message}`);
    }
  }

  async function sendStreamChat() {
    if (!canRequest) {
      applyStatus("warning", "请先获取 token");
      return;
    }
    if (!conversationId) {
      applyStatus("warning", "请先创建会话或填写 conversationId");
      return;
    }
    if (!/^\d+$/.test(conversationId.trim())) {
      applyStatus("warning", "conversationId 必须是数字（请填会话ID，不是token）");
      return;
    }

    applyStatus("info", "正在发送流式聊天...");
    setStreamResult("");
    setReasoningResult("");
    setMetaResult("");
    setStreamPhase("running");
    setStreamHint("流式输出中...");

    abortRef.current?.abort();
    abortRef.current = new AbortController();
    stopRequestedRef.current = false;
    let hasStreamError = false;
    let hasDoneEvent = false;
    let outputChars = 0;
    const startedAt = Date.now();

    try {
      await streamSse(
        baseUrl,
        `/api/v1/conversations/${conversationId}/chat/stream`,
        {
          userId: Number(userId),
          message,
          provider,
          model
        },
        token,
        (event, data) => {
          if (event === "meta") {
            setMetaResult(data);
            return;
          }
          if (event === "delta") {
            outputChars += data.length;
            setStreamResult((prev) => prev + data);
            return;
          }
          if (event === "reasoning") {
            setReasoningResult((prev) => prev + data);
            return;
          }
          if (event === "error") {
            hasStreamError = true;
            setStreamPhase("error");
            setStreamHint("流式异常中断");
            applyStatus("error", `流式下游错误: ${resolveStreamErrorMessage(data)}`);
            return;
          }
          if (event === "done") {
            hasDoneEvent = true;
            if (!hasStreamError) {
              const elapsedMs = Date.now() - startedAt;
              setStreamPhase("completed");
              setStreamHint(`完成，耗时 ${elapsedMs}ms，输出 ${outputChars} 字符`);
              applyStatus("success", `流式聊天完成（耗时 ${elapsedMs}ms）`);
            }
          }
        },
        abortRef.current?.signal
      );
      if (stopRequestedRef.current) {
        return;
      }
      if (!hasStreamError) {
        if (hasDoneEvent) {
          const elapsedMs = Date.now() - startedAt;
          setStreamPhase("completed");
          setStreamHint(`完成，耗时 ${elapsedMs}ms，输出 ${outputChars} 字符`);
          applyStatus("success", `流式聊天完成（耗时 ${elapsedMs}ms）`);
        } else {
          const elapsedMs = Date.now() - startedAt;
          setStreamPhase("closed");
          setStreamHint(`连接结束但未收到 done（耗时 ${elapsedMs}ms）`);
          applyStatus("warning", "流式连接结束，但未收到 done 事件");
        }
      }
    } catch (error) {
      if (stopRequestedRef.current) {
        return;
      }
      if (error instanceof DOMException && error.name === "AbortError") {
        setStreamPhase("stopped");
        setStreamHint("已手动停止");
        applyStatus("warning", "已手动停止流式请求");
        return;
      }
      setStreamPhase("error");
      setStreamHint("流式请求失败");
      applyStatus("error", `流式聊天失败: ${(error as Error).message}`);
    }
  }

  function stopStream() {
    stopRequestedRef.current = true;
    abortRef.current?.abort();
    setStreamPhase("stopped");
    setStreamHint("已手动停止");
    applyStatus("warning", "已手动停止流式请求");
  }

  return (
    <ConfigProvider
      theme={{
        token: {
          colorPrimary: "#0d7ac2",
          borderRadius: 12,
          fontFamily: '"Space Grotesk", "Noto Sans SC", sans-serif'
        }
      }}
    >
      <Layout style={{ minHeight: "100vh", background: "transparent" }}>
        <Header style={{ background: "transparent", height: "auto", padding: "24px 24px 8px" }}>
          <Title level={2} style={{ margin: 0 }}>
            Jarvis Gateway Console
          </Title>
          <Paragraph style={{ marginBottom: 0 }}>
            Ant Design 前端联调台，覆盖 JWT、会话、同步/流式调用与 M9 运营指标查询。
          </Paragraph>
        </Header>

        <Content style={{ padding: "8px 24px 36px" }}>
          <Space direction="vertical" size={16} style={{ width: "100%" }}>
            <AlertBanner level={statusLevel} text={status} />

            <Row gutter={[16, 16]}>
              <Col xs={24} lg={12}>
                <Card title="连接与认证">
                  <Space direction="vertical" size={12} style={{ width: "100%" }}>
                    <Input addonBefore="Base URL" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
                    <Input addonBefore="User ID" value={userId} onChange={(e) => setUserId(e.target.value)} />
                    <Flex gap={8} align="center">
                      <Text>Role</Text>
                      <Select<Role>
                        value={role}
                        style={{ width: 160 }}
                        options={[
                          { value: "USER", label: "USER" },
                          { value: "ADMIN", label: "ADMIN" }
                        ]}
                        onChange={(value) => setRole(value)}
                      />
                    </Flex>
                    <Button type="primary" onClick={issueToken}>
                      获取 Dev Token
                    </Button>
                    <TextArea value={token} rows={4} readOnly placeholder="签发后会显示 JWT" />
                  </Space>
                </Card>
              </Col>

              <Col xs={24} lg={12}>
                <Card title="模型与会话">
                  <Space direction="vertical" size={12} style={{ width: "100%" }}>
                    <Input addonBefore="Provider" value={provider} onChange={(e) => setProvider(e.target.value)} />
                    <Input addonBefore="Model" value={model} onChange={(e) => setModel(e.target.value)} />
                    <Input
                      addonBefore="新会话标题"
                      value={conversationTitle}
                      onChange={(e) => setConversationTitle(e.target.value)}
                    />
                    <Input
                      addonBefore="Workspace ID"
                      value={workspaceId}
                      onChange={(e) => setWorkspaceId(e.target.value)}
                      placeholder="留空自动使用个人空间"
                    />
                    <Button onClick={createConversation}>创建会话</Button>
                    <Input
                      addonBefore="Conversation ID"
                      value={conversationId}
                      onChange={(e) => setConversationId(e.target.value)}
                    />
                    <Tag color={canRequest ? "success" : "warning"}>
                      {canRequest ? "已持有可用 Token" : "未认证"}
                    </Tag>
                  </Space>
                </Card>
              </Col>
            </Row>

            <Card title="聊天请求">
              <Space direction="vertical" size={12} style={{ width: "100%" }}>
                <TextArea value={message} rows={4} onChange={(e) => setMessage(e.target.value)} />
                <Space wrap>
                  <Button type="primary" onClick={sendSyncChat}>
                    同步聊天
                  </Button>
                  <Button type="primary" ghost onClick={sendStreamChat}>
                    流式聊天
                  </Button>
                  <Button danger onClick={stopStream}>
                    停止流式
                  </Button>
                </Space>
              </Space>
            </Card>

            <Card title="运营指标（M9.3）">
              <Space direction="vertical" size={12} style={{ width: "100%" }}>
                <Row gutter={[12, 12]}>
                  <Col xs={24} md={8}>
                    <Input
                      addonBefore="From"
                      value={metricsFrom}
                      onChange={(e) => setMetricsFrom(e.target.value)}
                      placeholder="2026-03-01"
                    />
                  </Col>
                  <Col xs={24} md={8}>
                    <Input
                      addonBefore="To"
                      value={metricsTo}
                      onChange={(e) => setMetricsTo(e.target.value)}
                      placeholder="2026-03-07"
                    />
                  </Col>
                  <Col xs={24} md={8}>
                    <Space wrap>
                      <Button onClick={loadMetricsOverview}>查询总览</Button>
                      <Button onClick={loadProviderMetrics}>查询 Provider</Button>
                    </Space>
                  </Col>
                </Row>

                {overviewResult ? (
                  <Space wrap>
                    <Tag color="blue">workspace={overviewResult.workspaceId}</Tag>
                    <Tag color="green">total={overviewResult.totalRequests}</Tag>
                    <Tag color="green">success={overviewResult.successRequests}</Tag>
                    <Tag color="red">failed={overviewResult.failedRequests}</Tag>
                    <Tag color="gold">fallback={overviewResult.fallbackHits}</Tag>
                    <Tag color="cyan">TTFT P50={formatMetricNumber(overviewResult.ttftP50Ms)}ms</Tag>
                    <Tag color="cyan">P90={formatMetricNumber(overviewResult.ttftP90Ms)}ms</Tag>
                    <Tag color="cyan">P99={formatMetricNumber(overviewResult.ttftP99Ms)}ms</Tag>
                  </Space>
                ) : (
                  <Text type="secondary">尚未加载总览数据</Text>
                )}

                <Table<DailyCostPoint>
                  rowKey={(row) => row.date}
                  size="small"
                  pagination={false}
                  dataSource={overviewResult?.costTrend ?? []}
                  locale={{ emptyText: "暂无成本趋势数据" }}
                  columns={[
                    { title: "日期", dataIndex: "date" },
                    { title: "请求数", dataIndex: "requests" },
                    { title: "Token", dataIndex: "totalTokens" },
                    {
                      title: "成本(USD)",
                      dataIndex: "costUsd",
                      render: (value: number) => value.toFixed(6)
                    },
                    { title: "actual", dataIndex: "actualRequests" },
                    { title: "estimated", dataIndex: "estimatedRequests" }
                  ]}
                />

                <Table<ProviderMetricsData>
                  rowKey={(row) => row.provider}
                  size="small"
                  pagination={false}
                  dataSource={providerMetricsResult}
                  locale={{ emptyText: "暂无 Provider 指标数据" }}
                  columns={[
                    { title: "Provider", dataIndex: "provider" },
                    { title: "总请求", dataIndex: "totalRequests" },
                    { title: "成功", dataIndex: "successRequests" },
                    { title: "失败", dataIndex: "failedRequests" },
                    {
                      title: "成功率",
                      dataIndex: "successRate",
                      render: (value: number) => `${(value * 100).toFixed(2)}%`
                    },
                    { title: "fallback", dataIndex: "fallbackHits" },
                    {
                      title: "P50(ms)",
                      dataIndex: "ttftP50Ms",
                      render: (value: number | null) => formatMetricNumber(value)
                    },
                    {
                      title: "P90(ms)",
                      dataIndex: "ttftP90Ms",
                      render: (value: number | null) => formatMetricNumber(value)
                    },
                    {
                      title: "P99(ms)",
                      dataIndex: "ttftP99Ms",
                      render: (value: number | null) => formatMetricNumber(value)
                    }
                  ]}
                />
              </Space>
            </Card>

            <Row gutter={[16, 16]}>
              <Col xs={24} lg={12}>
                <Card title="同步结果">
                  <pre>{syncResult || "暂无"}</pre>
                </Card>
              </Col>
              <Col xs={24} lg={12}>
                <Card
                  title="流式结果"
                  extra={
                    <Space size={8}>
                      <Tag color={streamPhaseColor(streamPhase)}>{streamPhaseText(streamPhase)}</Tag>
                      <Text type="secondary">{streamHint}</Text>
                    </Space>
                  }
                >
                  <Space direction="vertical" size={10} style={{ width: "100%" }}>
                    <Text strong>思考过程（灰色实时）</Text>
                    <pre style={{ color: "#8c8c8c" }}>{reasoningResult || "暂无"}</pre>
                    <Text strong>最终回答</Text>
                    {streamResult ? (
                      <div className="markdown-body">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {formatMarkdownForRender(streamResult)}
                        </ReactMarkdown>
                      </div>
                    ) : (
                      <pre>暂无</pre>
                    )}
                    <Text strong>Meta</Text>
                    <pre>{metaResult || "暂无"}</pre>
                  </Space>
                </Card>
              </Col>
            </Row>
          </Space>
        </Content>
      </Layout>
    </ConfigProvider>
  );
}

function formatMetricNumber(value: number | null): string {
  if (value == null || Number.isNaN(value)) {
    return "-";
  }
  return value.toFixed(1);
}

function streamPhaseText(phase: StreamPhase): string {
  switch (phase) {
    case "running":
      return "进行中";
    case "completed":
      return "已完成";
    case "error":
      return "异常";
    case "stopped":
      return "已停止";
    case "closed":
      return "连接结束";
    default:
      return "未开始";
  }
}

function streamPhaseColor(phase: StreamPhase): string {
  switch (phase) {
    case "running":
      return "processing";
    case "completed":
      return "success";
    case "error":
      return "error";
    case "stopped":
      return "warning";
    case "closed":
      return "gold";
    default:
      return "default";
  }
}

function AlertBanner(props: { level: "success" | "warning" | "error" | "info"; text: string }) {
  const colorMap: Record<typeof props.level, string> = {
    success: "#1f8b4c",
    warning: "#a05a0a",
    error: "#b42318",
    info: "#0d7ac2"
  };

  return (
    <Card
      bodyStyle={{ padding: "10px 14px" }}
      style={{ border: `1px solid ${colorMap[props.level]}33`, background: `${colorMap[props.level]}12` }}
    >
      <Text style={{ color: colorMap[props.level] }}>{props.text}</Text>
    </Card>
  );
}
