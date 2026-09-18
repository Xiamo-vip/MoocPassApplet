<template>
  <view class="page-container">
    <mooc-nav-bar title="MoocPass" :show-back="true">
      <template #right>
        <view class="nav-icon-btn" @tap="refreshData">
          <x-icon :name="isRefreshing ? 'spinner' : 'refresh'" :size="36" color="#475569" />
        </view>
      </template>
    </mooc-nav-bar>

    <scroll-view
      scroll-y
      class="content-scroll"
      refresher-enabled
      :refresher-triggered="isPullRefreshing"
      @refresherrefresh="onPullRefresh"
    >
      <view class="hero-card">
        <view class="hero-bg-glow" />
        <view class="hero-content">
          <view class="hero-header">
            <view class="hero-badge">
              <x-icon name="sparkles" :size="24" color="#4F46E5" />
              <text class="hero-badge-text">网课助手</text>
            </view>
            <text class="user-greeting">{{ user ? user.nickname : '欢迎使用' }}</text>
          </view>
          
          <view class="stats-grid">
            <view class="stat-item" @tap="goPage('/moocpass/accounts/accounts')">
              <text class="stat-num">{{ accounts.length }}</text>
              <text class="stat-label">已绑平台</text>
            </view>
            <view class="stat-item" @tap="goPage('/moocpass/courses/courses')">
              <text class="stat-num">{{ courses.length }}</text>
              <text class="stat-label">同步课程</text>
            </view>
            <view class="stat-item" @tap="goPage('/moocpass/task/list')">
              <text class="stat-num stat-num--active">{{ activeTasks.length }}</text>
              <text class="stat-label">进行中任务</text>
            </view>
            <view class="stat-item" @tap="goPage('/moocpass/answer/models')">
              <text class="stat-num">{{ profiles.length }}</text>
              <text class="stat-label">答题模型</text>
            </view>
          </view>
        </view>
      </view>

      <view class="quick-actions-grid">
        <view class="action-card" @tap="goPage('/moocpass/accounts/accounts')">
          <view class="action-icon-box bg-blue">
            <x-icon name="shield-check" :size="40" color="#2563EB" />
          </view>
          <text class="action-title">平台账号</text>
        </view>
        <view class="action-card" @tap="goPage('/moocpass/courses/courses')">
          <view class="action-icon-box bg-indigo">
            <x-icon name="book" :size="40" color="#4F46E5" />
          </view>
          <text class="action-title">选课配置</text>
        </view>
        <view class="action-card" @tap="goPage('/moocpass/task/list')">
          <view class="action-icon-box bg-amber">
            <x-icon name="sliders" :size="40" color="#D97706" />
          </view>
          <text class="action-title">任务监控</text>
        </view>
        <view class="action-card" @tap="goPage('/moocpass/answer/models')">
          <view class="action-icon-box bg-purple">
            <x-icon name="cpu" :size="40" color="#9333EA" />
          </view>
          <text class="action-title">答题模型</text>
        </view>
      </view>

      <view class="section-header">
        <view class="section-title-wrap">
          <text class="section-title">进行中任务</text>
          <text class="section-count" v-if="activeTasks.length > 0">({{ activeTasks.length }})</text>
        </view>
        <view class="section-more" @tap="goPage('/moocpass/task/list')">
          <text class="more-text">全部任务</text>
          <x-icon name="chevron-right" :size="28" color="#64748B" />
        </view>
      </view>

      <view v-if="activeTasks.length === 0" class="empty-card" @tap="goPage('/moocpass/courses/courses')">
        <view class="empty-icon-circle">
          <x-icon name="play" :size="44" color="#94A3B8" />
        </view>
        <text class="empty-title">暂无进行中任务</text>
      </view>

      <view v-else class="task-list">
        <view
          v-for="task in activeTasks"
          :key="task.id"
          class="task-card"
          @tap="goTaskDetail(task.id)"
        >
          <view class="task-top">
            <view class="task-title-wrap">
              <text class="task-course-name">{{ task.courseName || '网课任务 #' + task.id }}</text>
              <view class="status-chip" :class="'chip--' + task.state.toLowerCase()">
                <text class="chip-text">{{ formatState(task.state) }}</text>
              </view>
            </view>
            <view class="task-action-btn" @tap.stop="toggleTask(task)">
              <x-icon
                :name="task.state === 'RUNNING' ? 'pause' : 'play'"
                :size="32"
                :color="task.state === 'RUNNING' ? '#D97706' : '#059669'"
              />
            </view>
          </view>
          
          <view class="task-step-info">
            <x-icon name="activity" :size="24" color="#64748B" />
            <text class="task-step-text">{{ task.currentStep || '正在初始化任务...' }}</text>
          </view>

          <view class="progress-bar-bg">
            <view class="progress-bar-fill" :style="{ width: (task.progress || 0) + '%' }" />
          </view>
          <view class="task-bottom">
            <text class="progress-text">处理进度 {{ task.progress || 0 }}%</text>
            <text class="platform-progress-text" v-if="task.platformProgress !== null">
              平台确认 {{ task.platformProgress }}%
            </text>
          </view>
        </view>
      </view>

      <view class="section-header">
        <view class="section-title-wrap">
          <text class="section-title">我的课程</text>
          <text class="section-count" v-if="courses.length > 0">({{ courses.length }})</text>
        </view>
        <view class="section-more" @tap="goPage('/moocpass/courses/courses')">
          <text class="more-text">查看全部</text>
          <x-icon name="chevron-right" :size="28" color="#64748B" />
        </view>
      </view>

      <view v-if="courses.length === 0" class="empty-card" @tap="goPage('/moocpass/accounts/accounts')">
        <view class="empty-icon-circle">
          <x-icon name="book" :size="44" color="#94A3B8" />
        </view>
        <text class="empty-title">尚未同步课程</text>
      </view>

      <view v-else class="course-grid">
        <view
          v-for="course in recentCourses"
          :key="course.id"
          class="course-card"
          @tap="goCourseDetail(course.id)"
        >
          <view class="course-cover-box">
            <image
              v-if="course.coverUrl"
              class="course-cover-img"
              :src="course.coverUrl"
              mode="aspectFill"
              lazy-load
            />
            <view v-else class="course-cover-placeholder">
              <x-icon name="book" :size="36" color="#94A3B8" />
            </view>
            <view class="course-badge" v-if="course.teacher">
              <text class="course-badge-text">{{ course.teacher }}</text>
            </view>
          </view>
          <text class="course-name">{{ course.name }}</text>
          <view class="course-progress-row">
            <view class="progress-bar-bg mini">
              <view class="progress-bar-fill" :style="{ width: (course.platformProgress || 0) + '%' }" />
            </view>
            <text class="course-progress-num">{{ course.platformProgress !== null && course.platformProgress !== undefined ? course.platformProgress + '%' : '0%' }}</text>
          </view>
        </view>
      </view>

      <view class="bottom-spacer" />
    </scroll-view>

    <mooc-tab-bar current="overview" />
  </view>
