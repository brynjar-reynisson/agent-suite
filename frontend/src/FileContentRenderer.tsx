import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface FileContentRendererProps {
  language: string;
  children: string;
}

export function FileContentRenderer({ language, children }: FileContentRendererProps) {
  if (language === 'md') {
    return (
      <div className="rounded border border-gray-200 bg-gray-50 px-5 py-4 my-1 overflow-auto max-h-[60vh] prose prose-sm max-w-none">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>
      </div>
    );
  }

  // Future: add syntax highlighting for java, ts, tsx, js, json, etc.
  return (
    <pre className="rounded bg-gray-900 text-gray-100 p-4 overflow-x-auto text-sm my-1 not-prose">
      <code>{children}</code>
    </pre>
  );
}
