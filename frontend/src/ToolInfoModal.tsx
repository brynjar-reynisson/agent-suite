import { useEffect } from 'react';

const TOOL_META: Record<string, { icon: string; description: string }> = {
  web:         { icon: '🌐', description: 'Search the web and fetch URLs.' },
  unix:        { icon: '📁', description: 'Browse, read and search files in the selected project.' },
  'md-writer': { icon: '✏️', description: 'Write spec and plan markdown files to the project.' },
};

interface ToolInfoModalProps {
  availableTools: string[];
  disabledTools: Set<string>;
  onClose: () => void;
}

export function ToolInfoModal({ availableTools, disabledTools, onClose }: ToolInfoModalProps) {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 bg-black/45 z-50 flex items-center justify-center"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="bg-white rounded-xl w-[380px] max-w-[90vw] shadow-2xl overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100 flex justify-between items-center">
          <h2 className="text-sm font-semibold text-gray-900 m-0">Available tools</h2>
          <button
            onClick={onClose}
            aria-label="Close"
            className="text-gray-400 hover:text-gray-600 text-lg leading-none px-1.5 py-0.5 rounded"
          >
            ×
          </button>
        </div>
        <div className="px-5 py-4 flex flex-col gap-4">
          {availableTools.map((tool) => {
            const meta = TOOL_META[tool] ?? { icon: '🔧', description: '' };
            const disabled = disabledTools.has(tool);
            return (
              <div key={tool} className={`flex gap-3 ${disabled ? 'opacity-50' : ''}`}>
                <span className="text-2xl flex-shrink-0 mt-0.5">{meta.icon}</span>
                <div className="flex-1">
                  <div className="flex items-center gap-2 mb-0.5">
                    <span className="text-sm font-semibold text-gray-900">{tool}</span>
                    {disabled ? (
                      <span className="text-xs bg-red-100 text-red-800 px-2 py-0.5 rounded-full font-medium">disabled</span>
                    ) : (
                      <span className="text-xs bg-green-100 text-green-800 px-2 py-0.5 rounded-full font-medium">enabled</span>
                    )}
                  </div>
                  {meta.description && (
                    <p className="text-xs text-gray-500 m-0">{meta.description}</p>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
