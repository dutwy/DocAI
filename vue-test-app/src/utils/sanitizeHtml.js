import DOMPurify from 'dompurify'

const EDITOR_SANITIZE_OPTIONS = {
  USE_PROFILES: { html: true },
  FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'meta', 'link', 'base'],
  FORBID_ATTR: ['style', 'srcdoc'],
  ALLOW_DATA_ATTR: false
}

export function sanitizeEditorHtml(html) {
  return DOMPurify.sanitize(String(html || ''), EDITOR_SANITIZE_OPTIONS)
}
