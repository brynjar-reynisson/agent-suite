import { useEffect, useRef, useState } from 'react';
import { chatStream, execTool, getDirectories, type ToolCall } from './api';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface Message {
  role: 'user' | 'ai';
  content: string;
  toolCalls?: ToolCall[];
}

function formatToolArgs(args: string): string {
  try {
    const parsed = JSON.parse(args);
    return Object.values(parsed).join(' ');
  } catch {
    return args;
  }
}

const MODELS = [
  'deepseek-v4-pro',
  'sonnet-4.6',
  'opus-4.7',
  'haiku-4.5',
  'gemini-2.5-pro',
  'gemini-2.5-flash',
];

const PROMPT_BANK = [
  {
    name: 'Code-request classifier',
    text: 'You are a coding assistant and will use the available tools on the selected codebase to classify the coding requests you receive. Respond in json format 1) intent, which shall be either bug-fix, enhancement, new-feature, architecture-change or unknown, 2) confidence in the classification (percentages)',
  },
  {
      name: 'Implementation-planner',
      text: 'Your job is to read a named specification file and create a step-by-step implementation plan. The plan should be broken down into small, actionable tasks that can be easily assigned to developers. The plan should also include any necessary technical details, such as which files or modules will need to be modified.'
  },

  {
      name: 'Specification-writer',
      text: 'Your job is to create a new specification file that takes a user request and defines the business requirement, the user problem and the success criteria. Specify what is in scope and out of scope. This is about the what and why, not how it will be implemented.'
  },

];

interface PromptComboboxProps {
  value: string;
  onChange: (v: string) => void;
  prompts?: { name: string; text: string }[];
}

function PromptCombobox({ value, onChange, prompts = PROMPT_BANK }: PromptComboboxProps) {
  const [isOpen, setIsOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleMouseDown = (e: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleMouseDown);
    return () => document.removeEventListener('mousedown', handleMouseDown);
  }, []);

  return (
    <div ref={wrapperRef} className="relative">
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="System instructions..."
        className="w-full border rounded px-3 py-2 pr-8 text-sm focus:ring-2 focus:ring-blue-500 outline-none"
      />
      <button
        type="button"
        aria-label="Open prompt presets"
        onClick={() => setIsOpen((o) => !o)}
        className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
      >
        ▾
      </button>
      {isOpen && (
        <ul className="absolute z-10 w-full bg-white border rounded shadow-sm mt-1">
          {prompts.map((entry) => (
            <li
              key={entry.name}
              onMouseDown={() => {
                onChange(entry.name);
                setIsOpen(false);
              }}
              className="px-3 py-2 text-sm cursor-pointer hover:bg-gray-100"
            >
              {entry.name}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [prompt, setPrompt] = useState('');
  const [rootDirectory, setRootDirectory] = useState('');
  const [allowedDirectories, setAllowedDirectories] = useState<string[]>([]);
  const [model, setModel] = useState('deepseek-v4-pro');
  const [loading, setLoading] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, loading]);

  useEffect(() => {
    const fetchConfig = async () => {
      try {
        const dirs = await getDirectories();
        const sortedDirs = [...dirs].sort((a, b) => {
          if (a === '') return -1;
          if (b === '') return 1;
          return a.localeCompare(b);
        });
        setAllowedDirectories(sortedDirs);
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
    const message = input;
    setInput('');
    setLoading(true);

    if (message.startsWith('!')) {
      try {
        const command = message.slice(1).trim();
        const result = await execTool(command, rootDirectory);
        const toolOutput = '```\n' + result + '\n```';
        setMessages((prev) => [...prev, { role: 'ai', content: toolOutput }]);
      } catch (error: any) {
        setMessages((prev) => [...prev, { role: 'ai', content: `Error: ${error.message}` }]);
      } finally {
        setLoading(false);
      }
      return;
    }

    const resolvedPrompt = PROMPT_BANK.find(p => p.name === prompt)?.text ?? prompt;
    try {
      await chatStream(
        {
          message: message,
          prompt: resolvedPrompt,
          rootDirectory: rootDirectory,
          model: model,
        },
        {
          onToolCall: (tc) => {
            setMessages((prev) => {
              const msgs = [...prev];
              const last = msgs[msgs.length - 1];
              if (last && last.role === 'ai') {
                const updated = { ...last, toolCalls: [...(last.toolCalls || []), tc] };
                msgs[msgs.length - 1] = updated;
              } else {
                msgs.push({ role: 'ai', content: '', toolCalls: [tc] });
              }
              return msgs;
            });
          },
          onContent: (text) => {
            setMessages((prev) => {
              const msgs = [...prev];
              const last = msgs[msgs.length - 1];
              if (last && last.role === 'ai') {
                msgs[msgs.length - 1] = { ...last, content: text };
              } else {
                msgs.push({ role: 'ai', content: text });
              }
              return msgs;
            });
          },
        }
      );
    } catch (error: any) {
      setMessages((prev) => {
        const errorMessage: Message = {
          role: 'ai',
          content: `Error: ${error.message}`,
        };
        // Replace any in-progress streaming message
        const msgs = [...prev];
        const last = msgs[msgs.length - 1];
        if (last && last.role === 'ai' && last.content === '') {
          msgs[msgs.length - 1] = errorMessage;
        } else {
          msgs.push(errorMessage);
        }
        return msgs;
      });
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
            {msg.toolCalls && msg.toolCalls.length > 0 && (
              <div className="mb-3 pb-3 border-b border-gray-200">
                {msg.toolCalls.map((tc, j) => (
                  <div key={j} className="text-xs text-gray-400 font-mono mb-1">
                    <span className="font-semibold text-gray-500">{tc.name}</span>
                    <span className="ml-1 text-gray-400">{formatToolArgs(tc.arguments)}</span>
                  </div>
                ))}
              </div>
            )}
            {msg.content && (
              <div className={`prose max-w-none ${msg.role === 'user' ? 'prose-invert' : ''}`}>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {msg.content}
                </ReactMarkdown>
              </div>
            )}
          </div>
        ))}
        {loading && (
          <div className="self-start bg-white p-3 rounded-lg shadow-sm text-gray-400 animate-pulse">
            Thinking...
          </div>
        )}
        <div ref={bottomRef} />
      </main>

      {/* Settings Panel */}
      <div className="bg-white border-t p-4 flex gap-4 flex-wrap">
        <div className="flex-1 min-w-[300px]">
          <label className="block text-xs font-semibold text-gray-500 mb-1">SYSTEM PROMPT</label>
          <PromptCombobox value={prompt} onChange={setPrompt} />
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
