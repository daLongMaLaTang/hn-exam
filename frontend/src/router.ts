import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn, currentUser, hasRole, refreshUser } from './api'
import LoginView from './views/LoginView.vue'
import DashboardView from './views/DashboardView.vue'
import UsersView from './views/UsersView.vue'
import QuestionsView from './views/QuestionsView.vue'
import ExamsView from './views/ExamsView.vue'
import ResultsView from './views/ResultsView.vue'
import PracticeView from './views/PracticeView.vue'
import CandidateExamsView from './views/CandidateExamsView.vue'
import TakeExamView from './views/TakeExamView.vue'
import MyGradesView from './views/MyGradesView.vue'
import GradeDetailView from './views/GradeDetailView.vue'
import AccountView from './views/AccountView.vue'
import ProfileView from './views/ProfileView.vue'
import AuditView from './views/AuditView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/', component: DashboardView },
    { path: '/users/:id/profile', component: ProfileView, meta: {roles:['SYSTEM_ADMIN','TRAINING_ADMIN','DEPARTMENT_LEADER']} },
    { path: '/users', component: UsersView, meta: {roles:['SYSTEM_ADMIN','TRAINING_ADMIN','DEPARTMENT_LEADER']} },
    { path: '/questions', component: QuestionsView, meta: {roles:['SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST']} },
    { path: '/exams', component: ExamsView, meta: {roles:['SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST']} },
    { path: '/results', component: ResultsView, meta: {roles:['SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST']} },
    { path: '/practice', component: PracticeView, meta: {roles:['CANDIDATE']} },
    { path: '/wrong-questions', component: PracticeView, props: {wrong:true}, meta: {roles:['CANDIDATE']} },
    { path: '/exam-wrong-questions', component: PracticeView, props: {wrong:true, examWrong:true}, meta: {roles:['CANDIDATE']} },
    { path: '/my-exams', component: CandidateExamsView, meta: {roles:['CANDIDATE']} },
    { path: '/take/:attemptId', component: TakeExamView, meta: {roles:['CANDIDATE']} },
    { path: '/my-grades', component: MyGradesView, meta: {roles:['CANDIDATE']} },
    { path: '/grades/:attemptId', component: GradeDetailView, meta: {roles:['CANDIDATE']} },
    { path: '/account', component: AccountView },
    { path: '/audit', component: AuditView, meta:{roles:['SYSTEM_ADMIN']} },
    { path: '/:pathMatch(.*)*', redirect:'/' }
  ]
})

router.beforeEach(async (to) => {
  if (!to.meta.public && !isLoggedIn()) return '/login'
  if (to.path === '/login' && isLoggedIn()) return '/'
  if (!to.meta.public) {
    try { await refreshUser() } catch { return '/login' }
    if (currentUser()?.mustChangePassword && to.path !== '/account') return '/account'
    if (to.meta.roles && !hasRole(...to.meta.roles as string[])) return '/'
  }
})

export default router
