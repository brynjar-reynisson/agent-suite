import { fetchEventSource } from '@microsoft/fetch-event-source';

const API_BASE_URL = ''; // Use relative URL to support proxying

export interface ToolCall {
  name: string;
  arguments: string;
}

export interface Message {
  role: 'user' | 'ai' | 'meta';
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
}

export interface StreamCallbacks {
  onToolCall: (tc: ToolCall) => void;
  onContent: (text: string) => void;
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
    }),
    onmessage(ev) {
      if (ev.event === 'tool_call') callbacks.onToolCall(JSON.parse(ev.data));
      if (ev.event === 'content') callbacks.onContent(ev.data);
      if (ev.event === 'done') controller.abort();
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

export const getUserConfig = async (token?: string | null): Promise<{ isAdmin: boolean }> => {
  const response = await fetch(`${API_BASE_URL}/ai/config/user`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) throw new Error('Failed to fetch user config');
  return response.json();
};

export const execTool = async (command: string, rootDirectory: string): Promise<string> => {
  const urlParams = new URLSearchParams({ command, rootDirectory });
  const response = await fetch(`${API_BASE_URL}/ai/tools?${urlParams.toString()}`);
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
