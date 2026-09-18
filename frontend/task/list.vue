<template>
  <view class="page-container">
    <mooc-nav-bar title="任务中心" :show-back="true" bg-color="#F8F9FC">
      <template #right>
        <view class="nav-icon-btn" @tap="fetchTasks">
          <x-icon :name="isRefreshing ? 'spinner' : 'refresh'" :size="36" color="#475569" />
        </view>
      </template>
    </mooc-nav-bar>

    <!-- 状态分类标签 (Material 3 Filter Chips) -->
    <view class="tabs-header">
      <scroll-view scroll-x class="tabs-scroll" :show-scrollbar="false">
        <view class="tabs-inner">
          <view
            v-for="t in stateTabs"
            :key="t.key"
            class="m3-tab-chip"
            :class="{ 'm3-tab-chip--active': activeTab === t.key }"
            @tap="switchTab(t.key)"
          >
            <text class="tab-label">{{ t.label }}</text>
          </view>
        </view>
      </scroll-view>
    </view>

    <scroll-view
      scroll-y
      class="content-scroll"
      refresher-enabled
      :refresher-triggered="isPullRefreshing"
      @refresherrefresh="onPullRefresh"
    >
      <!-- 批量操作药丸 (无冗余文字) -->
      <view class="batch-action-row" v-if="hasActiveTasks">
        <view class="batch-action-btn" @tap="batchAction('pause')">
          <x-icon name="pause" :size="24" color="#D97706" />
          <text class="batch-btn-text text-amber">全部暂停</text>
        </view>
        <view class="batch-action-btn" @tap="batchAction('resume')">
          <x-icon name="play" :size="24" color="#059669" />
          <text class="batch-btn-text text-green">全部恢复</text>
        </view>
      </view>

      <view v-if="isLoading && tasks.length === 0" class="skeleton-wrap">
        <view v-for="i in 3" :key="i" class="skeleton-card" />
      </view>

      <view v-else-if="filteredTasks.length === 0" class="empty-state">
        <view class="empty-icon-box">
          <x-icon name="sliders" :size="48" color="#94A3B8" />
        </view>
        <text class="empty-title">暂无任务</text>
        <button class="empty-btn" @tap="goCourses">
          <text class="empty-btn-text">前往选课</text>
        </button>
      </view>

      <view v-else class="task-list">
        <view
          v-for="task in filteredTasks"
          :key="task.id"
          class="m3-task-card"
          @tap="goDetail(task.id)"
        >
          <view class="card-header">
            <text class="course-name">{{ task.courseName || '任务 #' + task.id }}</text>
            <view class="status-chip" :class="'chip--' + task.state.toLowerCase()">
              <text class="chip-text">{{ formatState(task.state) }}</text>
            </view>
          </view>

          <view class="step-row" v-if="task.currentStep">
            <text class="step-text">{{ task.currentStep }}</text>
          </view>

          <view class="error-banner" v-if="task.errorMessage">
            <x-icon name="warning" :size="24" color="#DC2626" />
            <text class="error-text">{{ task.errorMessage }}</text>
          </view>

          <view class="progress-wrap">
            <view class="progress-track">
              <view class="progress-bar" :style="{ width: (task.progress || 0) + '%' }" />
            </view>
            <view class="progress-labels">
              <text class="progress-val">{{ task.progress || 0 }}%</text>
              <text class="platform-val" v-if="task.platformProgress !== null">
                平台 {{ task.platformProgress }}%
              </text>
            </view>
          </view>

          <view class="card-footer">
            <view class="action-buttons">
              <view
                class="tonal-btn"
                v-if="task.state === 'RUNNING'"
                @tap.stop="doAction(task, 'pause')"
              >
                <x-icon name="pause" :size="24" color="#D97706" />
                <text class="tonal-text text-amber">暂停</text>
              </view>
              <view
                class="tonal-btn"
                v-if="['PAUSED', 'PAUSING'].includes(task.state)"
                @tap.stop="doAction(task, 'resume')"
              >
                <x-icon name="play" :size="24" color="#059669" />
                <text class="tonal-text text-green">恢复</text>
              </view>
              <view
                class="tonal-btn"
                v-if="['FAILED', 'PARTIAL'].includes(task.state)"
                @tap.stop="doAction(task, 'retry')"
              >
                <x-icon name="refresh" :size="24" color="#2563EB" />
                <text class="tonal-text text-blue">重试</text>
              </view>
              <view
                class="tonal-btn text-danger-bg"
                v-if="['QUEUED', 'WAITING_AUTH', 'WAITING_USER', 'WAITING_QUOTA', 'RETRY_WAIT'].includes(task.state)"
                @tap.stop="doAction(task, 'cancel')"
              >
                <x-icon name="close" :size="24" color="#DC2626" />
                <text class="tonal-text text-danger">取消</text>
              </view>
            </view>

            <view class="detail-btn">
              <text class="detail-text">详情</text>
              <x-icon name="chevron-right" :size="26" color="#4F46E5" />
            </view>
          </view>
        </view>
      </view>

      <view class="bottom-spacer" />
    </scroll-view>

    <mooc-tab-bar current="tasks" />
  </view>
