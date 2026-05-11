const API_BASE_URL = ''; // Use relative URL to support proxying

export interface ToolCall {
  name: string;
  arguments: string;
}

export interface ChatRequest {
  message: string;
  prompt?: string;
  rootDirectory?: string;
  model?: string;
  tools?: string;
}

export interface StreamCallbacks {
  onToolCall: (tc: ToolCall) => void;
  onContent: (text: string) => void;
}

export const chatStream = (params: ChatRequest, callbacks: StreamCallbacks): Promise<void> => {
  const urlParams = new URLSearchParams({
    message: params.message,
    prompt: params.prompt || '',
    rootDirectory: params.rootDirectory || '',
    model: params.model || 'deepseek-v4-pro',
    tools: params.tools || '',
  });
  const url = `${API_BASE_URL}/ai/chat?${urlParams.toString()}`;

  return new Promise((resolve, reject) => {
    const source = new EventSource(url);
    let resolved = false;

    const finish = () => {
      if (!resolved) {
        resolved = true;
        source.close();
        resolve();
      }
    };

    source.addEventListener('tool_call', (e) => {
      const data = JSON.parse(e.data);
      callbacks.onToolCall(data);
    });

    source.addEventListener('content', (e) => {
      callbacks.onContent(e.data);
    });

    source.addEventListener('done', () => {
      finish();
    });

    source.addEventListener('error', (e) => {
      if (resolved) return;
      if (e.data) {
        reject(new Error(e.data));
      } else {
        reject(new Error('Connection error'));
      }
      source.close();
    });
  });
};

export const getDirectories = async (): Promise<string[]> => {
  const response = await fetch(`${API_BASE_URL}/ai/config/directories`);
  return response.json();
};

export const execTool = async (command: string, rootDirectory: string): Promise<string> => {
  const urlParams = new URLSearchParams({ command, rootDirectory });
  const response = await fetch(`${API_BASE_URL}/ai/tools?${urlParams.toString()}`);
  return response.text();
};
