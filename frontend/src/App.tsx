import { useEffect, useState } from 'react';
import { chat, getDirectories } from './api';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface Message {
  role: 'user' | 'ai';
  content: string;
}

const MODELS = [
  'deepseek-v4-pro',
  'sonnet-4.6',
  'opus-4.7',
  'haiku-4.5',
  'gemini-2.5-pro',
  'gemini-2.5-flash',
];

function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [prompt, setPrompt] = useState('');
  const [rootDirectory, setRootDirectory] = useState('');
  const [allowedDirectories, setAllowedDirectories] = useState<string[]>([]);
  const [model, setModel] = useState('deepseek-v4-pro');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const fetchConfig = async () => {
      try {
        const dirs = await getDirectories();
        // Sort alphabetically, but keep empty string at the top
        const sortedDirs = [...dirs].sort((a, b) => {
          if (a === '') return -1;
          if (b === '') return 1;
          return a.localeCompare(b);
        });
        setAllowedDirectories(sortedDirs);
        // Default to empty string (None)
        setRootDirectory('');
      } catch (error) {
        console.error('Failed to fetch allowed directories:', error);
      }
    };
    fetchConfig();
  }, []);

  const handleSend = async () => {
    if (!input.trim() || loading) return;

    const userMessage: Message = { role: 'user', content: input };
    setMessages((prev) => [...prev, userMessage]);
    setInput('');
    setLoading(true);

    try {
      const result = await chat({
        message: input,
        prompt: prompt,
        rootDirectory: rootDirectory,
        model: model,
      });

      const aiMessage: Message = { role: 'ai', content: result };
      setMessages((prev) => [...prev, aiMessage]);
    } catch (error: any) {
      const errorMessage: Message = { 
        role: 'ai', 
        content: `Error: ${error.response?.data || error.message}` 
      };
      setMessages((prev) => [...prev, errorMessage]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex flex-col h-screen bg-gray-100 font-sans">
      {/* Header */}
      <header className="bg-white shadow-sm p-4 flex justify-between items-center">
        <h1 className="text-xl font-bold text-gray-800">AgentSuite Chat</h1>
        <div className="flex gap-4 items-center">
          <select 
            value={model} 
            onChange={(e) => setModel(e.target.value)}
            className="border rounded px-2 py-1 text-sm bg-gray-50"
          >
            {MODELS.map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        </div>
      </header>

      {/* Main Chat Area */}
      <main className="flex-1 overflow-y-auto p-4 flex flex-col gap-4">
        {messages.length === 0 && (
          <div className="flex-1 flex items-center justify-center text-gray-400">
            Start a conversation...
          </div>
        )}
        {messages.map((msg, i) => (
          <div 
            key={i} 
            className={`max-w-[80%] p-3 rounded-lg shadow-sm ${
              msg.role === 'user' 
                ? 'self-end bg-blue-600 text-white' 
                : 'self-start bg-white text-gray-800'
            }`}
          >
            <div className={`prose max-w-none ${msg.role === 'user' ? 'prose-invert' : ''}`}>
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {msg.content}
              </ReactMarkdown>
            </div>
          </div>
        ))}
        {loading && (
          <div className="self-start bg-white p-3 rounded-lg shadow-sm text-gray-400 animate-pulse">
            Thinking...
          </div>
        )}
      </main>

      {/* Settings Panel */}
      <div className="bg-white border-t p-4 flex gap-4 flex-wrap">
        <div className="flex-1 min-w-[300px]">
          <label className="block text-xs font-semibold text-gray-500 mb-1">SYSTEM PROMPT</label>
          <input 
            type="text" 
            value={prompt} 
            onChange={(e) => setPrompt(e.target.value)}
            placeholder="System instructions..."
            className="w-full border rounded px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 outline-none"
          />
        </div>
        <div className="flex-1 min-w-[300px]">
          <label className="block text-xs font-semibold text-gray-500 mb-1">ROOT DIRECTORY</label>
          <select 
            value={rootDirectory} 
            onChange={(e) => setRootDirectory(e.target.value)}
            className="w-full border rounded px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 outline-none bg-white"
          >
            {allowedDirectories.map((dir) => (
              <option key={dir} value={dir}>
                {dir === '' ? '(None)' : dir}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Input Area */}
      <footer className="bg-white border-t p-4 flex gap-2">
        <input 
          type="text" 
          value={input} 
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleSend()}
          placeholder="Type your message..."
          className="flex-1 border rounded px-4 py-2 focus:ring-2 focus:ring-blue-500 outline-none"
        />
        <button 
          onClick={handleSend}
          disabled={loading}
          className="bg-blue-600 text-white px-6 py-2 rounded font-semibold hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          Send
        </button>
      </footer>
    </div>
  );
}

export default App;
