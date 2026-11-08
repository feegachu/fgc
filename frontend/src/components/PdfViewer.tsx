import { useEffect, useRef } from 'react'
import { Modal } from './Modal'
import './pdf-viewer.css'

export function PdfViewer({ file, onClose }: { file: { blob: Blob; filename: string }; onClose: () => void }) {
  const frame = useRef<HTMLIFrameElement>(null)
  useEffect(() => {
    const objectUrl = URL.createObjectURL(file.blob)
    if (frame.current) frame.current.src = `${objectUrl}#toolbar=1&navpanes=1&view=FitH`
    return () => URL.revokeObjectURL(objectUrl)
  }, [file.blob])
  return <Modal open title="환수 문서뷰어" className="pdf-viewer" onClose={onClose}>
    <iframe ref={frame} title="환수 PDF 문서" className="pdf-viewer-frame" tabIndex={0} />
  </Modal>
}
