import { useEffect, useRef } from 'react'
import { Modal } from './Modal'
import './pdf-viewer.css'

export function PdfViewer({ file, onClose }: { file: { blob: Blob; filename: string }; onClose: () => void }) {
  const frame = useRef<HTMLIFrameElement>(null)
  const download = useRef<HTMLAnchorElement>(null)
  useEffect(() => {
    const objectUrl = URL.createObjectURL(file.blob)
    if (frame.current) frame.current.src = `${objectUrl}#toolbar=1&navpanes=1&view=FitH`
    if (download.current) download.current.href = objectUrl
    return () => URL.revokeObjectURL(objectUrl)
  }, [file.blob])
  return <Modal open title="환수 문서뷰어" className="pdf-viewer" onClose={onClose}>
    <div className="pdf-viewer-toolbar">
      <span>{file.filename}</span>
      <a ref={download} data-guide-feature="clawback.pdf-viewer.download" className="button button-primary" download={file.filename}>
        <span className="material-symbols-rounded" aria-hidden="true">download</span>다운로드
      </a>
    </div>
    <iframe ref={frame} title="환수 PDF 문서" className="pdf-viewer-frame" tabIndex={0} />
  </Modal>
}
