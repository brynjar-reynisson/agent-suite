export function MetaMessage({ content }: { content: string }) {
  let label: string;
  let value: string;
  if (content.startsWith('model:')) {
    label = 'model';
    value = content.slice(6);
  } else if (content.startsWith('system:')) {
    label = 'system';
    value = content.slice(7);
  } else {
    label = '';
    value = content;
  }
  return (
    <div className="self-start max-w-[80%] px-3 py-2 rounded-lg bg-white shadow-sm text-xs font-mono text-gray-400">
      <span className="font-semibold text-gray-500">{label}</span>
      {label && <span className="mx-1 text-gray-300">·</span>}
      <span className="whitespace-pre-wrap">{value}</span>
    </div>
  );
}
