<template>
  <div v-loading="!hasContent" class="ppt-runtime">
    <iframe
      v-if="hasContent"
      class="ppt-frame"
      :srcdoc="pptSource"
      sandbox="allow-scripts allow-presentation"
      referrerpolicy="no-referrer"
      title="PPT 预览"
    ></iframe>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'

const hasContent = ref(false)
const pptSource = ref('')

onMounted(() => {
  // 从 sessionStorage 拿到之前存好的PPT 源码
  const html = sessionStorage.getItem('temp_ppt_render_source')
  if (html) {
    pptSource.value = html
    hasContent.value = true
  } else {
    window.close() // 没内容就关掉
  }
})
</script>

<style scoped>
.ppt-runtime,
.ppt-frame {
  width: 100%;
  height: 100vh;
  margin: 0;
  border: 0;
}
</style>
