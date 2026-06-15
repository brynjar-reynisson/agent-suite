const TOOL_META: Record<string, { icon: string; tooltip: string }> = {
  'unix':      { icon: '📁', tooltip: 'unix: ls · cat · grep' },
  'md-writer': { icon: '✏️', tooltip: 'md-writer: write markdown files' },
  'web':       { icon: '🌐', tooltip: 'web: search · fetch' },
  'mcp':       { icon: '🔌', tooltip: 'mcp: external MCP servers' },
};

interface ToolStripProps {
  availableTools: string[];
  disabledTools: Set<string>;
  onToggle: (tool: string) => void;
  onInfo: () => void;
}

export function ToolStrip({ availableTools, disabledTools, onToggle, onInfo }: ToolStripProps) {
  if (availableTools.length === 0) return null;

  return (
    <div className="bg-white border-t border-gray-100 px-4 py-1.5 flex gap-2 items-center">
      {availableTools.map((tool) => {
        const meta = TOOL_META[tool] ?? { icon: '🔧', tooltip: tool };
        const disabled = disabledTools.has(tool);
        return (
          <button
            key={tool}
            type="button"
            title={meta.tooltip}
            onClick={() => onToggle(tool)}
            aria-label={`Toggle ${meta.tooltip}${disabled ? ' — currently disabled' : ''}`}
            aria-pressed={!disabled}
            className={`relative p-1.5 rounded-md text-base leading-none cursor-pointer transition-all ${
              disabled ? 'bg-gray-100 opacity-40 grayscale' : 'bg-blue-100'
            }`}
          >
            {meta.icon}
            {disabled && (
              <span className="absolute inset-0 flex items-center justify-center pointer-events-none" aria-hidden="true">
                <span className="block w-4/5 h-[1.5px] bg-gray-500 -rotate-[35deg]" />
              </span>
            )}
          </button>
        );
      })}
      <button
        type="button"
        onClick={onInfo}
        aria-label="Show tool info"
        title="Tool info"
        className="p-1.5 rounded-md text-base leading-none cursor-pointer bg-blue-50"
      >
        ℹ️
      </button>
    </div>
  );
}
