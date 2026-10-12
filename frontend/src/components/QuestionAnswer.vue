<script setup lang="ts">
import { computed } from 'vue'
import { decodeBlankAnswers, encodeBlankAnswers, fillBlankCount } from '../candidates/blankAnswers'
const props = withDefaults(defineProps<{
  modelValue: string
  type: string
  optionsJson?: string | null
  disabled?: boolean
  name?: string
  practice?: boolean
  blankCount?: number
  stem?: string
}>(), { disabled: false, name: 'answer' })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const options = computed<string[]>(() => {
  if (props.type === 'TRUE_FALSE') return ['正确', '错误']
  if (!['SINGLE_CHOICE', 'MULTIPLE_CHOICE'].includes(props.type)) return []
  try {
    const values: unknown = JSON.parse(props.optionsJson || '[]')
    if (!Array.isArray(values)) return []
    return values.map(value => typeof value === 'string' ? value : String(value?.text ?? value?.label ?? value?.value ?? ''))
  } catch { return [] }
})
const multiple = computed(() => props.type === 'MULTIPLE_CHOICE')
const longAnswer = computed(() => ['SHORT_ANSWER', 'CALCULATION', 'IMAGE_CASE', 'ESSAY'].includes(props.type))
const placeholder = computed(() => longAnswer.value ? '请分点作答，写清依据、步骤和结论' : '请填写你的答案')
const selected = computed(() => props.modelValue.split(/[,，、;；]+/).filter(Boolean))
const blanks = computed(() => {
  const saved = decodeBlankAnswers(props.modelValue)
  return Array.from({ length: fillBlankCount(props.blankCount, props.stem, props.modelValue) }, (_, index) => saved[index] || '')
})
function updateBlank(index: number, value: string) {
  const next = [...blanks.value]
  next[index] = value
  emit('update:modelValue', encodeBlankAnswers(next))
}
function updateOption(value: string, checked: boolean) {
  if (!multiple.value) { emit('update:modelValue', value); return }
  const next = new Set(selected.value)
  if (checked) next.add(value)
  else next.delete(value)
  emit('update:modelValue', options.value.filter(option => next.has(option)).join(','))
}
</script>

<template>
  <div v-if="options.length" class="answer-options">
    <label v-for="(option, index) in options" :key="index" class="answer-option"
      :class="{ selected: multiple ? selected.includes(option) : modelValue === option, disabled }">
      <input :type="multiple ? 'checkbox' : 'radio'" :name="name" :disabled="disabled"
        :checked="multiple ? selected.includes(option) : modelValue === option"
        @change="updateOption(option, ($event.target as HTMLInputElement).checked)">
      <span class="option-letter" v-if="type !== 'TRUE_FALSE'">{{ String.fromCharCode(65 + index) }}</span>
      <span>{{ option }}</span>
    </label>
    <small v-if="multiple" class="answer-hint">可选择多个选项，全部选对才得分。</small>
  </div>
  <fieldset v-else-if="type === 'FILL_BLANK'" class="blank-answer" :disabled="disabled" :aria-describedby="`${name}-blank-hint`">
    <legend>填写答案 · 共 {{ blanks.length }} 空</legend>
    <div class="blank-fields">
      <label v-for="(value, index) in blanks" :key="index" :for="`${name}-blank-${index}`">
        <span>第 {{ index + 1 }} 空</span>
        <input :id="`${name}-blank-${index}`" class="answer-input" type="text" :value="value"
          :name="`${name}-blank-${index}`" :placeholder="`填写第 ${index + 1} 空`" autocomplete="off"
          :maxlength="Math.max(1, Math.floor((10000 - 3 * blanks.length - 1) / (2 * blanks.length)))"
          @input="updateBlank(index, ($event.target as HTMLInputElement).value)">
      </label>
    </div>
    <small :id="`${name}-blank-hint`" class="answer-hint">每个框填写一个答案，不会的空可留空。{{ practice ? '全部空答对才记为答对本题。' : '每空均分，按答对比例计分，整题得分四舍五入取整数。' }}</small>
  </fieldset>
  <textarea v-else class="answer-input" :value="modelValue" :disabled="disabled" :rows="longAnswer ? 10 : 4"
    aria-label="填写答案" :placeholder="placeholder" maxlength="10000"
    @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"></textarea>
  <small v-if="longAnswer" class="answer-hint">{{ practice ? '提交后对照参考答案复习；主观题练习不自动判分，也不销号。' : '系统会自动保存；交卷后由超级管理员根据评分要点人工判卷。' }}</small>
</template>

<style scoped>
.blank-answer{min-width:0;margin:18px 0 0;padding:0;border:0}.blank-answer legend{padding:0;margin-bottom:12px;font-size:13px;font-weight:600;color:#49677c}.blank-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,180px),1fr));gap:14px}.blank-fields label{display:grid;gap:7px;min-width:0;font-size:13px;color:#49677c}.blank-fields input{box-sizing:border-box;min-width:0;padding:11px 12px}.blank-answer .answer-hint{display:block;margin-top:10px;line-height:1.8}.blank-answer:disabled input{background:#f0f3f6;color:#728497}
.answer-options{display:grid;gap:10px;margin:20px 0}.answer-option{display:flex;align-items:flex-start;gap:12px;padding:14px 16px;border:1px solid #dce5ed;border-radius:9px;cursor:pointer;font-size:14px;line-height:1.6;color:#314d63;background:#fbfcfd;transition:border-color .15s,background .15s}.answer-option.selected{border-color:#528eb4;background:#edf6fc}.answer-option.disabled{cursor:default}.answer-option input{margin-top:5px;accent-color:#0c4f80;flex-shrink:0}.option-letter{font-weight:700;color:#52758d}.answer-input{width:100%;font:inherit;padding:14px;border:1px solid #ccdce8;border-radius:9px;resize:vertical;line-height:1.8;color:#263d50;background:#fbfcfd}.answer-hint{color:#778b9d;font-size:12px}.answer-input:focus{outline:2px solid #bdd9ec;outline-offset:1px}
</style>
