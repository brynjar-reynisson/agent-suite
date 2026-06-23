import { useRef, useEffect, Children, isValidElement, type ReactElement } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { MetaMessage } from './MetaMessage';
import { FileContentRenderer } from './FileContentRenderer';
import type { Message } from './api';

function formatToolArgs(args: string): string {
  try {
    const parsed = JSON.parse(args);
    return Object.values(parsed).join(' ');
  } catch {
    return args;
  }
}

interface MessageListProps {
  messages: Message[];
  loading: boolean;
  historySizeBytes: number;
  onImageClick: (src: string) => void;
}

export function MessageList({ messages, loading, historySizeBytes, onImageClick }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, loading]);

  return (
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
            {msg.content && msg.sourceLanguage && (
              <FileContentRenderer language={msg.sourceLanguage}>
                {msg.content}
              </FileContentRenderer>
            )}
            {msg.content && !msg.sourceLanguage && (
              <div className={`prose max-w-none ${msg.role === 'user' ? 'prose-invert' : ''}`}>
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={{
                    pre({ children }: { children?: React.ReactNode }) {
                      const codeEl = Children.toArray(children).find(isValidElement) as ReactElement<{ className?: string; children?: React.ReactNode }> | undefined;
                      const lang = /language-(\w+)/.exec(codeEl?.props?.className ?? '')?.[1] ?? '';
                      if (lang) {
                        return (
                          <FileContentRenderer language={lang}>
                            {String(codeEl?.props?.children ?? '').trimEnd()}
                          </FileContentRenderer>
                        );
                      }
                      return <pre>{children}</pre>;
                    },
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
                    img: ({ src, alt }: { src?: string; alt?: string }) => (
                      <div className="relative inline-block max-w-[380px]">
                        <img
                          src={src}
                          alt={alt ?? 'screenshot'}
                          onClick={() => src && onImageClick(src)}
                          className="block max-w-full rounded-lg border border-[#d0d8e8] cursor-pointer"
                        />
                        <div className="absolute bottom-2 right-2 rounded-[5px] bg-black/55 px-2 py-[3px] text-[0.72rem] text-white">
                          click to expand
                        </div>
                      </div>
                    ),
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
      {messages.length > 0 && (
        <div className={`self-end sticky bottom-2 rounded-full px-2 py-0.5 text-xs font-mono bg-gray-900/60 ${
          historySizeBytes >= 2.5 * 1_048_576
            ? 'text-red-400'
            : historySizeBytes >= 1.5 * 1_048_576
            ? 'text-amber-400'
            : 'text-gray-400'
        }`}>
          {(historySizeBytes / 1_048_576).toFixed(2)} MB
        </div>
      )}
      <div ref={bottomRef} />
    </main>
  );
}
