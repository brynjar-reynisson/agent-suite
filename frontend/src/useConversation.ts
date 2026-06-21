import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  chatStream, compactConversation, compactMergeConversation, execTool, execShellStream,
  getConversationDetail, type ConversationDetail, type ConversationSummary, type Message,
} from './api';
import { useAuth, getAccessToken } from './auth';
import { PROMPT_BANK } from './config';

interface UseConversationOptions {
  model: string;
  prompt: string;
  rootDirectory: string;
  availableTools: string[];
  disabledTools: Set<string>;
}

export function useConversation({ model, prompt, rootDirectory, availableTools, disabledTools }: UseConversationOptions) {
  const { user } = useAuth();
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [errorToast, setErrorToast] = useState<string | null>(null);
  const conversationId = useRef<string>(crypto.randomUUID());
  const lastSentModel = useRef<string | null>(null);
  const lastSentPrompt = useRef<string | null>(null);
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [editorFile, setEditorFile] = useState<{ path: string; rootDirectory: string } | null>(null);
  const closeEditor = useCallback(() => setEditorFile(null), []);

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
    return detail;
  };

  const showToast = (message: string) => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    setErrorToast(message || 'An error occurred');
    toastTimerRef.current = setTimeout(() => setErrorToast(null), 5000);
  };

  const handleSend = async (input: string) => {
    if (!input.trim() || loading) return;

    // intercept !edit before adding to conversation history
    const editMatch = input.match(/^!edit\s+(.+)$/i);
    if (editMatch) {
      if (!rootDirectory) {
        showToast('Select a root directory first');
      } else {
        setEditorFile({ path: editMatch[1].trim(), rootDirectory });
      }
      return;
    }

    // intercept !! for direct shell execution
    const execMatch = input.match(/^!!(.+)$/);
    if (execMatch) {
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
    if (!input.startsWith('/')) {
      setMessages((prev) => [...prev, ...metaMessages, userMessage]);
    }
    setLoading(true);

    if (input.startsWith('!')) {
      try {
        const command = input.slice(1).trim();
        const token = await getAccessToken();
        const result = await execTool(command, rootDirectory, token);
        setMessages((prev) => [...prev, { role: 'ai', content: '```\n' + result + '\n```' }]);
      } catch (error: any) {
        setMessages((prev) => [...prev, { role: 'ai', content: `Error: ${error.message}` }]);
      } finally {
        setLoading(false);
      }
      return;
    }

    if (input === '/compact') {
      if (!conversationId.current) {
        setMessages((prev) => [...prev, { role: 'ai', content: 'Start a conversation before compacting.' }]);
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

    if (input === '/compact-merge') {
      if (!conversationId.current) {
        setMessages((prev) => [...prev, { role: 'ai', content: 'Start a conversation before merging compacts.' }]);
        setLoading(false);
        return;
      }
      try {
        const token = await getAccessToken();
        const { summary } = await compactMergeConversation(conversationId.current, token);
        setMessages((prev) => [...prev, { role: 'compact', content: summary }]);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Compact merge failed.';
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
            setMessages((prev) => {
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
          onError: showToast,
        },
        token,
      );
    } catch (error: any) {
      setMessages((prev) => {
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
    } finally {
      setLoading(false);
    }
  };

  return { messages, loading, errorToast, historySizeBytes, handleSend, resetConversation, loadConversation, editorFile, closeEditor };
}
