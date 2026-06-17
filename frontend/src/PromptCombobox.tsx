import { useEffect, useRef, useState } from 'react';
import { PROMPT_BANK } from './config';

interface PromptComboboxProps {
  value: string;
  onChange: (v: string) => void;
  prompts?: { name: string; text: string; tools: string[] }[];
}

export function PromptCombobox({ value, onChange, prompts = PROMPT_BANK }: PromptComboboxProps) {
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
