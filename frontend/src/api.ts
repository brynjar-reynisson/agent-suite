import { fetchEventSource } from '@microsoft/fetch-event-source';

const API_BASE_URL = ''; // Use relative URL to support proxying

export interface ToolCall {
  name: string;
  arguments: string;
}

export interface Message {
  role: 'user' | 'ai' | 'meta' | 'compact';
  content: string;
  toolCalls?: ToolCall[];
}

export interface ConversationSummary {
  externalId: string;
  name: string;
  createTime: string;
  initialModel: string;
  systemPrompt: string;
}

export interface ConversationDetail extends ConversationSummary {
  rootDirectory: string;
  messages: Message[];
}

export interface ChatRequest {
  message: string;
  prompt?: string;
  rootDirectory?: string;
  model?: string;
  tools?: string;
  conversationId?: string;
  requestId?: string;
}

export interface StreamCallbacks {
  onToolCall: (tc: ToolCall) => void;
  onContent: (text: string) => void;
  onError?: (message: string) => void;
}

export const chatStream = async (
  params: ChatRequest,
  callbacks: StreamCallbacks,
  token?: string | null,
): Promise<void> => {
  const controller = new AbortController();
  await fetchEventSource(`${API_BASE_URL}/ai/chat`, {
    method: 'POST',
    signal: controller.signal,
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: new URLSearchParams({
      message: params.message,
      prompt: params.prompt ?? '',
      rootDirectory: params.rootDirectory ?? '',
      model: params.model ?? 'deepseek-v4-pro',
      ...(params.tools ? { tools: params.tools } : {}),
      ...(params.conversationId ? { conversationId: params.conversationId } : {}),
      ...(params.requestId ? { requestId: params.requestId } : {}),
    }),
    onmessage(ev) {
      if (ev.event === 'tool_call') callbacks.onToolCall(JSON.parse(ev.data));
      if (ev.event === 'content') callbacks.onContent(ev.data);
      if (ev.event === 'error') callbacks.onError?.(ev.data);
      if (ev.event === 'done') controller.abort();
    },
    onclose() {
      throw new Error('Stream closed by server');
    },
    onerror(err) {
      throw err;
    },
  });
};

export const getDirectories = async (): Promise<string[]> => {
  const response = await fetch(`${API_BASE_URL}/ai/config/directories`);
  return response.json();
};

export interface UserConfig {
  isAdmin: boolean;
  grantedToolGroups: string[];
}

export const getUserConfig = async (token?: string | null): Promise<UserConfig> => {
  const response = await fetch(`${API_BASE_URL}/ai/config/user`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) throw new Error('Failed to fetch user config');
  return response.json();
};

export const getMcpTools = async (token?: string | null, rootDirectory = ''): Promise<string[]> => {
  const urlParams = new URLSearchParams({ rootDirectory });
  const response = await fetch(`${API_BASE_URL}/ai/config/mcp-tools?${urlParams.toString()}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) return [];
  return response.json();
};

export const execTool = async (
  command: string,
  rootDirectory: string,
  token?: string | null,
): Promise<string> => {
  const urlParams = new URLSearchParams({ command, rootDirectory });
  const response = await fetch(`${API_BASE_URL}/ai/tools?${urlParams.toString()}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  return response.text();
};

export const getConversations = async (token?: string | null): Promise<ConversationSummary[]> => {
  const response = await fetch(`${API_BASE_URL}/ai/conversations`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) throw new Error('Failed to fetch conversations');
  return response.json();
};

export const getConversationDetail = async (
  externalId: string,
  token?: string | null,
): Promise<ConversationDetail> => {
  const response = await fetch(
    `${API_BASE_URL}/ai/conversations/${encodeURIComponent(externalId)}`,
    { headers: token ? { Authorization: `Bearer ${token}` } : {} },
  );
  if (!response.ok) throw new Error('Conversation not found');
  return response.json();
};

export const compactConversation = async (
  conversationId: string,
  token?: string | null,
): Promise<{ summary: string }> => {
  const res = await fetch(
    `${API_BASE_URL}/ai/conversations/${encodeURIComponent(conversationId)}/compact`,
    { method: 'POST', headers: token ? { Authorization: `Bearer ${token}` } : {} },
  );
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error((body as { error?: string }).error ?? `Compact failed (${res.status})`);
  }
  return res.json();
};
