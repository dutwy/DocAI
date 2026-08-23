import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiApi } from '../../../api/ai'

export function useRagIndexing(documentId, getEditorText) {
  const ragDrawerVisible = ref(false)
  const strategyMap = ref({ AUTO: '智能自动分段' })
  const selectedStrategy = ref('AUTO')
  const segments = ref([])
  const indexing = ref(false)

  const openRagSettings = async () => {
    ragDrawerVisible.value = true
    try {
      const [strategyResponse, segmentResponse] = await Promise.all([
        aiApi.getSegmentStrategies(),
        aiApi.getDocumentSegments(documentId)
      ])
      if (strategyResponse.data) strategyMap.value = strategyResponse.data
      if (segmentResponse.data) segments.value = segmentResponse.data
    } catch {
      strategyMap.value = {
        FIXED_LENGTH: '固定长度 (1000字符/10%重叠)',
        CHAPTER: '智能按章节分段',
        SEMANTIC: '语义分割 (句子级别)'
      }
    }
  }

  const doIndexDocument = async () => {
    const content = getEditorText() || ''
    if (content.length < 10) return ElMessage.warning('文档内容过少，无法分段')
    indexing.value = true
    try {
      await aiApi.indexWithSegment(documentId, content, selectedStrategy.value)
      ElMessage.success('向量化重构完成！')
      const response = await aiApi.getDocumentSegments(documentId)
      segments.value = response.data || []
    } catch {
      ElMessage.error('分段策略执行失败')
      segments.value = [
        { content: content.substring(0, 500) + '...' },
        { content: content.substring(500, 1000) + '...' }
      ]
    } finally {
      indexing.value = false
    }
  }

  return {
    ragDrawerVisible,
    strategyMap,
    selectedStrategy,
    segments,
    indexing,
    openRagSettings,
    doIndexDocument
  }
}
