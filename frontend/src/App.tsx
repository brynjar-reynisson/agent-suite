import { useEffect, useMemo, useRef, useState } from 'react';
import {
  chatStream, compactConversation, execTool, getDirectories, getConversationDetail, getUserConfig, type Message, type ConversationSummary,
} from './api';
import { ConversationPanel } from './ConversationPanel';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useAuth, getAccessToken } from './auth';
import { UserAvatar } from './UserAvatar';
import { ToolStrip } from './ToolStrip';
import { ToolInfoModal } from './ToolInfoModal';

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
  'deepseek-v4-flash',
  'sonnet-4.6',
  'opus-4.7',
  'opus-4.8',
  'haiku-4.5',
  'gemini-2.5-flash',
  'mistral-large',
  'mistral-small',
];

// tools field is used only for prompt-visibility filtering (hide md-writer prompts for non-admins)
// tool availability for chat requests is driven by the server (grantedToolGroups)
const PROMPT_BANK = [
  {
    name: 'Code-request classifier',
    text: 'You are a coding assistant and will use the available tools on the selected codebase to classify the coding requests you receive. Respond in json format 1) intent, which shall be either bug-fix, enhancement, new-feature, architecture-change or unknown, 2) confidence in the classification (percentages)',
    tools: ['unix'],
  },
  {
    name: 'Implementation-planner',
    text: 'Your job is to read a named specification file and create a step-by-step implementation plan. The plan should be broken down into small, actionable tasks that can be easily assigned to developers. The plan should also include any necessary technical details, such as which files or modules will need to be modified.',
    tools: ['unix', 'md-writer'],
  },
  {
    name: 'Plan-Reviewer',
    text: 'Your job is to read a named specification file and its implementation plan file, and review them for clarity, completeness and correctness. Provide feedback on any areas that are unclear, incomplete or incorrect. Pay special attention to possible security and performance issues. You will write your review comments in a new markdown review file.',
    tools: ['unix', 'md-writer'],
  },
  {
    name: 'Spec-Reviewer',
    text: 'Your job is to read a named specification file and review it for clarity, completeness and correctness. Provide feedback on any areas that are unclear, incomplete or incorrect, and suggest improvements to make the specification more effective. You will write your review comments in a new markdown review file.',
    tools: ['unix', 'md-writer'],
  },
  {
    name: 'Specification-writer',
    text: 'Your job is to create a new specification file that takes a user request and defines the business requirement, the user problem and the success criteria. Specify what is in scope and out of scope. This is about the what and why, not how it will be implemented.',
    tools: ['unix', 'md-writer'],
  },
  {
      name: 'Web-dweller',
      text: 'You have access to search and fetch information from the internet. Use it to find information on the web to answer user questions. Always use the tool when you need to find up-to-date information or access specific websites. If the user question can be answered with your existing knowledge, you can respond without using the tool.',
      tools: [],
  }
];

