<template>
  <view class="page-container">
    <mooc-nav-bar title="API 用量" :show-back="true">
      <template #right>
        <view class="nav-icon-btn" @tap="fetchUsage">
          <x-icon :name="isLoading ? 'spinner' : 'refresh'" :size="36" color="#475569" />
        </view>
      </template>
    </mooc-nav-bar>

    <view class="time-filter-bar">
      <view
        v-for="d in [7, 30, 90]"
        :key="d"
        class="time-pill"
        :class="{ 'time-pill--active': days === d }"
        @tap="changeDays(d)"
      >
        <text class="pill-label">近 {{ d }} 天</text>
      </view>
    </view>

    <scroll-view scroll-y class="content-scroll">
      <view class="summary-grid">
        <view class="summary-card">
          <text class="sum-val">{{ totalCalls }}</text>
          <text class="sum-lbl">总调用次数</text>
        </view>
        <view class="summary-card">
          <text class="sum-val text-green">{{ successRate }}%</text>
          <text class="sum-lbl">成功率</text>
        </view>
        <view class="summary-card">
          <text class="sum-val text-indigo">{{ totalTokens }}</text>
          <text class="sum-lbl">消耗 Tokens</text>
        </view>
        <view class="summary-card">
          <text class="sum-val">{{ avgElapsed }}ms</text>
          <text class="sum-lbl">平均响应时间</text>
        </view>
      </view>

      <view class="section-title-row">
        <text class="section-title">调用记录明细</text>
        <text class="record-count">{{ recordCountLabel }}</text>
      </view>

      <view v-if="isLoading && records.length === 0" class="skeleton-wrap">
        <view v-for="i in 3" :key="i" class="skeleton-card" />
      </view>

      <view v-else-if="records.length === 0" class="empty-state">
        <view class="empty-icon-box">
          <x-icon name="bar-chart" :size="48" color="#94A3B8" />
        </view>
        <text class="empty-title">暂无调用记录</text>
      </view>

      <view v-else class="record-list">
        <view v-for="r in records" :key="r.id" class="record-card">
          <view class="record-top">
            <view class="model-badge">
              <x-icon name="cpu" :size="22" color="#4F46E5" />
              <text class="model-text">{{ r.model }}</text>
            </view>
            <view class="record-status" :class="'rec--' + (r.state || 'success').toLowerCase()">
              <text class="status-text">{{ r.state === 'SUCCESS' ? '成功' : (r.errorCode || r.state) }}</text>
            </view>
          </view>

          <view class="record-metrics">
            <view class="metric-col">
              <text class="m-title">输入 Tokens</text>
              <text class="m-data">{{ r.inputTokens !== null ? r.inputTokens : '--' }}</text>
            </view>
            <view class="metric-col">
              <text class="m-title">输出 Tokens</text>
              <text class="m-data">{{ r.outputTokens !== null ? r.outputTokens : '--' }}</text>
            </view>
            <view class="metric-col">
              <text class="m-title">耗时</text>
              <text class="m-data">{{ r.elapsedMs !== null ? r.elapsedMs + 'ms' : '--' }}</text>
            </view>
          </view>

          <view class="record-footer">
            <text class="record-time">{{ formatRecordTime(r.createdAt) }}</text>
            <text class="record-task" v-if="r.taskId">任务 #{{ r.taskId }}</text>
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
  name: 'MoocUsage',
  components: { MoocNavBar, XIcon },
  data() {
    return {
      days: 30,
      records: [],
      totals: {},
      requestId: 0,
      isLoading: false
    };
  },
  computed: {
    totalCalls() {
      return Number(this.totals.requests) || 0;
    },
    recordCountLabel() {
      return this.totalCalls > this.records.length
        ? `最近 ${this.records.length} / ${this.totalCalls} 条`
        : `共 ${this.totalCalls} 条`;
    },
    successCalls() {
      return Number(this.totals.succeeded) || 0;
    },
    successRate() {
      if (this.totalCalls === 0) return 0;
      return Math.round((this.successCalls / this.totalCalls) * 100);
    },
    totalTokens() {
      return (Number(this.totals.inputTokens) || 0) + (Number(this.totals.outputTokens) || 0);
    },
    avgElapsed() {
      return Math.round(Number(this.totals.avgElapsed) || 0);
    }
  },
  onShow() {
    this.fetchUsage();
  },
  methods: {
    async fetchUsage() {
      const requestId = ++this.requestId;
      this.isLoading = true;
      try {
        const res = await api.getUsage(this.days);
        if (requestId !== this.requestId) return;
        this.records = Array.isArray(res?.records) ? res.records : [];
        this.totals = res?.totals && typeof res.totals === 'object' ? res.totals : {};
      } catch (err) {
        if (requestId === this.requestId) {
          uni.showToast({ title: err?.message || '获取用量失败', icon: 'none' });
        }
      } finally {
        if (requestId === this.requestId) this.isLoading = false;
      }
    },
    changeDays(d) {
      this.days = d;
      this.fetchUsage();
    },
    formatRecordTime(timeStr) {
      if (!timeStr) return '';
      if (typeof timeStr === 'string') {
        if (timeStr.includes('T') || timeStr.endsWith('Z')) {
          const d = new Date(timeStr);
          if (!isNaN(d.getTime())) {
            const pad = n => n < 10 ? '0' + n : n;
            return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
          }
        }
        if (timeStr.length >= 19) {
          return timeStr.substring(5, 19);
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
.time-filter-bar {
  flex-shrink: 0;
  display: flex;
  gap: 16rpx;
  padding: 16rpx 24rpx;
  background: #ffffff;
  border-bottom: 1rpx solid rgba(226, 232, 240, 0.8);
}
.time-pill {
  flex: 1;
  height: 64rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
}
.time-pill--active {
  background: #4f46e5;
}
.pill-label {
  font-size: 22rpx;
  color: #64748b;
  font-weight: 500;
  line-height: 1;
}
.time-pill--active .pill-label {
  color: #ffffff;
  font-weight: 600;
}
.content-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 24rpx;
  box-sizing: border-box;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16rpx;
  margin-bottom: 24rpx;
}
.summary-card {
  background: #ffffff;
  border-radius: 24rpx;
  padding: 24rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.8);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.04);
  display: flex;
  flex-direction: column;
  align-items: center;
}
.sum-val {
  font-size: 40rpx;
  font-weight: 700;
  color: #0f172a;
}
.text-green { color: #059669; }
.text-indigo { color: #4f46e5; }
.sum-lbl {
  font-size: 20rpx;
  color: #64748b;
  margin-top: 6rpx;
}
.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
.section-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #0f172a;
}
.record-count {
  font-size: 22rpx;
  color: #94a3b8;
}
.empty-state {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 56rpx 24rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  border: 1rpx dashed #cbd5e1;
}
.empty-icon-box {
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
  font-weight: 600;
  color: #475569;
}
.empty-sub {
  font-size: 22rpx;
  color: #94a3b8;
  margin-top: 6rpx;
  text-align: center;
  padding: 0 20rpx;
}
.record-list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.record-card {
  background: #ffffff;
  border-radius: 24rpx;
  padding: 20rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.8);
}
.record-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
.model-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  background: #eef2ff;
  padding: 6rpx 14rpx;
  border-radius: 8rpx;
  box-sizing: border-box;
}
.model-text {
  font-size: 22rpx;
  font-weight: 600;
  color: #4f46e5;
  line-height: 1;
}
.record-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  box-sizing: border-box;
}
.rec--success { background: #dcfce7; color: #15803d; }
.rec--failed { background: #fff1f2; color: #e11d48; }
.status-text {
  font-size: 18rpx;
  font-weight: 600;
  line-height: 1;
}
.record-metrics {
  display: flex;
  background: #f8fafc;
  padding: 12rpx;
  border-radius: 12rpx;
  margin-bottom: 12rpx;
}
.metric-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.m-title {
  font-size: 18rpx;
  color: #94a3b8;
}
.m-data {
  font-size: 24rpx;
  font-weight: 600;
  color: #1e293b;
  margin-top: 4rpx;
}
.record-footer {
  display: flex;
  justify-content: space-between;
}
.record-time {
  font-size: 20rpx;
  color: #94a3b8;
}
.record-task {
  font-size: 20rpx;
  color: #64748b;
}
.bottom-spacer {
  height: 60rpx;
}
</style>
