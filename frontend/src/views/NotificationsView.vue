<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '../api'
type Notice = { id: number; examId: number; title: string; startTime: string | null; endTime: string | null; examStatus: string; sentAt: string; readAt: string | null; canOpen: boolean }
type Inbox = { items: Notice[]; unreadCount: number; total: number; page: number; totalPages: number }
const inbox = ref<Inbox | null>(null), loading = ref(true), error = ref(''), busy = ref<number | null>(null), page = ref(0)
const date = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未设置'
async function load() {
  loading.value = true; error.value = ''
  try { inbox.value = await api<Inbox>(`/api/notifications?page=${page.value}`) }
  catch (cause) { inbox.value = null; error.value = cause instanceof Error ? cause.message : '通知加载失败，请重试' }
  finally { loading.value = false }
}
async function read(item: Notice) {
  busy.value = item.id; error.value = ''
  try { const updated = await api<Notice>(`/api/notifications/${item.id}/read`, { method: 'POST' }); Object.assign(item, updated); if (inbox.value) inbox.value.unreadCount = Math.max(0, inbox.value.unreadCount - 1); window.dispatchEvent(new Event('notifications-change')) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '未能标记已读，请重试' }
  finally { busy.value = null }
}
function changePage(value: number) { page.value = value; void load() }
onMounted(load)
</script>
<template>
  <div class="page-head notifications-head"><div><h1>站内通知</h1><p>考试提醒{{ inbox ? ` · ${inbox.unreadCount} 条未读` : '' }}</p></div><button class="secondary-button" :disabled="loading || busy !== null" @click="load">刷新通知</button></div>
  <p v-if="error" class="notice error" role="alert">{{ error }} <button class="link-button" @click="load">重新加载</button></p>
  <section v-if="loading" class="panel"><div class="empty" role="status">正在加载通知…</div></section>
  <section v-else-if="inbox && !inbox.items.length" class="panel"><div class="empty"><h2>暂无通知</h2><p>管理员发送考试提醒后，会显示在这里。</p><RouterLink to="/my-exams" class="secondary-button">查看我的考试</RouterLink></div></section>
  <section v-else-if="inbox" class="panel notification-list" aria-label="考试提醒列表">
    <article v-for="item in inbox.items" :key="item.id" class="notification-item" :class="{unread: !item.readAt}"><div><span class="status-pill" :class="item.readAt ? 'success' : 'info'">{{ item.readAt ? '已读' : '未读' }}</span><time>{{ date(item.sentAt) }}</time><h2>{{ item.title }}</h2><p>请查看考试安排，按时参加考试。</p><p>开始：{{ date(item.startTime) }}<br />截止：{{ date(item.endTime) }}</p><p v-if="!item.canOpen" class="notification-ended">{{ item.examStatus === 'CANCELLED' ? '考试已取消，此提醒已失效。' : '考试已截止或当前不可参加，可前往我的考试查看安排。' }}</p></div><div class="notification-actions"><RouterLink v-if="item.canOpen" :to="`/my-exams?examId=${item.examId}`" class="primary-button">查看考试</RouterLink><button v-if="!item.readAt" class="secondary-button" :disabled="busy !== null" @click="read(item)">{{ busy === item.id ? '正在标记…' : '标记已读' }}</button></div></article>
    <div v-if="inbox.totalPages > 1" class="notification-pagination"><button class="secondary-button" :disabled="!page" @click="changePage(page - 1)">上一页</button><span>{{ page + 1 }} / {{ inbox.totalPages }}</span><button class="secondary-button" :disabled="page + 1 >= inbox.totalPages" @click="changePage(page + 1)">下一页</button></div>
  </section>
</template>
<style scoped>
.notification-list{padding:0 24px}.notification-item{padding:24px 0;border-bottom:1px solid #e1e9ef;display:flex;justify-content:space-between;gap:24px}.notification-item:last-child{border-bottom:0}.notification-item>div:first-child{min-width:0}.notification-item h2{font-size:18px;line-height:1.7;margin:12px 0 8px;overflow-wrap:anywhere;color:#173550}.notification-item p{color:#667f92;font-size:13px;line-height:1.8;margin:6px 0}.notification-item time{font-size:11px;color:#667f92;margin-left:12px}.notification-item.unread h2{font-weight:800}.notification-actions{display:flex;align-items:center;gap:10px;flex-shrink:0}.notification-actions .secondary-button,.notification-pagination .secondary-button{margin:0}.notification-item .notification-ended{color:#9b6526}.notification-pagination{display:flex;align-items:center;justify-content:center;gap:14px;padding:18px;font-size:13px}button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:760px){.notification-list{padding:0 18px}.notification-item{flex-direction:column;gap:12px}.notification-actions{flex-wrap:wrap}.notification-actions>*{flex:1;text-align:center}.notification-item time{display:block;margin:8px 0 0}}
</style>
