<template>
  <view class="page-container">
    <mooc-nav-bar :title="task ? task.courseName : '任务详情'" :show-back="true">
      <template #right>
        <view class="nav-icon-btn" @tap="fetchDetail">
          <x-icon :name="isLoading ? 'spinner' : 'refresh'" :size="36" color="#475569" />
        </view>
      </template>
    </mooc-nav-bar>

    <scroll-view scroll-y class="content-scroll">
      <view v-if="task" class="summary-card">
        <view class="summary-header">
          <view class="title-group">
            <text class="course-name">{{ task.courseName || '网课任务 #' + task.id }}</text>
            <text class="task-id-text">ID: {{ task.id }}</text>
          </view>
          <view class="status-chip" :class="'chip--' + task.state.toLowerCase()">
            <text class="chip-text">{{ formatState(task.state) }}</text>
          </view>
        </view>

        <view class="current-step-box">
          <x-icon name="activity" :size="24" color="#4F46E5" />
          <text class="step-label">{{ task.currentStep || '正在执行中...' }}</text>
        </view>

        <view class="error-alert" v-if="task.errorMessage">
          <x-icon name="warning" :size="24" color="#E11D48" />
          <text class="error-text">{{ task.errorMessage }}</text>
        </view>

        <view class="metrics-grid">
          <view class="metric-box">
            <text class="m-val">{{ task.progress || 0 }}%</text>
            <text class="m-lbl">处理进度</text>
          </view>
          <view class="metric-box">
            <text class="m-val">{{ task.platformProgress !== null ? task.platformProgress + '%' : '--' }}</text>
            <text class="m-lbl">平台确认进度</text>
          </view>
          <view class="metric-box">
            <text class="m-val">{{ logs.length }}</text>
            <text class="m-lbl">日志事件数</text>
          </view>
        </view>

        <view class="control-actions-row">
          <button
            class="ctrl-btn btn-warning"
            v-if="task.state === 'RUNNING'"
            @tap="handleAction('pause')"
          >
            <x-icon name="pause" :size="28" color="#FFFFFF" />
            <text class="ctrl-btn-text">暂停执行</text>
          </button>
          <button
            class="ctrl-btn btn-success"
            v-if="['PAUSED', 'PAUSING'].includes(task.state)"
            @tap="handleAction('resume')"
          >
            <x-icon name="play" :size="28" color="#FFFFFF" />
            <text class="ctrl-btn-text">继续执行</text>
          </button>
          <button
            class="ctrl-btn btn-primary"
            v-if="['FAILED', 'PARTIAL'].includes(task.state)"
            @tap="handleAction('retry')"
          >
            <x-icon name="refresh" :size="28" color="#FFFFFF" />
            <text class="ctrl-btn-text">重试任务</text>
          </button>
          <button
            class="ctrl-btn btn-danger"
            v-if="['QUEUED', 'RUNNING', 'WAITING_AUTH', 'WAITING_USER', 'WAITING_QUOTA'].includes(task.state)"
            @tap="handleAction('cancel')"
          >
            <x-icon name="close" :size="28" color="#FFFFFF" />
            <text class="ctrl-btn-text">终止取消</text>
          </button>
        </view>
      </view>

      <view class="detail-tabs">
        <view
          class="d-tab"
          :class="{ 'd-tab--active': activeTab === 'logs' }"
          @tap="activeTab = 'logs'"
        >
          <x-icon name="file-text" :size="26" :color="activeTab === 'logs' ? '#4F46E5' : '#64748B'" />
          <text class="d-tab-text">日志 ({{ logs.length }})</text>
        </view>
        <view
          class="d-tab"
          :class="{ 'd-tab--active': activeTab === 'answers' }"
          @tap="switchAnswersTab"
        >
          <x-icon name="edit" :size="26" :color="activeTab === 'answers' ? '#4F46E5' : '#64748B'" />
          <text class="d-tab-text">答案 ({{ answers.length }})</text>
        </view>
      </view>

      <view v-if="activeTab === 'logs'" class="logs-container">
        <view class="terminal-card">
          <view class="terminal-header">
            <view class="terminal-dots">
              <view class="dot red" />
              <view class="dot yellow" />
              <view class="dot green" />
            </view>
            <text class="terminal-title">任务日志</text>
            <text class="log-poll-indicator" v-if="isPolling">实时同步中</text>
          </view>
          
          <view class="log-list" v-if="logs.length > 0">
            <view v-for="l in logs" :key="l.seq" class="log-line">
              <text class="log-seq">#{{ l.seq }}</text>
              <text class="log-time">{{ formatLogTime(l.createdAt) }}</text>
              <text class="log-level" :class="'lvl--' + (l.level || 'info').toLowerCase()">[{{ l.level }}]</text>
              <text class="log-msg">{{ l.message }}</text>
            </view>
          </view>
          <view v-else class="log-empty">
            <text class="empty-log-text">暂无日志</text>
          </view>
        </view>
      </view>

      <view v-else-if="activeTab === 'answers'" class="answers-container">
        <view v-if="answers.length === 0" class="answers-empty">
          <x-icon name="help-circle" :size="48" color="#CBD5E1" />
          <text class="empty-ans-title">暂无答题记录</text>
        </view>

        <view v-else class="answer-cards-list">
          <view v-for="ans in answers" :key="ans.id" class="answer-card">
            <view class="ans-header">
              <view class="ans-type-badge">
                <text class="ans-type-text">{{ getQuestionTypeName(ans.questionType) }}</text>
              </view>
              <view class="ans-source-chip" :class="'src--' + (ans.source || 'ai').toLowerCase()">
                <text class="src-text">{{ ans.source || 'AI' }}</text>
              </view>
            </view>

            <text class="ans-question">{{ ans.question }}</text>

            <view class="ans-options" v-if="ans.options && ans.options.length > 0">
              <view v-for="(opt, optIdx) in ans.options" :key="optIdx" class="opt-row">
                <text class="opt-text">{{ opt }}</text>
              </view>
            </view>

            <view class="ans-result-box">
              <text class="res-label">自动答案:</text>
              <text class="res-value">{{ ans.answer || '无答案' }}</text>
            </view>
          </view>
        </view>
      </view>

      <view class="bottom-spacer" />
    </scroll-view>
  </view>
