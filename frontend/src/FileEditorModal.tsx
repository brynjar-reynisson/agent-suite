import { useEffect, useState, useCallback } from 'react';
import ReactDOM from 'react-dom';
import { readFile, writeFile } from './api';
import { getAccessToken } from './auth';

export interface FileEditorPlugin {
  test: (path: string) => boolean;
  render: (content: string, onChange: (value: string) => void) => React.ReactNode;
}

interface Props {
  path: string;
  rootDirectory: string;
  onClose: () => void;
  plugins?: FileEditorPlugin[];
}

export function FileEditorModal({ path, rootDirectory, onClose, plugins }: Props) {
  const [content, setContent] = useState('');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    getAccessToken()
      .then(token => readFile(path, rootDirectory, token))
      .then(text => { if (!cancelled) { setContent(text); setLoading(false); } })
      .catch((err: Error) => { if (!cancelled) { setLoadError(err.message); setLoading(false); } });
    return () => { cancelled = true; };
  }, [path, rootDirectory]);

  const handleSave = useCallback(async () => {
    setSaving(true);
    setSaveError(null);
    try {
      const token = await getAccessToken();
      await writeFile(path, rootDirectory, content, token);
      onClose();
    } catch (err: unknown) {
      setSaveError(err instanceof Error ? err.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }, [path, rootDirectory, content, onClose]);

  const activePlugin = plugins?.find(p => p.test(path));

  return ReactDOM.createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/45"
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="bg-white rounded-xl shadow-2xl flex flex-col w-[800px] max-w-[95vw] h-[80vh]">
        <div className="px-5 py-3 border-b border-gray-100 flex justify-between items-center flex-shrink-0">
          <span className="text-sm font-mono text-gray-700 truncate">{path}</span>
          <button
            onClick={onClose}
            aria-label="Close"
            className="text-gray-400 hover:text-gray-600 text-lg leading-none px-1.5 py-0.5 rounded ml-3 flex-shrink-0"
          >
            ×
          </button>
        </div>

        <div className="flex-1 overflow-hidden flex flex-col min-h-0">
          {loading && (
            <div className="flex-1 flex items-center justify-center text-gray-400 text-sm">
              Loading…
            </div>
          )}
          {loadError && (
            <div className="flex-1 flex items-center justify-center text-red-500 text-sm px-6">
              {loadError}
            </div>
          )}
          {!loading && !loadError && (
            activePlugin
              ? (
                <div className="flex-1 overflow-auto">
                  {activePlugin.render(content, setContent)}
                </div>
              )
              : (
                <textarea
                  value={content}
                  onChange={e => setContent(e.target.value)}
                  className="flex-1 resize-none font-mono text-sm p-4 outline-none border-0"
                  spellCheck={false}
                />
              )
          )}
        </div>

        <div className="px-5 py-3 border-t border-gray-100 flex justify-end items-center gap-2 flex-shrink-0">
          {saveError && (
            <span className="text-red-500 text-sm mr-auto">{saveError}</span>
          )}
          <button
            onClick={onClose}
            disabled={saving}
            className="px-4 py-1.5 rounded text-sm text-gray-600 hover:bg-gray-100 disabled:opacity-50"
          >
            Close
          </button>
          <button
            onClick={handleSave}
            disabled={saving || loading || !!loadError}
            className="px-4 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
