import { useEffect } from 'react';
import ReactDOM from 'react-dom';

interface Props {
  src: string;
  alt: string;
  onClose: () => void;
}

export function ImageLightbox({ src, alt, onClose }: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return ReactDOM.createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80"
      onClick={onClose}
    >
      {/* Stop click on the image itself from closing */}
      <img
        src={src}
        alt={alt}
        className="max-h-[90vh] max-w-[90vw] object-contain rounded shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      />
      <button
        onClick={onClose}
        aria-label="Close"
        className="absolute top-4 right-4 flex items-center justify-center w-9 h-9 rounded bg-white/10 text-white text-xl hover:bg-white/25 transition-colors"
      >
        ✕
      </button>
      <p className="absolute bottom-4 left-1/2 -translate-x-1/2 text-white/40 text-xs tracking-wide select-none">
        Press Esc to close
      </p>
    </div>,
    document.body
  );
}