</template>

<script>
import MoocNavBar from '../components/MoocNavBar.vue';
import MoocTabBar from '../components/MoocTabBar.vue';
import XIcon from '../../components/XIcon.vue';
import api, { getUser, isAuthenticated } from '../api/client.js';

export default {
  name: 'MoocOverview',
  components: { MoocNavBar, MoocTabBar, XIcon },
  data() {
    return {
      user: null,
      accounts: [],
      courses: [],
      activeTasks: [],
      profiles: [],
      isRefreshing: false,
      isPullRefreshing: false
    };
  },
  computed: {
    recentCourses() {
      const list = Array.isArray(this.courses) ? this.courses : [];
      return list.slice(0, 4);
    }
  },
  onShow() {
    this.initSession();
  },
  methods: {
    async initSession() {
      if (!isAuthenticated()) {
        try {
          uni.showLoading({ title: '连接中...' });
          await api.login();
          uni.hideLoading();
        } catch (err) {
          uni.hideLoading();
          uni.showToast({ title: err?.message || '登录失败', icon: 'none' });
          return;
        }
      }
      this.user = getUser();
      this.refreshData();
    },
    async refreshData() {
      this.isRefreshing = true;
      try {
        const [accs, crss, tsks, profs] = await Promise.allSettled([
          api.getAccounts(),
          api.getCourses({ page: 1 }),
          api.getTasks({ page: 1 }),
          api.getAnswerProfiles()
        ]);
        if (accs.status === 'fulfilled') this.accounts = Array.isArray(accs.value) ? accs.value : (accs.value?.items || []);
        if (crss.status === 'fulfilled') this.courses = Array.isArray(crss.value) ? crss.value : (crss.value?.items || []);
        if (tsks.status === 'fulfilled') {
          const list = Array.isArray(tsks.value) ? tsks.value : (tsks.value?.items || []);
          this.activeTasks = list.filter((t) => ['QUEUED', 'RUNNING', 'VERIFYING', 'PAUSING', 'WAITING_AUTH', 'WAITING_USER', 'WAITING_QUOTA', 'RETRY_WAIT'].includes(t.state));
        }
        if (profs.status === 'fulfilled') this.profiles = Array.isArray(profs.value) ? profs.value : (profs.value?.items || []);
      } catch (_) {}
      this.isRefreshing = false;
      this.isPullRefreshing = false;
    },
    onPullRefresh() {
      this.isPullRefreshing = true;
      this.refreshData();
    },
    goPage(url) {
      uni.navigateTo({ url });
    },
    goCourseDetail(id) {
      uni.navigateTo({ url: `/moocpass/courses/detail?id=${id}` });
    },
    goTaskDetail(id) {
      uni.navigateTo({ url: `/moocpass/task/detail?id=${id}` });
    },
    async toggleTask(task) {
      const action = task.state === 'RUNNING' ? 'pause' : 'resume';
      try {
        await api.taskAction(task.id, action);
        uni.showToast({ title: action === 'pause' ? '已请求暂停' : '已恢复任务', icon: 'none' });
        this.refreshData();
      } catch (err) {
        uni.showToast({ title: err?.message || '操作失败', icon: 'none' });
      }
    },
    formatState(state) {
      const map = {
        QUEUED: '排队中',
        RUNNING: '运行中',
        VERIFYING: '核验中',
        PAUSING: '暂停中',
        PAUSED: '已暂停',
        WAITING_AUTH: '凭证失效',
        WAITING_USER: '待人工处理',
        WAITING_QUOTA: '额度不足',
        RETRY_WAIT: '等待重试',
        COMPLETED: '已完成',
        PARTIAL: '部分完成',
        FAILED: '执行失败',
        CANCELED: '已取消'
      };
      return map[state] || state;
    }
  }
};
</script>