interface PromptComboboxProps {
  value: string;
  onChange: (v: string) => void;
  prompts?: { name: string; text: string; tools: string[] }[];
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
        <ul className="absolute z-10 w-full bg-white border rounded shadow-sm bottom-full mb-1">
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

function MetaMessage({ content }: { content: string }) {
  let label: string;
  let value: string;
  if (content.startsWith('model:')) {
    label = 'model';
    value = content.slice(6);
  } else if (content.startsWith('system:')) {
    label = 'system';
    value = content.slice(7);
  } else {
    label = '';
    value = content;
  }
  return (
    <div className="self-start max-w-[80%] px-3 py-2 rounded-lg bg-white shadow-sm text-xs font-mono text-gray-400">
      <span className="font-semibold text-gray-500">{label}</span>
      {label && <span className="mx-1 text-gray-300">·</span>}
      <span className="whitespace-pre-wrap">{value}</span>
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
  const [isAdmin, setIsAdmin] = useState(false);
  const [isToolInfoOpen, setIsToolInfoOpen] = useState(false);
  const [grantedToolGroups, setGrantedToolGroups] = useState<string[]>([]);
  const conversationId = useRef<string>(crypto.randomUUID());
  const lastSentModel = useRef<string | null>(null);
  const lastSentPrompt = useRef<string | null>(null);

  const availableTools = useMemo(() => {
    const toolSet = new Set(grantedToolGroups);
    if (rootDirectory) {
      toolSet.add('unix');
    } else {
      toolSet.delete('md-writer'); // md-writer requires a project root, same as unix
    }
    return [...toolSet];
  }, [grantedToolGroups, rootDirectory]);

  const [disabledTools, setDisabledTools] = useState<Set<string>>(new Set());

  const availableToolsKey = availableTools.join(',');
  useEffect(() => {
    setDisabledTools(new Set());
  }, [availableToolsKey]);

  const toggleTool = (tool: string) =>
    setDisabledTools(prev => {
      const next = new Set(prev);
      next.has(tool) ? next.delete(tool) : next.add(tool);
      return next;
    });

  const [isPanelOpen, setIsPanelOpen] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const { user, signIn, signOut } = useAuth();

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

  useEffect(() => {
    if (!user) {
      conversationId.current = crypto.randomUUID();
      lastSentModel.current = null;
      lastSentPrompt.current = null;
      setMessages([]);
      setModel('deepseek-v4-pro');
      setPrompt('');
      setRootDirectory('');
      setIsAdmin(false);
      setGrantedToolGroups([]);
      setIsToolInfoOpen(false);
      // No return — always fetch config so guests also receive grantedToolGroups (web is always granted)
    }
    const fetchUserConfig = async () => {
      try {
        const token = await getAccessToken();
        const config = await getUserConfig(token);
        setIsAdmin(config.isAdmin);
        setGrantedToolGroups(config.grantedToolGroups);
      } catch {
        setIsAdmin(false);
        setGrantedToolGroups(['web']);
      }
    };
    fetchUserConfig();
  }, [user]);

  const startNewConversation = () => {
    conversationId.current = crypto.randomUUID();
    lastSentModel.current = null;
    lastSentPrompt.current = null;
    setMessages([]);
    setModel('deepseek-v4-pro');
    setPrompt('');
    setRootDirectory('');
  };

  const loadConversation = async (conv: ConversationSummary): Promise<void> => {
    const token = await getAccessToken();
    const detail = await getConversationDetail(conv.externalId, token);
    conversationId.current = detail.externalId;
    lastSentModel.current = detail.initialModel;
    lastSentPrompt.current = detail.systemPrompt;
    setMessages(detail.messages);
    setModel(detail.initialModel);
    setPrompt(detail.systemPrompt);
    setRootDirectory(detail.rootDirectory ?? '');
    setIsPanelOpen(false);
  };

  const handleSend = async () => {
    if (!input.trim() || loading) return;

    const userMessage: Message = { role: 'user', content: input };
    const metaMessages: Message[] = [];
    if (model !== lastSentModel.current) {
      metaMessages.push({ role: 'meta', content: 'model:' + model });
      lastSentModel.current = model;
    }
    if (prompt !== lastSentPrompt.current) {
      if (prompt) metaMessages.push({ role: 'meta', content: 'system:' + prompt });
      lastSentPrompt.current = prompt;
    }
    setMessages((prev) => [...prev, ...metaMessages, userMessage]);
    const message = input;
    setInput('');
    setLoading(true);

    if (message.startsWith('!')) {
      try {
        const command = message.slice(1).trim();
        const token = await getAccessToken();
        const result = await execTool(command, rootDirectory, token);
        const toolOutput = '```\n' + result + '\n```';
        setMessages((prev) => [...prev, { role: 'ai', content: toolOutput }]);
      } catch (error: any) {
        setMessages((prev) => [...prev, { role: 'ai', content: `Error: ${error.message}` }]);
      } finally {
        setLoading(false);
      }
      return;
    }

    if (message === '/compact') {
      if (!conversationId.current) {
        setMessages((prev) => [
          ...prev,
          { role: 'ai', content: 'Start a conversation before compacting.' },
        ]);
        setLoading(false);
        return;
      }
      try {
        const token = await getAccessToken();
        const { summary } = await compactConversation(conversationId.current, token);
        setMessages((prev) => [...prev, { role: 'compact', content: summary }]);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Compact failed.';
        setMessages((prev) => [...prev, { role: 'ai', content: `Error: ${msg}` }]);
      } finally {
        setLoading(false);
      }
      return;
    }

    const matched = PROMPT_BANK.find(p => p.name === prompt);
    const resolvedPrompt = matched?.text ?? prompt;
    const enabledTools = availableTools.filter(t => !disabledTools.has(t)).join(',');
    try {
      const token = await getAccessToken();
      await chatStream(
        {
          message: message,
          prompt: resolvedPrompt,
          rootDirectory: rootDirectory,
          model: model,
          tools: enabledTools,
          conversationId: conversationId.current,
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
        },
        token,
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
        <div className="flex gap-2 items-center">
          <select
            value={model}
            onChange={(e) => setModel(e.target.value)}
            className="border rounded px-2 py-1 text-sm bg-gray-50"
          >
            {MODELS.map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
          <button
            onClick={startNewConversation}
            disabled={loading}
            title="New conversation"
            className="p-1.5 rounded hover:bg-gray-100 disabled:opacity-50 text-gray-600 font-bold text-lg leading-none"
            aria-label="New conversation"
          >
            +
          </button>
          <button
            onClick={() => setIsPanelOpen(true)}
            disabled={loading}
            title="Past conversations"
            className="p-1.5 rounded hover:bg-gray-100 disabled:opacity-50 text-gray-600 text-base leading-none"
            aria-label="Past conversations"
          >
            ☰
          </button>
          <UserAvatar user={user} signIn={signIn} signOut={signOut} />
        </div>
      </header>

      {/* Main Chat Area */}
      <main className="flex-1 overflow-y-auto p-4 flex flex-col gap-4">
        {messages.length === 0 && (
          <div className="flex-1 flex items-center justify-center text-gray-400">
            Start a conversation...
          </div>
        )}
        {messages.map((msg, i) => {
          if (msg.role === 'meta') return <MetaMessage key={i} content={msg.content} />;
          if (msg.role === 'compact') {
            return (
              <div key={i} className="self-stretch rounded border border-gray-700 bg-gray-800/50 px-4 py-3 text-sm text-gray-300">
                <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-500">Conversation compacted</p>
                <p className="whitespace-pre-wrap">{msg.content}</p>
              </div>
            );
          }
          return (
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
                  <ReactMarkdown
                    remarkPlugins={[remarkGfm]}
                    components={{
                      a: ({ href, children }: { href?: string; children?: React.ReactNode }) => {
                        if (href && /\.(wav|mp3)$/i.test(href)) {
                          return (
                            <span className="block mt-1">
                              <audio controls src={href} className="w-full" />
                              <a
                                href={href}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="text-xs text-gray-400 hover:underline"
                              >
                                {children}
                              </a>
                            </span>
                          );
                        }
                        return (
                          <a href={href} target="_blank" rel="noopener noreferrer">
                            {children}
                          </a>
                        );
                      },
                    }}
                  >
                    {msg.content}
                  </ReactMarkdown>
                </div>
              )}
            </div>
          );
        })}
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
          <PromptCombobox
            value={prompt}
            onChange={setPrompt}
            prompts={isAdmin ? PROMPT_BANK : PROMPT_BANK.filter(p => !p.tools.includes('md-writer'))}
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

      <ToolStrip
        availableTools={availableTools}
        disabledTools={disabledTools}
        onToggle={toggleTool}
        onInfo={() => setIsToolInfoOpen(true)}
      />
      {isToolInfoOpen && (
        <ToolInfoModal
          availableTools={availableTools}
          disabledTools={disabledTools}
          rootDirectory={rootDirectory}
          onClose={() => setIsToolInfoOpen(false)}
        />
      )}

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
      <ConversationPanel
        isOpen={isPanelOpen}
        onClose={() => setIsPanelOpen(false)}
        onSelect={loadConversation}
      />
    </div>
  );
}

export default App;
