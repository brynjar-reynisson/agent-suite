import { useEffect, useMemo, useRef, useState } from 'react';
import { getDirectories, type ConversationSummary } from './api';
import { ConversationPanel } from './ConversationPanel';
import { useAuth } from './auth';
import { UserAvatar } from './UserAvatar';
import { ToolStrip } from './ToolStrip';
import { ToolInfoModal } from './ToolInfoModal';
import { ImageLightbox } from './ImageLightbox';
import { FileEditorModal } from './FileEditorModal';
import { PromptCombobox } from './PromptCombobox';
import { MessageList } from './MessageList';
import { useUserConfig } from './useUserConfig';
import { useConversation } from './useConversation';
import { MODELS, PROMPT_BANK } from './config';

function App() {
  const { user, signIn, signOut } = useAuth();
  const { isAdmin, grantedToolGroups } = useUserConfig();

  const [model, setModel] = useState('deepseek-v4-pro');
  const [prompt, setPrompt] = useState('');
  const [rootDirectory, setRootDirectory] = useState('');
  const [allowedDirectories, setAllowedDirectories] = useState<string[]>([]);
  const [disabledTools, setDisabledTools] = useState<Set<string>>(new Set());
  const [isToolInfoOpen, setIsToolInfoOpen] = useState(false);
  const [isPanelOpen, setIsPanelOpen] = useState(false);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const availableTools = useMemo(() => {
    const toolSet = new Set(grantedToolGroups);
    if (rootDirectory) {
      toolSet.add('unix');
    } else {
      toolSet.delete('md-writer'); // md-writer requires a project root, same as unix
    }
    return [...toolSet];
  }, [grantedToolGroups, rootDirectory]);

  const availableToolsKey = availableTools.join(',');
  useEffect(() => {
    setDisabledTools(new Set());
  }, [availableToolsKey]);

  useEffect(() => {
    getDirectories()
      .then((dirs) => {
        const sorted = [...dirs].sort((a, b) => {
          if (a === '') return -1;
          if (b === '') return 1;
          return a.localeCompare(b);
        });
        setAllowedDirectories(sorted);
        setRootDirectory('');
      })
      .catch((error) => console.error('Failed to fetch allowed directories:', error));
  }, []);

  useEffect(() => {
    if (!user) {
      setModel('deepseek-v4-pro');
      setPrompt('');
      setRootDirectory('');
      setIsToolInfoOpen(false);
    }
  }, [user]);

  const { messages, loading, errorToast, historySizeBytes, handleSend, resetConversation, loadConversation, editorFile, closeEditor, activeConvDisplayName, updateActiveConvDisplayName } =
    useConversation({ model, prompt, rootDirectory, availableTools, disabledTools, isAdmin });

  const startNewConversation = () => {
    resetConversation();
    setModel('deepseek-v4-pro');
    setPrompt('');
    setRootDirectory('');
  };

  const handleLoadConversation = async (conv: ConversationSummary) => {
    const detail = await loadConversation(conv);
    setModel(detail.initialModel);
    setPrompt(detail.systemPrompt);
    setRootDirectory(detail.rootDirectory ?? '');
    setIsPanelOpen(false);
  };

  const toggleTool = (tool: string) =>
    setDisabledTools(prev => {
      const next = new Set(prev);
      next.has(tool) ? next.delete(tool) : next.add(tool);
      return next;
    });

  return (
    <div className="flex flex-col h-screen bg-gray-100 font-sans">
      <header className="bg-white shadow-sm p-4 flex justify-between items-center">
        <div className="flex items-baseline gap-3 min-w-0 flex-1 overflow-hidden">
          <h1 className="text-xl font-bold text-gray-800 flex-shrink-0">AgentSuite Chat</h1>
          {activeConvDisplayName && (
            <span className="text-sm text-gray-400 truncate min-w-0">{activeConvDisplayName}</span>
          )}
        </div>
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

      <MessageList
        messages={messages}
        loading={loading}
        historySizeBytes={historySizeBytes}
        onImageClick={setLightboxSrc}
      />

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

      <footer className="bg-white border-t p-4 flex gap-2">
        <input
          ref={inputRef}
          type="text"
          onKeyDown={(e) => {
            if (e.key === 'Enter' && inputRef.current) {
              handleSend(inputRef.current.value);
              inputRef.current.value = '';
            }
          }}
          placeholder="Type your message..."
          className="flex-1 border rounded px-4 py-2 focus:ring-2 focus:ring-blue-500 outline-none"
        />
        <button
          onClick={() => {
            if (inputRef.current) {
              handleSend(inputRef.current.value);
              inputRef.current.value = '';
            }
          }}
          disabled={loading}
          className="bg-blue-600 text-white px-6 py-2 rounded font-semibold hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          Send
        </button>
      </footer>

      <ConversationPanel
        isOpen={isPanelOpen}
        onClose={() => setIsPanelOpen(false)}
        onSelect={handleLoadConversation}
        onRename={updateActiveConvDisplayName}
      />
      {lightboxSrc && (
        <ImageLightbox
          src={lightboxSrc}
          alt="screenshot"
          onClose={() => setLightboxSrc(null)}
        />
      )}
      {editorFile && (
        <FileEditorModal
          path={editorFile.path}
          rootDirectory={editorFile.rootDirectory}
          onClose={closeEditor}
        />
      )}
      {errorToast && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 px-4 py-2 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm shadow-lg whitespace-pre-wrap max-w-md text-center">
          {errorToast}
        </div>
      )}
    </div>
  );
}

export default App;