</template>

<script>
import MoocNavBar from '../components/MoocNavBar.vue';
import MoocTabBar from '../components/MoocTabBar.vue';
import XIcon from '../../components/XIcon.vue';
import api from '../api/client.js';

export default {
  name: 'MoocTaskList',
  components: { MoocNavBar, MoocTabBar, XIcon },
  data() {
    return {
      tasks: [],
      activeTab: 'ALL',
      isLoading: false,
      isRefreshing: false,
      isPullRefreshing: false,
      stateTabs: [
        { key: 'ALL', label: '全部' },
        { key: 'RUNNING', label: '运行中' },
        { key: 'QUEUED', label: '排队中' },
        { key: 'PAUSED', label: '已暂停' },
        { key: 'WAITING', label: '待处理' },
        { key: 'COMPLETED', label: '已完成' },
        { key: 'FAILED', label: '失败/异常' }
      ]
    };
  },
  computed: {
    filteredTasks() {
      const list = Array.isArray(this.tasks) ? this.tasks : [];
      if (this.activeTab === 'ALL') return list;
      if (this.activeTab === 'WAITING') {
        return list.filter((t) => ['WAITING_AUTH', 'WAITING_USER', 'WAITING_QUOTA', 'RETRY_WAIT'].includes(t.state));
      }
      return list.filter((t) => t.state === this.activeTab);
    },
    hasActiveTasks() {
      const list = Array.isArray(this.tasks) ? this.tasks : [];
      return list.some((t) => ['RUNNING', 'PAUSED', 'PAUSING', 'QUEUED'].includes(t.state));
    }
  },
  onShow() {
    this.fetchTasks();
  },
  methods: {
    async fetchTasks() {
      this.isLoading = true;
      this.isRefreshing = true;
      try {
        const res = await api.getTasks({ page: 1 });
        this.tasks = Array.isArray(res) ? res : (res?.items || []);
      } catch (err) {
        uni.showToast({ title: err?.message || '获取任务失败', icon: 'none' });
      }
      this.isLoading = false;
      this.isRefreshing = false;
      this.isPullRefreshing = false;
    },
    onPullRefresh() {
      this.isPullRefreshing = true;
      this.fetchTasks();
    },
    switchTab(tabKey) {
      this.activeTab = tabKey;
    },
    goDetail(id) {
      uni.navigateTo({ url: `/moocpass/task/detail?id=${id}` });
    },
    goCourses() {
      uni.navigateTo({ url: '/moocpass/courses/courses' });
    },
    async doAction(task, action) {
      try {
        await api.taskAction(task.id, action);
        uni.showToast({ title: '操作已发送', icon: 'success' });
        this.fetchTasks();
      } catch (err) {
        uni.showToast({ title: err?.message || '操作失败', icon: 'none' });
      }
    },
    async batchAction(action) {
      const list = Array.isArray(this.tasks) ? this.tasks : [];
      const targets = list.filter((t) => action === 'pause' ? t.state === 'RUNNING' : ['PAUSED', 'PAUSING'].includes(t.state));
      if (targets.length === 0) {
        uni.showToast({ title: '当前无可批量操作的任务', icon: 'none' });
        return;
      }
      uni.showLoading({ title: '批量处理中...' });
      for (const t of targets) {
        try {
          await api.taskAction(t.id, action);
        } catch (_) {}
      }
      uni.hideLoading();
      uni.showToast({ title: '批量操作完成', icon: 'success' });
      this.fetchTasks();
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
  background-color: #f8f9fc;
}

.nav-icon-btn {
  width: 64rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 状态分类栏 (M3 Filter Chips) */
.tabs-header {
  flex-shrink: 0;
  background: #ffffff;
  border-bottom: 1rpx solid rgba(0, 0, 0, 0.04);
  padding: 16rpx 28rpx;
}

.tabs-scroll {
  white-space: nowrap;
}

.tabs-inner {
  display: flex;
  gap: 12rpx;
}

.m3-tab-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 8rpx 24rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  flex-shrink: 0;
  transition: all 0.18s ease;
  box-sizing: border-box;
}

.m3-tab-chip--active {
  background: #4f46e5;
  box-shadow: 0 2rpx 8rpx rgba(79, 70, 229, 0.25);
}

.tab-label {
  font-size: 22rpx;
  color: #64748b;
  font-weight: 500;
  line-height: 1;
}

.m3-tab-chip--active .tab-label {
  color: #ffffff;
  font-weight: 600;
}

.content-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 20rpx 28rpx;
  box-sizing: border-box;
}

/* 批量操作按钮 */
.batch-action-row {
  display: flex;
  gap: 16rpx;
  margin-bottom: 18rpx;
}

.batch-action-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  height: 64rpx;
  background: #ffffff;
  border-radius: 20rpx;
  border: 1rpx solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 2rpx 8rpx rgba(15, 23, 42, 0.02);
}

