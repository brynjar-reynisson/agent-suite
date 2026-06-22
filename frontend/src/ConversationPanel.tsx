import { useRef, useState, useEffect } from 'react';
import { getConversations, renameConversation, type ConversationSummary } from './api';
import { getAccessToken } from './auth';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (conv: ConversationSummary) => Promise<void>;
  onRename?: (externalId: string, displayName: string) => void;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
}

export function ConversationPanel({ isOpen, onClose, onSelect, onRename }: Props) {
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [selectError, setSelectError] = useState<string | null>(null);
  const [selecting, setSelecting] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const editHandledRef = useRef(false);

  useEffect(() => {
    if (!isOpen) return;
    setListLoading(true);
    setListError(null);
    setSelectError(null);
    getAccessToken()
      .then(token => getConversations(token))
      .then(setConversations)
      .catch(() => setListError('Failed to load conversations'))
      .finally(() => setListLoading(false));
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !editingId) onClose();
    };
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [isOpen, onClose, editingId]);

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

  const startEdit = (conv: ConversationSummary) => {
    editHandledRef.current = false;
    setEditingId(conv.externalId);
    setEditValue(conv.customName ?? conv.name);
  };

  const saveEdit = async (conv: ConversationSummary) => {
    if (editHandledRef.current) return;
    editHandledRef.current = true;
    const trimmed = editValue.trim();
    const prevCustomName = conv.customName;
    setConversations(prev =>
      prev.map(c =>
        c.externalId === conv.externalId ? { ...c, customName: trimmed || null } : c,
      ),
    );
    setEditingId(null);
    try {
      const token = await getAccessToken();
      await renameConversation(conv.externalId, trimmed, token);
      onRename?.(conv.externalId, trimmed || conv.name);
    } catch {
      setConversations(prev =>
        prev.map(c =>
          c.externalId === conv.externalId ? { ...c, customName: prevCustomName } : c,
        ),
      );
      setSelectError('Failed to rename conversation');
    }
  };

  const cancelEdit = () => {
    editHandledRef.current = true;
    setEditingId(null);
  };

  if (!isOpen) return null;

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-10" onClick={onClose} />
      {/* Panel */}
      <div className="fixed top-0 right-0 h-full w-96 bg-white shadow-xl z-20 flex flex-col border-l border-gray-200">
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
          {conversations.map((conv) => {
            const displayName = conv.customName ?? conv.name;
            const isEditing = editingId === conv.externalId;

            return (
              <div key={conv.externalId} className="border-b">
                <div
                  role="button"
                  tabIndex={isEditing || selecting !== null ? -1 : 0}
                  onClick={() => !isEditing && selecting === null && handleSelect(conv)}
                  onKeyDown={e => {
                    if ((e.key === 'Enter' || e.key === ' ') && !isEditing && selecting === null)
                      handleSelect(conv);
                  }}
                  title={displayName}
                  className={`w-full text-left px-4 py-5 transition-colors ${
                    isEditing || selecting !== null
                      ? 'opacity-50 cursor-default'
                      : 'hover:bg-gray-50 cursor-pointer'
                  }`}
                >
                  <div className="flex justify-between items-baseline gap-2">
                    <div className="flex items-center gap-1 min-w-0 flex-1">
                      <button
                        onClick={e => {
                          e.stopPropagation();
                          if (!isEditing) startEdit(conv);
                        }}
                        className="text-gray-400 hover:text-gray-600 shrink-0 leading-none text-base"
                        aria-label="Rename conversation"
                        title="Rename"
                      >
                        ✎
                      </button>
                      {isEditing ? (
                        <input
                          autoFocus
                          className="flex-1 text-sm text-gray-800 border border-blue-400 rounded px-1 outline-none"
                          value={editValue}
                          onChange={e => setEditValue(e.target.value)}
                          onKeyDown={e => {
                            if (e.key === 'Enter') {
                              e.preventDefault();
                              e.stopPropagation();
                              saveEdit(conv);
                            }
                            if (e.key === 'Escape') {
                              e.preventDefault();
                              e.stopPropagation();
                              cancelEdit();
                            }
                          }}
                          onBlur={() => saveEdit(conv)}
                          onClick={e => e.stopPropagation()}
                        />
                      ) : (
                        <span className="text-gray-800 text-sm truncate">
                          {selecting === conv.externalId ? 'Loading...' : displayName}
                        </span>
                      )}
                    </div>
                    <span className="text-xs text-gray-400 shrink-0">
                      {formatDate(conv.createTime)}
                    </span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </>
  );
}
