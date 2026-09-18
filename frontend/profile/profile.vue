<template>
  <view class="page-container">
    <mooc-nav-bar title="个人中心" :show-back="true" bg-color="#F8F9FC" />

    <scroll-view scroll-y class="content-scroll">
      <!-- 1. 用户信息卡片 -->
      <view class="user-card">
        <view class="avatar-box">
          <x-icon name="person" :size="48" color="#4F46E5" />
        </view>
        <view class="user-info" @tap="openRenameDialog">
          <view class="name-row">
            <text class="user-name">{{ user ? user.nickname : 'MoocPass 用户' }}</text>
            <x-icon name="edit" :size="24" color="#94A3B8" />
          </view>
          <view class="uid-tag">
            <text class="uid-text">ID: {{ user ? user.id : '--' }}</text>
          </view>
        </view>
      </view>

      <!-- 2. 核心功能导航（无多余描述） -->
      <view class="nav-card">
        <view class="nav-item" @tap="goPage('/moocpass/accounts/accounts')">
          <view class="nav-icon-wrap icon-blue">
            <x-icon name="shield-check" :size="36" color="#2563EB" />
          </view>
          <text class="nav-title">平台账号</text>
          <x-icon name="chevron-right" :size="28" color="#CBD5E1" />
        </view>

        <view class="nav-divider" />

        <view class="nav-item" @tap="goPage('/moocpass/answer/models')">
          <view class="nav-icon-wrap icon-purple">
            <x-icon name="cpu" :size="36" color="#7C3AED" />
          </view>
          <text class="nav-title">答题模型</text>
          <x-icon name="chevron-right" :size="28" color="#CBD5E1" />
        </view>

        <view class="nav-divider" />

        <view class="nav-item" @tap="goPage('/moocpass/usage/usage')">
          <view class="nav-icon-wrap icon-amber">
            <x-icon name="bar-chart" :size="36" color="#D97706" />
          </view>
          <text class="nav-title">用量统计</text>
          <x-icon name="chevron-right" :size="28" color="#CBD5E1" />
        </view>
      </view>

      <!-- 3. 学习偏好（M3 分段控制） -->
      <view class="section-title">学习偏好</view>
      <view class="pref-card">
        <!-- 播放倍速 -->
        <view class="pref-row">
          <text class="pref-label">播放倍速</text>
          <view class="segmented-track">
            <view
              v-for="s in [1.0, 1.25, 1.5, 2.0]"
              :key="s"
              class="segmented-item"
              :class="{ 'segmented-item--active': preferences.speed === s }"
              @tap="handleSpeedChange(s)"
            >
              <text class="segmented-text">{{ s }}x</text>
            </view>
          </view>
        </view>

        <view class="pref-divider" />

        <!-- 切换间隔 -->
        <view class="pref-row">
          <text class="pref-label">切换间隔</text>
          <view class="segmented-track">
            <view
              v-for="sec in [3, 5, 10]"
              :key="sec"
              class="segmented-item"
              :class="{ 'segmented-item--active': preferences.minIntervalSeconds === sec }"
              @tap="updatePref('minIntervalSeconds', sec)"
            >
              <text class="segmented-text">{{ sec }}s</text>
            </view>
          </view>
        </view>

        <view class="pref-divider" />

        <!-- 学习范围 -->
        <view class="pref-row">
          <text class="pref-label">学习范围</text>
          <view class="segmented-track">
            <view
              class="segmented-item"
              :class="{ 'segmented-item--active': preferences.onlyUncompleted }"
              @tap="updatePref('onlyUncompleted', true)"
            >
              <text class="segmented-text">未完成</text>
            </view>
            <view
              class="segmented-item"
              :class="{ 'segmented-item--active': !preferences.onlyUncompleted }"
              @tap="updatePref('onlyUncompleted', false)"
            >
              <text class="segmented-text">全部</text>
            </view>
          </view>
        </view>
      </view>

      <view class="bottom-spacer" />
    </scroll-view>

    <mooc-tab-bar current="mine" />
  </view>
</template>

<script>
import MoocNavBar from '../components/MoocNavBar.vue';
import MoocTabBar from '../components/MoocTabBar.vue';
import XIcon from '../../components/XIcon.vue';
import api, { getUser } from '../api/client.js';

