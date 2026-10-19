<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
const route = useRoute(), count = ref<number | null>(null)
let generation = 0
async function load() { const request = ++generation; try { const result = await api<{unreadCount: number}>('/api/notifications'); if (request === generation) count.value = result.unreadCount } catch { if (request === generation) count.value = null } }
watch(() => route.fullPath, () => { if (!route.path.startsWith('/take/')) void load() }, { immediate: true })
window.addEventListener('notifications-change', load)
onBeforeUnmount(() => { generation++; window.removeEventListener('notifications-change', load) })
</script>
<template><RouterLink to="/notifications" class="notification-link">通知<span v-if="count" class="notification-badge" :aria-label="`${count}条未读`">{{ count > 99 ? '99+' : count }}</span></RouterLink></template>
<style scoped>.notification-link{display:inline-flex;gap:6px;align-items:center;text-decoration:none;color:#167d8d;font-size:13px;font-weight:700;min-height:44px;white-space:nowrap}.notification-badge{background:#167d8d;color:#fff;border-radius:20px;font-size:10px;min-width:19px;line-height:19px;text-align:center;padding:0 4px}</style>