</template>

<script>
import MoocNavBar from '../components/MoocNavBar.vue';
import XIcon from '../../components/XIcon.vue';
import api from '../api/client.js';

export default {
  name: 'MoocTaskDetail',
  components: { MoocNavBar, XIcon },
  data() {
    return {
      taskId: null,
      task: null,
      logs: [],
      answers: [],
      activeTab: 'logs',
      lastSeq: 0,
      isLoading: false,
      isPolling: false,
      pollTimer: null
    };
  },
  onLoad(options) {
    if (options.id) {
      this.taskId = Number(options.id);
    }
  },
  onShow() {
    if (this.taskId) {
      this.fetchDetail();
      this.startPolling();
    }
  },
  onHide() {
    this.stopPolling();
  },
  onUnload() {
    this.stopPolling();
  },
  methods: {
    async fetchDetail() {
      this.isLoading = true;
      try {
        const t = await api.getTaskDetail(this.taskId);
        this.task = t;
        await this.fetchLogs();
      } catch (err) {
        uni.showToast({ title: err?.message || '获取详情失败', icon: 'none' });
      }
      this.isLoading = false;
    },
    async fetchLogs() {
      try {
        const newLogs = await api.getTaskLogs(this.taskId, this.lastSeq);
        if (newLogs && newLogs.length > 0) {
          this.logs = [...this.logs, ...newLogs];
          this.lastSeq = newLogs[newLogs.length - 1].seq;
        }
      } catch (_) {}
    },
    async switchAnswersTab() {
      this.activeTab = 'answers';
      try {
        const res = await api.getTaskAnswers(this.taskId);
        this.answers = res || [];
      } catch (_) {}
    },
    startPolling() {
      this.stopPolling();
      this.isPolling = true;
      this.pollTimer = setInterval(async () => {
        if (!this.task || !['QUEUED', 'RUNNING', 'PAUSING', 'RETRY_WAIT', 'VERIFYING'].includes(this.task.state)) {
          this.stopPolling();
          return;
        }
        try {
          const t = await api.getTaskDetail(this.taskId);
          this.task = t;
          await this.fetchLogs();
        } catch (_) {}
      }, 4000);
    },
    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer);
        this.pollTimer = null;
      }
      this.isPolling = false;
    },
    async handleAction(action) {
      try {
        await api.taskAction(this.taskId, action);
        uni.showToast({ title: '操作已生效', icon: 'success' });
        this.fetchDetail();
        if (action === 'resume') {
          this.startPolling();
        }
      } catch (err) {
        uni.showToast({ title: err?.message || '操作失败', icon: 'none' });
      }
    },
    getQuestionTypeName(type) {
      const map = {
        single: '单选题',
        multiple: '多选题',
        judgement: '判断题',
        completion: '填空题'
      };
      return map[type] || type || '选择题';
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
    },
    formatLogTime(timeStr) {
      if (!timeStr) return '';
      if (typeof timeStr === 'string') {
        if (timeStr.includes('T') || timeStr.endsWith('Z')) {
          const d = new Date(timeStr);
          if (!isNaN(d.getTime())) {
            const pad = n => n < 10 ? '0' + n : n;
            return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
          }
        }
        if (timeStr.length >= 19) {
          return timeStr.substring(11, 19);
        }
      }
      return timeStr;
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
.summary-card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 28rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.8);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.04);
  margin-bottom: 24rpx;
}
.summary-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
.title-group {
  flex: 1;
}
.course-name {
  font-size: 30rpx;
  font-weight: 700;
  color: #0f172a;
}
.task-id-text {
  font-size: 20rpx;
  color: #94a3b8;
  margin-left: 8rpx;
}
.status-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  box-sizing: border-box;
}
.chip--running { background: #dcfce7; color: #15803d; }
.chip--queued { background: #e0f2fe; color: #0369a1; }
.chip--verifying, .chip--retry_wait { background: #eef2ff; color: #4f46e5; }
.chip--pausing, .chip--paused { background: #fef3c7; color: #b45309; }
.chip--waiting_auth, .chip--waiting_user, .chip--waiting_quota { background: #fee2e2; color: #b91c1c; }
.chip--completed { background: #f1f5f9; color: #475569; }
.chip--failed, .chip--canceled { background: #fff1f2; color: #e11d48; }
.chip-text {
  font-size: 20rpx;
  font-weight: 600;
  line-height: 1;
  text-align: center;
}
.current-step-box {
  display: flex;
  align-items: center;
  gap: 10rpx;
  background: #f1f5f9;
  padding: 12rpx 16rpx;
  border-radius: 14rpx;
  margin-bottom: 16rpx;
}
.step-label {
  font-size: 22rpx;
  color: #334155;
  flex: 1;
}
.error-alert {
  display: flex;
  align-items: center;
  gap: 8rpx;
  background: #fff1f2;
  padding: 10rpx 16rpx;
  border-radius: 14rpx;
  margin-bottom: 16rpx;
}
.error-text {
  font-size: 20rpx;
  color: #e11d48;
}
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12rpx;
  background: #f8fafc;
  border-radius: 18rpx;
  padding: 16rpx 12rpx;
  margin-bottom: 20rpx;
}
.metric-box {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.m-val {
  font-size: 32rpx;
  font-weight: 700;
  color: #4f46e5;
}
.m-lbl {
  font-size: 18rpx;
  color: #94a3b8;
  margin-top: 4rpx;
}
.control-actions-row {
  display: flex;
  gap: 12rpx;
}
.ctrl-btn {
  flex: 1;
  height: 68rpx;
  border-radius: 18rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6rpx;
}
.ctrl-btn-text {
  font-size: 22rpx;
  font-weight: 600;
  color: #ffffff;
  line-height: 1;
}
.btn-warning { background: #d97706; }
.btn-success { background: #059669; }
.btn-primary { background: #2563eb; }
.btn-danger { background: #e11d48; }
.detail-tabs {
  display: flex;
  background: #ffffff;
  border-radius: 20rpx;
  padding: 8rpx;
  margin-bottom: 20rpx;
  border: 1rpx solid #e2e8f0;
}
.d-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  height: 68rpx;
  border-radius: 14rpx;
}
.d-tab--active {
  background: #eef2ff;
}
.d-tab-text {
  font-size: 24rpx;
  color: #64748b;
  font-weight: 500;
  line-height: 1;
}
.d-tab--active .d-tab-text {
  color: #4f46e5;
  font-weight: 600;
}
.terminal-card {
  background: #0f172a;
  border-radius: 24rpx;
  padding: 24rpx;
  box-shadow: 0 12rpx 36rpx rgba(15, 23, 42, 0.2);
  width: 100%;
  box-sizing: border-box;
  overflow: hidden;
}
.terminal-header {
  display: flex;
  align-items: center;
  padding-bottom: 16rpx;
  border-bottom: 1rpx solid rgba(255, 255, 255, 0.1);
  margin-bottom: 16rpx;
  width: 100%;
  box-sizing: border-box;
}
.terminal-dots {
  display: flex;
  gap: 10rpx;
  flex-shrink: 0;
}
.dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 999rpx;
  flex-shrink: 0;
}
.dot.red { background: #ef4444; }
.dot.yellow { background: #f59e0b; }
.dot.green { background: #10b981; }
.terminal-title {
  font-size: 20rpx;
  color: #94a3b8;
  margin-left: 16rpx;
  font-family: monospace;
  flex-shrink: 0;
}
.log-poll-indicator {
  margin-left: auto;
  font-size: 18rpx;
  color: #10b981;
  flex-shrink: 0;
}
.log-list {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  width: 100%;
  box-sizing: border-box;
}
.log-line {
  display: flex;
  align-items: flex-start;
  gap: 8rpx;
  font-family: monospace;
  line-height: 1.45;
  width: 100%;
  box-sizing: border-box;
}
.log-seq {
  font-size: 18rpx;
  color: #64748b;
  flex-shrink: 0;
}
.log-time {
  font-size: 18rpx;
  color: #94a3b8;
  flex-shrink: 0;
}
.log-level {
  font-size: 18rpx;
  font-weight: 700;
  flex-shrink: 0;
}
.lvl--info { color: #38bdf8; }
.lvl--warn { color: #facc15; }
.lvl--error { color: #f87171; }
.log-msg {
  font-size: 20rpx;
  color: #e2e8f0;
  flex: 1;
  min-width: 0;
  word-break: break-all;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.log-empty {
  padding: 40rpx 0;
  text-align: center;
}
.empty-log-text {
  font-size: 22rpx;
  color: #64748b;
}
.answers-empty {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 56rpx 24rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  border: 1rpx dashed #cbd5e1;
}
.empty-ans-title {
  font-size: 26rpx;
  font-weight: 600;
  color: #475569;
  margin-top: 16rpx;
}
.empty-ans-sub {
  font-size: 22rpx;
  color: #94a3b8;
  margin-top: 6rpx;
  text-align: center;
}
.answer-cards-list {
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}
.answer-card {
  background: #ffffff;
  border-radius: 24rpx;
  padding: 24rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.8);
}
.ans-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12rpx;
}
.ans-type-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #f1f5f9;
  padding: 6rpx 14rpx;
  border-radius: 8rpx;
  box-sizing: border-box;
}
.ans-type-text {
  font-size: 20rpx;
  color: #475569;
  line-height: 1;
}
.ans-source-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  box-sizing: border-box;
}
.src--ai { background: #faf5ff; color: #9333ea; }
.src--tiku { background: #eff6ff; color: #2563eb; }
.src--manual { background: #ecfdf5; color: #059669; }
.src-text {
  font-size: 20rpx;
  font-weight: 600;
  line-height: 1;
}
.ans-question {
  font-size: 26rpx;
  font-weight: 600;
  color: #0f172a;
  line-height: 1.4;
  margin-bottom: 14rpx;
}
.ans-options {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  margin-bottom: 14rpx;
}
.opt-row {
  background: #f8fafc;
  padding: 8rpx 14rpx;
  border-radius: 10rpx;
}
.opt-text {
  font-size: 22rpx;
  color: #475569;
}
.ans-result-box {
  display: flex;
  align-items: center;
  gap: 8rpx;
  background: #f0fdf4;
  padding: 10rpx 16rpx;
  border-radius: 12rpx;
}
.res-label {
  font-size: 22rpx;
  color: #166534;
}
.res-value {
  font-size: 22rpx;
  font-weight: 700;
  color: #15803d;
  flex: 1;
}
.bottom-spacer {
  height: 60rpx;
}
</style>