export default {
  name: 'MoocProfile',
  components: { MoocNavBar, MoocTabBar, XIcon },
  data() {
    return {
      user: null,
      preferences: {
        speed: 1.0,
        minIntervalSeconds: 3,
        onlyUncompleted: true,
        resourceTypes: ['video', 'workid', 'document', 'read'],
        answerMode: 'OFF',
        autoSubmitPractice: false
      }
    };
  },
  onShow() {
    this.user = getUser();
    this.loadPreferences();
  },
  methods: {
    async loadPreferences() {
      try {
        const p = await api.getPreferences();
        if (p) this.preferences = { ...this.preferences, ...p };
        if (!this.user) {
          this.user = getUser();
        }
      } catch (_) {}
    },
    handleSpeedChange(s) {
      if (this.preferences.speed === s) return;
      if (s !== 1.0) {
        uni.showToast({
          title: '调整播放倍率可能会导致任务点无法完成，请注意',
          icon: 'none',
          duration: 3000
        });
      }
      this.updatePref('speed', s);
    },
    async updatePref(key, value) {
      this.preferences[key] = value;
      try {
        await api.updatePreferences(this.preferences);
      } catch (err) {
        uni.showToast({ title: err?.message || '保存失败', icon: 'none' });
      }
    },
    goPage(url) {
      uni.navigateTo({ url });
    },
    openRenameDialog() {
      uni.showModal({
        title: '修改昵称',
        editable: true,
        placeholderText: '请输入新昵称',
        content: this.user ? this.user.nickname : '',
        success: async (res) => {
          if (res.confirm && res.content?.trim()) {
            try {
              const u = await api.updateNickname(res.content.trim());
              this.user = u;
              uni.setStorageSync('moocpass.user', u);
              uni.showToast({ title: '修改成功', icon: 'success' });
            } catch (err) {
              uni.showToast({ title: err?.message || '修改失败', icon: 'none' });
            }
          }
        }
      });
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

.content-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 20rpx 28rpx;
  box-sizing: border-box;
}

/* 1. 用户卡片 */
.user-card {
  background: #ffffff;
  border-radius: 32rpx;
  padding: 28rpx 32rpx;
  display: flex;
  align-items: center;
  gap: 24rpx;
  border: 1rpx solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.03);
  margin-bottom: 24rpx;
}

.avatar-box {
  width: 100rpx;
  height: 100rpx;
  border-radius: 999rpx;
  background: #eef2ff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.user-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.user-name {
  font-size: 32rpx;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.5rpx;
}

.uid-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  align-self: flex-start;
  padding: 6rpx 16rpx;
  background: #f1f5f9;
  border-radius: 999rpx;
  box-sizing: border-box;
}

.uid-text {
  font-size: 20rpx;
  font-weight: 600;
  color: #64748b;
  line-height: 1;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, monospace;
}

/* 2. 核心导航卡片（Material 3 列表） */
.nav-card {
  background: #ffffff;
  border-radius: 32rpx;
  border: 1rpx solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.03);
  margin-bottom: 24rpx;
  overflow: hidden;
}

.nav-item {
  display: flex;
  align-items: center;
  padding: 26rpx 28rpx;
  gap: 20rpx;
  transition: background-color 0.15s ease;
}

.nav-item:active {
  background-color: #f8fafc;
}

.nav-icon-wrap {
  width: 68rpx;
  height: 68rpx;
  border-radius: 20rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.icon-blue { background: #eff6ff; }
.icon-purple { background: #f5f3ff; }
.icon-amber { background: #fffbeb; }

.nav-title {
  flex: 1;
  font-size: 28rpx;
  font-weight: 600;
  color: #1e293b;
}

.nav-divider {
  height: 1rpx;
  background: #f1f5f9;
  margin: 0 28rpx 0 116rpx;
}

/* 标题样式 */
.section-title {
  font-size: 24rpx;
  font-weight: 600;
  color: #64748b;
  margin: 16rpx 8rpx 14rpx;
  letter-spacing: 0.5rpx;
}

/* 3. 学习偏好卡片（Material 3 分段控制器） */
.pref-card {
  background: #ffffff;
  border-radius: 32rpx;
  padding: 12rpx 28rpx;
  border: 1rpx solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.03);
  margin-bottom: 24rpx;
}

.pref-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22rpx 0;
}

.pref-label {
  font-size: 28rpx;
  font-weight: 500;
  color: #334155;
}

.segmented-track {
  display: flex;
  background: #f1f5f9;
  border-radius: 999rpx;
  padding: 4rpx;
  gap: 4rpx;
}

.segmented-item {
  padding: 8rpx 20rpx;
  border-radius: 999rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.18s ease;
}

.segmented-text {
  font-size: 22rpx;
  color: #64748b;
  font-weight: 500;
  line-height: 1;
}

.segmented-item--active {
  background: #ffffff;
  box-shadow: 0 2rpx 8rpx rgba(15, 23, 42, 0.08);
}

.segmented-item--active .segmented-text {
  color: #4f46e5;
  font-weight: 600;
}

.pref-divider {
  height: 1rpx;
  background: #f1f5f9;
}

.bottom-spacer {
  height: 140rpx;
}
</style>
