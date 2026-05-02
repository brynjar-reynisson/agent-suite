import axios from 'axios';

const API_BASE_URL = ''; // Use relative URL to support proxying

export interface ChatRequest {
  message: string;
  prompt?: string;
  rootDirectory?: string;
  model?: string;
}

export const chat = async (params: ChatRequest): Promise<string> => {
  const response = await axios.get(`${API_BASE_URL}/ai/chat`, {
    params: {
      message: params.message,
      prompt: params.prompt || '',
      rootDirectory: params.rootDirectory || '',
      model: params.model || 'deepseek-v4-pro',
    },
  });
  return response.data;
};

export const getDirectories = async (): Promise<string[]> => {
  const response = await axios.get(`${API_BASE_URL}/ai/config/directories`);
  return response.data;
};
