import { ElMessage } from 'element-plus'
import request from '../../../utils/request'
import { sanitizeEditorHtml } from '../../../utils/sanitizeHtml'

const escapeHtml = (value) => String(value || '')
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#039;')

export function useEditorDownloads() {
  const openSafeUrl = (rawUrl) => {
    try {
      const url = new URL(rawUrl, window.location.origin)
      if (!['http:', 'https:'].includes(url.protocol)) throw new Error('unsupported protocol')
      window.open(url.href, '_blank', 'noopener,noreferrer')
    } catch {
      ElMessage.warning('下载地址不安全或格式无效')
    }
  }

  const downloadBlob = (blob, fileName) => {
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fileName
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    URL.revokeObjectURL(url)
  }

  const exportEditorDocument = (title, text) => {
    const wordHtml = `
      <html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w="urn:schemas-microsoft-com:office:office:word" xmlns="http://www.w3.org/TR/REC-html40">
      <head><meta charset="utf-8"></head>
      <body style="font-family: sans-serif;">
        <pre style="white-space: pre-wrap; word-break: break-all;">${escapeHtml(text)}</pre>
      </body>
      </html>`
    downloadBlob(new Blob([wordHtml], { type: 'application/msword' }), `[最新]_${title}.doc`)
  }

  const downloadSkillFile = async (params, fileName = 'download') => {
    const blob = await request.get('/skills/file/download', { params, responseType: 'blob' })
    downloadBlob(blob, fileName)
  }

  const handleDownloadAgentFile = (actionResult) => {
    if (!actionResult) return
    if (actionResult.url) {
      openSafeUrl(actionResult.url)
      return
    }
    if (actionResult.type === 'ppt') {
      if (actionResult.htmlContent) {
        const fullHtml = `<!DOCTYPE html><html><head><meta charset="utf-8">
          <title>${escapeHtml(actionResult.title || 'PPT')}</title>
          <style>body{font-family:'Microsoft YaHei',sans-serif;background:#f0f0f0;padding:50px}.slide{background:#fff;padding:40px;margin-bottom:20px;border-radius:8px;box-shadow:0 2px 10px rgba(0,0,0,.1)}h1{color:#3370ff}p{line-height:1.6;color:#333}pre{background:#f8f8f8;padding:10px;border-radius:4px}</style>
          </head><body>${sanitizeEditorHtml(actionResult.htmlContent)}</body></html>`
        downloadBlob(new Blob([fullHtml], { type: 'text/html' }), `${actionResult.title || 'PPT'}.html`)
      } else if (actionResult.fileName) {
        downloadSkillFile({ objectName: actionResult.fileName }, actionResult.fileName)
          .catch(() => ElMessage.error('下载失败'))
      }
      return
    }
    if (actionResult.type === 'doc') {
      const objectName = actionResult.objectName || actionResult.fileName
      if (!objectName) return ElMessage.warning('无法获取文件名')
      downloadSkillFile({ bucket: 'doc-ai', objectName }, objectName)
        .catch(() => ElMessage.error('下载失败'))
      return
    }
    if (actionResult.fileName) {
      ElMessage.success(`正在下载：${actionResult.fileName}`)
      downloadSkillFile({ objectName: actionResult.fileName }, actionResult.fileName)
        .catch(() => ElMessage.error('下载失败'))
    } else {
      ElMessage.info('该任务没有返回可下载的文件')
    }
  }

  return { exportEditorDocument, handleDownloadAgentFile }
}
