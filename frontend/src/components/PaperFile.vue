<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ options?: string | null }>()
const attachment = computed(() => {
  try {
    const file = JSON.parse(props.options || '{}')?.paper
    return typeof file === 'string' && /^data:(image\/(png|jpeg)|application\/pdf);base64,/.test(file) ? file : ''
  } catch { return '' }
})
</script>
<template><div v-if="attachment" class="paper-file">
  <img v-if="attachment.startsWith('data:image/')" :src="attachment" alt="计算题题图" />
  <template v-else><a :href="attachment" download="试卷.pdf">下载 PDF 试卷</a></template>
</div></template>
<style scoped>.paper-file img{max-width:100%;height:auto}.paper-file iframe{width:100%;height:55vh;border:1px solid #cedce1}.paper-file a{display:inline-block;padding:8px 0}</style>