.batch-btn-text {
  font-size: 22rpx;
  font-weight: 600;
  line-height: 1;
}

.text-amber { color: #d97706; }
.text-green { color: #059669; }
.text-blue { color: #2563eb; }
.text-danger { color: #dc2626; }

/* 空状态 */
.empty-state {
  background: #ffffff;
  border-radius: 32rpx;
  padding: 64rpx 24rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  border: 1rpx solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.03);
}

.empty-icon-box {
  width: 96rpx;
  height: 96rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20rpx;
}

.empty-title {
  font-size: 26rpx;
  font-weight: 500;
  color: #64748b;
}

.empty-btn {
  margin-top: 24rpx;
  background: #4f46e5;
  border-radius: 999rpx;
  padding: 0 36rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.empty-btn-text {
  font-size: 24rpx;
  font-weight: 600;
  color: #ffffff;
}

/* 任务列表 */
.task-list {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.m3-task-card {
  background: #ffffff;
  border-radius: 32rpx;
  padding: 24rpx 28rpx;
  border: 1rpx solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.03);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
}

.course-name {
  flex: 1;
  font-size: 28rpx;
  font-weight: 600;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 状态标签 */
.status-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  flex-shrink: 0;
  box-sizing: border-box;
}

.chip--running { background: #dcfce7; color: #15803d; }
.chip--queued { background: #e0f2fe; color: #0369a1; }
.chip--verifying, .chip--retry_wait { background: #eef2ff; color: #4f46e5; }
.chip--pausing, .chip--paused { background: #fef3c7; color: #b45309; }
.chip--waiting_auth, .chip--waiting_user, .chip--waiting_quota { background: #fee2e2; color: #b91c1c; }
.chip--completed { background: #f1f5f9; color: #475569; }
.chip--failed, .chip--canceled { background: #fff1f2; color: #dc2626; }

.chip-text {
  font-size: 20rpx;
  font-weight: 600;
  line-height: 1;
  text-align: center;
}

.step-row {
  margin: 10rpx 0 12rpx;
}

.step-text {
  font-size: 22rpx;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}

.error-banner {
  display: flex;
  align-items: center;
  gap: 8rpx;
  background: #fff1f2;
  padding: 8rpx 14rpx;
  border-radius: 12rpx;
  margin-bottom: 12rpx;
}

.error-text {
  font-size: 20rpx;
  color: #dc2626;
  flex: 1;
}

/* 进度条 */
.progress-wrap {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  margin: 14rpx 0 18rpx;
}

.progress-track {
  height: 10rpx;
  background: #e2e8f0;
  border-radius: 999rpx;
  overflow: hidden;
}

.progress-bar {
  height: 100%;
  background: linear-gradient(90deg, #4f46e5 0%, #06b6d4 100%);
  border-radius: 999rpx;
}

.progress-labels {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.progress-val {
  font-size: 20rpx;
  font-weight: 600;
  color: #4f46e5;
}

.platform-val {
  font-size: 20rpx;
  color: #94a3b8;
}

/* 卡片操作与跳转 */
.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 1rpx solid #f1f5f9;
  padding-top: 18rpx;
}

.action-buttons {
  display: flex;
  gap: 12rpx;
}

.tonal-btn {
  display: flex;
  align-items: center;
  gap: 6rpx;
  background: #f8fafc;
  padding: 8rpx 18rpx;
  border-radius: 14rpx;
}

.text-danger-bg {
  background: #fff1f2;
}

.tonal-text {
  font-size: 22rpx;
  font-weight: 500;
}

.detail-btn {
  display: flex;
  align-items: center;
  gap: 4rpx;
  margin-left: auto;
}

.detail-text {
  font-size: 22rpx;
  font-weight: 600;
  color: #4f46e5;
}

.bottom-spacer {
  height: 130rpx;
}
</style>