<style scoped>
.page-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  box-sizing: border-box;
  background-color: #f8fafc;
}
.nav-icon-btn {
  width: 64rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.content-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 24rpx;
  box-sizing: border-box;
}
.hero-card {
  position: relative;
  background: linear-gradient(135deg, #4f46e5 0%, #3730a3 100%);
  border-radius: 32rpx;
  padding: 32rpx;
  color: #ffffff;
  overflow: hidden;
  box-shadow: 0 12rpx 36rpx rgba(79, 70, 229, 0.24);
  margin-bottom: 32rpx;
}
.hero-bg-glow {
  position: absolute;
  top: -50rpx;
  right: -50rpx;
  width: 240rpx;
  height: 240rpx;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.25) 0%, rgba(255, 255, 255, 0) 70%);
  border-radius: 999rpx;
}
.hero-content {
  position: relative;
  z-index: 1;
}
.hero-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 28rpx;
}
.hero-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  background: rgba(255, 255, 255, 0.92);
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  box-sizing: border-box;
}
.hero-badge-text {
  font-size: 22rpx;
  font-weight: 600;
  color: #4f46e5;
  line-height: 1;
}
.user-greeting {
  font-size: 26rpx;
  color: rgba(255, 255, 255, 0.9);
}
.stats-grid {
  display: flex;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(10px);
  border-radius: 24rpx;
  padding: 20rpx 12rpx;
}
.stat-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.stat-num {
  font-size: 38rpx;
  font-weight: 700;
  color: #ffffff;
}
.stat-num--active {
  color: #fde047;
}
.stat-label {
  font-size: 20rpx;
  color: rgba(255, 255, 255, 0.8);
  margin-top: 4rpx;
}
.section-title-row {
  margin: 12rpx 0 20rpx 4rpx;
}
.section-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #0f172a;
}
.quick-actions-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16rpx;
  margin-bottom: 32rpx;
}
.action-card {
  background: #ffffff;
  border-radius: 24rpx;
  padding: 20rpx 8rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.04);
  border: 1rpx solid rgba(226, 232, 240, 0.8);
}
.action-icon-box {
  width: 76rpx;
  height: 76rpx;
  border-radius: 20rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12rpx;
}
.bg-blue { background: #eff6ff; }
.bg-indigo { background: #eef2ff; }
.bg-amber { background: #fffbeb; }
.bg-purple { background: #faf5ff; }
.action-title {
  font-size: 24rpx;
  font-weight: 600;
  color: #1e293b;
}
.action-desc {
  font-size: 18rpx;
  color: #94a3b8;
  margin-top: 2rpx;
}
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 24rpx 4rpx 16rpx;
}
.section-title-wrap {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.section-count {
  font-size: 24rpx;
  color: #64748b;
  font-weight: 500;
}
.section-more {
  display: flex;
  align-items: center;
  gap: 4rpx;
}
.more-text {
  font-size: 24rpx;
  color: #64748b;
}
.empty-card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 40rpx 24rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  border: 1rpx dashed rgba(203, 213, 225, 0.9);
  margin-bottom: 24rpx;
}
.empty-icon-circle {
  width: 96rpx;
  height: 96rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16rpx;
}
.empty-title {
  font-size: 26rpx;
  color: #475569;
  font-weight: 600;
}
.empty-sub {
  font-size: 22rpx;
  color: #94a3b8;
  margin-top: 6rpx;
}
.task-list {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
  margin-bottom: 24rpx;
}
.task-card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 24rpx;
  box-shadow: 0 4rpx 20rpx rgba(15, 23, 42, 0.04);
  border: 1rpx solid rgba(226, 232, 240, 0.8);
}
.task-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
.task-title-wrap {
  display: flex;
  align-items: center;
  gap: 12rpx;
  flex: 1;
}
.task-course-name {
  font-size: 28rpx;
  font-weight: 600;
  color: #0f172a;
}
.status-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  font-size: 20rpx;
  font-weight: 600;
  box-sizing: border-box;
}
.chip--running { background: #dcfce7; color: #15803d; }
.chip--queued { background: #e0f2fe; color: #0369a1; }
.chip--verifying, .chip--retry_wait { background: #eef2ff; color: #4f46e5; }
.chip--pausing, .chip--paused { background: #fef3c7; color: #b45309; }
.chip--waiting_auth, .chip--waiting_user, .chip--waiting_quota { background: #fee2e2; color: #b91c1c; }
.chip-text {
  font-size: 20rpx;
  line-height: 1;
  text-align: center;
}
.task-action-btn {
  width: 60rpx;
  height: 60rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
}
.task-step-info {
  display: flex;
  align-items: center;
  gap: 8rpx;
  margin-bottom: 16rpx;
}
.task-step-text {
  font-size: 22rpx;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.progress-bar-bg {
  height: 12rpx;
  background: #e2e8f0;
  border-radius: 999rpx;
  overflow: hidden;
}
.progress-bar-bg.mini {
  height: 8rpx;
  flex: 1;
}
.progress-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #4f46e5 0%, #06b6d4 100%);
  border-radius: 999rpx;
  transition: width 0.3s ease;
}
.task-bottom {
  display: flex;
  justify-content: space-between;
  margin-top: 10rpx;
}
.progress-text {
  font-size: 20rpx;
  font-weight: 600;
  color: #4f46e5;
}
.platform-progress-text {
  font-size: 20rpx;
  color: #64748b;
}
.course-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16rpx;
  margin-bottom: 24rpx;
}
.course-card {
  background: #ffffff;
  border-radius: 24rpx;
  padding: 20rpx;
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.04);
  border: 1rpx solid rgba(226, 232, 240, 0.8);
  display: flex;
  flex-direction: column;
}
.course-cover-box {
  width: 100%;
  height: 180rpx;
  border-radius: 16rpx;
  overflow: hidden;
  position: relative;
  background: #f1f5f9;
  margin-bottom: 14rpx;
}
.course-cover-box .course-cover-img {
  width: 100%;
  height: 100%;
}
.course-cover-box .course-cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f1f5f9;
}
.course-cover-box .course-badge {
  position: absolute;
  left: 10rpx;
  bottom: 10rpx;
  background: rgba(15, 23, 42, 0.75);
  padding: 6rpx 14rpx;
  border-radius: 8rpx;
  margin-bottom: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
.course-cover-box .course-badge-text {
  font-size: 20rpx;
  color: #ffffff;
  line-height: 1;
}
.course-name {
  font-size: 26rpx;
  font-weight: 600;
  color: #0f172a;
  line-height: 1.3;
  height: 68rpx;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  margin-bottom: 16rpx;
}
.course-progress-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: auto;
}
.course-progress-num {
  font-size: 20rpx;
  font-weight: 600;
  color: #059669;
}
.bottom-spacer {
  height: 130rpx;
}
</style>
