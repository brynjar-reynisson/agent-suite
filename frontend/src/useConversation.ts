import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  chatStream, compactConversation, compactMergeConversation, eraseLastTurn, execTool, execShellStream,
  getConversationDetail, type ConversationDetail, type ConversationSummary, type Message,
} from './api';
import { useAuth, getAccessToken } from './auth';
import { PROMPT_BANK } from './config';

const EXT_TO_LANG: Record<string, string> = {
  md: 'md', markdown: 'md',
  java: 'java',
  ts: 'ts', tsx: 'tsx',
  js: 'js', jsx: 'jsx',
  json: 'json',
  yaml: 'yaml', yml: 'yaml',
  sh: 'sh', bash: 'sh',
  xml: 'xml', html: 'html', css: 'css',
};

function catFileLang(command: string): string {
  if (!command.startsWith('cat ')) return '';
  const lastToken = command.trim().split(/\s+/).pop() ?? '';
  const ext = lastToken.replace(/["']/g, '').split('.').pop()?.toLowerCase() ?? '';
  return EXT_TO_LANG[ext] ?? '';
}

interface UseConversationOptions {
  model: string;
  prompt: string;
  rootDirectory: string;
  availableTools: string[];
  disabledTools: Set<string>;
  isAdmin: boolean;
}

export function useConversation({ model, prompt, rootDirectory, availableTools, disabledTools, isAdmin }: UseConversationOptions) {
  const { user } = useAuth();
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [errorToast, setErrorToast] = useState<string | null>(null);
  const conversationId = useRef<string>(crypto.randomUUID());
  const lastSentModel = useRef<string | null>(null);
  const lastSentPrompt = useRef<string | null>(null);
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const streamGenRef = useRef(0);
  const [editorFile, setEditorFile] = useState<{ path: string; rootDirectory: string } | null>(null);
  const closeEditor = useCallback(() => setEditorFile(null), []);
  const [activeConvDisplayName, setActiveConvDisplayName] = useState<string | null>(null);

  const historySizeBytes = useMemo(() => {
    const lastCompactIdx = messages.reduce(
      (acc: number, msg, i) => (msg.role === 'compact' ? i : acc), -1
    );
    const relevant = lastCompactIdx >= 0 ? messages.slice(lastCompactIdx) : messages;
    return new TextEncoder().encode(JSON.stringify(relevant)).length;
  }, [messages]);

  const resetConversation = useCallback(() => {
    conversationId.current = crypto.randomUUID();
    lastSentModel.current = null;
    lastSentPrompt.current = null;
    setMessages([]);
    setActiveConvDisplayName(null);
  }, []);

  useEffect(() => {
    if (!user) resetConversation();
  }, [user, resetConversation]);

  const loadConversation = async (conv: ConversationSummary): Promise<ConversationDetail> => {
    const token = await getAccessToken();
    const detail = await getConversationDetail(conv.externalId, token);
    conversationId.current = detail.externalId;
    lastSentModel.current = detail.initialModel;
    lastSentPrompt.current = detail.systemPrompt;
    setMessages(detail.messages);
    setActiveConvDisplayName(conv.customName ?? conv.name);
    return detail;
  };

  const updateActiveConvDisplayName = useCallback((externalId: string, displayName: string) => {
    if (externalId === conversationId.current) {
      setActiveConvDisplayName(displayName);
    }
  }, []);

  const showToast = (message: string) => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    setErrorToast(message || 'An error occurred');
    toastTimerRef.current = setTimeout(() => setErrorToast(null), 5000);
  };

  const handleSend = async (input: string) => {
    if (!input.trim()) return; // NEW: removed || loading

    // NEW: !stop — abort without adding to history
    if (input === '!stop') {
      abortRef.current?.abort();
      return;
    }

    // !erase-last — soft-delete last user+AI turn
    if (input === '!erase-last') {
      if (loading) { showToast('Use !stop first before erasing'); return; }
      const hasUser = messages.some(m => m.role === 'user');
      if (!hasUser) { showToast('Nothing to erase'); return; }
      try {
        const token = await getAccessToken();
        await eraseLastTurn(conversationId.current, token);
        // Reload from backend: local state may have extra AI messages from the
        // AbortError that fetchEventSource raises on !stop, so splicing is unreliable.
        const detail = await getConversationDetail(conversationId.current, token);
        setMessages(detail.messages);
      } catch (err: unknown) {
        showToast(err instanceof Error ? err.message : 'Erase failed');
      }
      return;
    }

    // !edit — blocked while loading
    const editMatch = input.match(/^!edit\s+(.+)$/i);
    if (editMatch) {
      if (loading) { showToast('Wait for the response to finish'); return; } // NEW
      if (!rootDirectory) {
        showToast('Select a root directory first');
      } else if (!isAdmin) {
        setMessages(prev => [
          ...prev,
          { role: 'user', content: input },
          { role: 'ai', content: 'Error: Permission denied' },
        ]);
      } else {
        setEditorFile({ path: editMatch[1].trim(), rootDirectory });
      }
      return;
    }

    // !! direct shell — blocked while loading
    const execMatch = input.match(/^!!(.+)$/);
    if (execMatch) {
      if (loading) { showToast('Wait for the response to finish'); return; } // NEW
      if (!rootDirectory) {
        showToast('Select a root directory first');
        return;
      }
      const command = execMatch[1].trim();
      setMessages(prev => [
        ...prev,
        { role: 'user', content: input },
        { role: 'ai', content: '```\n```' },
      ]);
      setLoading(true);
      let accumulated = '';
      try {
        const token = await getAccessToken();
        await execShellStream(command, rootDirectory, {
          onOutput: (line) => {
            accumulated += line + '\n';
            setMessages(prev => {
              const msgs = [...prev];
              msgs[msgs.length - 1] = { role: 'ai', content: '```\n' + accumulated + '```' };
              return msgs;
            });
          },
          onDone: (exitCode) => {
            if (exitCode !== 0) accumulated += '[exit ' + exitCode + ']\n';
            setMessages(prev => {
              const msgs = [...prev];
              msgs[msgs.length - 1] = { role: 'ai', content: '```\n' + accumulated + '```' };
              return msgs;
            });
            setLoading(false);
          },
          onError: (message) => {
            setMessages(prev => {
              const msgs = [...prev];
              msgs[msgs.length - 1] = { role: 'ai', content: 'Error: ' + message };
              return msgs;
            });
            setLoading(false);
          },
        }, token);
      } catch (error: unknown) {
        const msg = error instanceof Error ? error.message : 'Exec failed';
        setMessages(prev => {
          const msgs = [...prev];
          msgs[msgs.length - 1] = { role: 'ai', content: 'Error: ' + msg };
          return msgs;
        });
        setLoading(false);
      }
      return;
    }

    // /compact — blocked while loading
    if (input === '/compact') {
      if (loading) { showToast('Wait for the response to finish'); return; } // NEW
      if (!conversationId.current) {
        setMessages(prev => [...prev, { role: 'ai', content: 'Start a conversation before compacting.' }]);
        return;
      }
      setLoading(true);
      try {
        const token = await getAccessToken();
        const { summary } = await compactConversation(conversationId.current, token);
        setMessages(prev => [...prev, { role: 'compact', content: summary }]);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Compact failed.';
        setMessages(prev => [...prev, { role: 'ai', content: `Error: ${msg}` }]);
      } finally {
        setLoading(false);
      }
      return;
    }

    // /compact-merge — blocked while loading
    if (input === '/compact-merge') {
      if (loading) { showToast('Wait for the response to finish'); return; } // NEW
      if (!conversationId.current) {
        setMessages(prev => [...prev, { role: 'ai', content: 'Start a conversation before merging compacts.' }]);
        return;
      }
      setLoading(true);
      try {
        const token = await getAccessToken();
        const { summary } = await compactMergeConversation(conversationId.current, token);
        setMessages(prev => [...prev, { role: 'compact', content: summary }]);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Compact merge failed.';
        setMessages(prev => [...prev, { role: 'ai', content: `Error: ${msg}` }]);
      } finally {
        setLoading(false);
      }
      return;
    }

    // !exec (single ! commands other than !stop, !erase-last, !edit, !!) — blocked while loading
    if (input.startsWith('!')) {
      if (loading) { showToast('Wait for the response to finish'); return; } // NEW
      const metaMessages: Message[] = [];
      if (model !== lastSentModel.current) {
        metaMessages.push({ role: 'meta', content: 'model:' + model });
        lastSentModel.current = model;
      }
      if (prompt !== lastSentPrompt.current) {
        if (prompt) metaMessages.push({ role: 'meta', content: 'system:' + prompt });
        lastSentPrompt.current = prompt;
      }
      setMessages(prev => [...prev, ...metaMessages, { role: 'user', content: input }]);
      setLoading(true);
      try {
        const command = input.slice(1).trim();
        const token = await getAccessToken();
        const result = await execTool(command, rootDirectory, token);
        const lang = catFileLang(command);
        setMessages(prev => [...prev, lang
          ? { role: 'ai', content: result, sourceLanguage: lang }
          : { role: 'ai', content: '```\n' + result + '\n```' },
        ]);
      } catch (error: any) {
        setMessages(prev => [...prev, { role: 'ai', content: `Error: ${error.message}` }]);
      } finally {
        setLoading(false);
      }
      return;
    }

    // --- Normal chat (possibly interrupting an in-flight stream) ---

    // NEW: If loading, abort current stream and remove partial AI response
    if (loading) {
      abortRef.current?.abort();
      setMessages(prev => {
        const msgs = [...prev];
        if (msgs.length > 0 && msgs[msgs.length - 1].role === 'ai') {
          return msgs.slice(0, -1);
        }
        return msgs;
      });
    }

    // Build meta messages and add user message to state
    const metaMessages: Message[] = [];
    if (model !== lastSentModel.current) {
      metaMessages.push({ role: 'meta', content: 'model:' + model });
      lastSentModel.current = model;
    }
    if (prompt !== lastSentPrompt.current) {
      if (prompt) metaMessages.push({ role: 'meta', content: 'system:' + prompt });
      lastSentPrompt.current = prompt;
    }
    if (!input.startsWith('/')) {
      setMessages(prev => [...prev, ...metaMessages, { role: 'user', content: input }]);
    }

    // NEW: Generation counter prevents old stream's finally from clearing loading
    streamGenRef.current++;
    const gen = streamGenRef.current;
    const controller = new AbortController(); // NEW
    abortRef.current = controller;            // NEW
    setLoading(true);

    const matched = PROMPT_BANK.find(p => p.name === prompt);
    const resolvedPrompt = matched?.text ?? prompt;
    const enabledTools = availableTools.filter(t => !disabledTools.has(t)).join(',');
    try {
      const token = await getAccessToken();
      await chatStream(
        {
          message: input,
          prompt: resolvedPrompt,
          rootDirectory,
          model,
          tools: enabledTools,
          conversationId: conversationId.current,
          requestId: crypto.randomUUID(),
        },
        {
          onToolCall: (tc) => {
            setMessages(prev => {
              const msgs = [...prev];
              const last = msgs[msgs.length - 1];
              if (last && last.role === 'ai') {
                msgs[msgs.length - 1] = { ...last, toolCalls: [...(last.toolCalls || []), tc] };
              } else {
                msgs.push({ role: 'ai', content: '', toolCalls: [tc] });
              }
              return msgs;
            });
          },
          onContent: (text) => {
            setMessages(prev => {
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
          onError: showToast,
        },
        token,
        controller, // NEW: pass controller so it can be aborted externally
      );
    } catch (error: any) {
      if (gen === streamGenRef.current) { // NEW: only update state if this is still the active stream
        setMessages(prev => {
          const errorMessage: Message = { role: 'ai', content: `Error: ${error.message}` };
          const msgs = [...prev];
          const last = msgs[msgs.length - 1];
          if (last && last.role === 'ai' && last.content === '') {
            msgs[msgs.length - 1] = errorMessage;
          } else {
            msgs.push(errorMessage);
          }
          return msgs;
        });
      }
    } finally {
      if (gen === streamGenRef.current) { // NEW: only clear loading if this is still the active stream
        setLoading(false);
        abortRef.current = null;
      }
    }
  };

  return { messages, loading, errorToast, historySizeBytes, handleSend, resetConversation, loadConversation, editorFile, closeEditor, activeConvDisplayName, updateActiveConvDisplayName };
}
