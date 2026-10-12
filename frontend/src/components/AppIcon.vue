<script setup lang="ts">
defineProps<{ name: string }>()
</script>

<template>
  <svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="name === 'dashboard'"><path d="M4 13h6V4H4zM14 20h6v-9h-6zM4 20h6v-3H4zM14 7h6V4h-6z" /></template>
    <template v-else-if="name === 'users'"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" /></template>
    <template v-else-if="name === 'questions'"><path d="M5 4h14a2 2 0 0 1 2 2v13a1 1 0 0 1-1.45.89L16 18H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" /><path d="M9.1 9a3 3 0 1 1 5.55 1.56c-.84 1.1-2.15 1.17-2.15 2.44M12.5 15.5h.01" /></template>
    <template v-else-if="name === 'exams'"><path d="M8 2v4M16 2v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14H3V6a2 2 0 0 1 2-2Z" /><path d="m8 15 2 2 5-5" /></template>
    <template v-else-if="name === 'results'"><path d="M4 20V10M10 20V4M16 20v-7M22 20H2" /></template>
    <template v-else-if="name === 'practice'"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z" /><path d="m9 10 2 2 4-4" /></template>
    <template v-else-if="name === 'take-exam'"><path d="M9 5H6a2 2 0 0 0-2 2v13h16V7a2 2 0 0 0-2-2h-3" /><rect x="9" y="2" width="6" height="6" rx="1" /><path d="m8 13 2 2 5-5" /></template>
    <template v-else-if="name === 'grades'"><circle cx="12" cy="8" r="6" /><path d="M8.5 13 7 22l5-3 5 3-1.5-9" /><path d="m9.5 8 1.7 1.7L15 6" /></template>
    <template v-else-if="name === 'wrong'"><path d="M4 4h16v16H4z" /><path d="m9 9 6 6M15 9l-6 6" /></template>
    <template v-else-if="name === 'audit'"><path d="M9 5H6a2 2 0 0 0-2 2v13h16V7a2 2 0 0 0-2-2h-3" /><rect x="9" y="2" width="6" height="6" rx="1" /><path d="M8 12h8M8 16h5" /></template>
    <template v-else><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21H9.6v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3V9.6h.1A1.7 1.7 0 0 0 4.6 8.5a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.1a1.7 1.7 0 0 0 1.1 1.5 1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.14.38.35.72.6 1 .3.3.7.45 1.1.4h.1v4h-.1a1.7 1.7 0 0 0-1.7.6Z" /></template>
  </svg>
</template>
