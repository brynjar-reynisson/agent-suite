import { useState, useEffect } from 'react';
import { getConversations, type ConversationSummary } from './api';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (conv: ConversationSummary) => Promise<void>;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
}

export function ConversationPanel({ isOpen, onClose, onSelect }: Props) {
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [selectError, setSelectError] = useState<string | null>(null);
  const [selecting, setSelecting] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    setListLoading(true);
    setListError(null);
    setSelectError(null);
    getConversations()
      .then(setConversations)
      .catch(() => setListError('Failed to load conversations'))
      .finally(() => setListLoading(false));
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [isOpen, onClose]);

  const handleSelect = async (conv: ConversationSummary) => {
    setSelecting(conv.externalId);
    setSelectError(null);
    try {
      await onSelect(conv);
    } catch {
      setSelectError('Failed to load conversation');
    } finally {
      setSelecting(null);
    }
  };

  if (!isOpen) return null;

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-10" onClick={onClose} />
      {/* Panel */}
      <div className="fixed top-0 right-0 h-full w-72 bg-white shadow-xl z-20 flex flex-col border-l border-gray-200">
        <div className="p-4 border-b flex justify-between items-center shrink-0">
          <h2 className="font-semibold text-gray-800 text-sm">Past Conversations</h2>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-lg leading-none"
            aria-label="Close"
          >
            ✕
          </button>
        </div>
        {selectError && (
          <div className="px-4 py-2 bg-red-50 text-red-600 text-xs border-b shrink-0">
            {selectError}
          </div>
        )}
        <div className="flex-1 overflow-y-auto">
          {listLoading && (
            <p className="p-4 text-sm text-gray-400">Loading...</p>
          )}
          {listError && (
            <p className="p-4 text-sm text-red-500">{listError}</p>
          )}
          {!listLoading && !listError && conversations.length === 0 && (
            <p className="p-4 text-sm text-gray-400">No conversations yet.</p>
          )}
          {conversations.map((conv) => (
            <button
              key={conv.externalId}
              onClick={() => handleSelect(conv)}
              disabled={selecting !== null}
              className="w-full text-left px-4 py-3 border-b hover:bg-gray-50 transition-colors disabled:opacity-50"
            >
              <div className="flex justify-between items-baseline gap-2">
                <span className="font-medium text-gray-800 text-sm truncate">
                  {selecting === conv.externalId ? 'Loading...' : conv.name}
                </span>
                <span className="text-xs text-gray-400 shrink-0">
                  {formatDate(conv.createTime)}
                </span>
              </div>
            </button>
          ))}
        </div>
      </div>
    </>
  );
}
