<template>
  <view class="page-container">
    <mooc-nav-bar title="网课列表" :show-back="true" bg-color="#F8F9FC">
      <template #right>
        <view class="nav-icon-btn" @tap="syncCurrentOrFirstAccount" v-if="accounts.length > 0">
          <x-icon :name="isSyncing ? 'spinner' : 'refresh'" :size="36" color="#475569" />
        </view>
      </template>
    </mooc-nav-bar>

    <!-- 顶部分类与搜索 -->
    <view class="filter-header">
      <scroll-view scroll-x class="account-scroll" :show-scrollbar="false">
        <view class="account-chips">
          <view
            class="m3-chip"
            :class="{ 'm3-chip--active': !selectedAccountId }"
            @tap="selectAccount(null)"
          >
            <text class="chip-text">全部</text>
          </view>
          <view
            v-for="acc in accounts"
            :key="acc.id"
            class="m3-chip"
            :class="{ 'm3-chip--active': selectedAccountId === acc.id }"
            @tap="selectAccount(acc.id)"
          >
            <text class="chip-text">{{ acc.username }}</text>
          </view>
        </view>
      </scroll-view>

      <view class="m3-search-bar">
        <x-icon name="search" :size="28" color="#94A3B8" />
        <input
          type="text"
          class="search-input"
          v-model="searchQuery"
          placeholder="搜索课程或教师"
          placeholder-class="input-placeholder"
          @input="onSearchInput"
        />
        <view class="clear-btn" v-if="searchQuery" @tap="clearSearch">
          <x-icon name="close" :size="26" color="#94A3B8" />
        </view>
      </view>
    </view>

    <scroll-view
      scroll-y
      class="content-scroll"
      refresher-enabled
      :refresher-triggered="isPullRefreshing"
      @refresherrefresh="onPullRefresh"
    >
      <view v-if="isLoading && courses.length === 0" class="skeleton-wrap">
        <view v-for="i in 3" :key="i" class="skeleton-card" />
      </view>

      <view v-else-if="filteredCourses.length === 0" class="empty-state">
        <view class="empty-icon-box">
          <x-icon name="book" :size="48" color="#94A3B8" />
        </view>
        <text class="empty-title">{{ accounts.length === 0 ? '暂未绑定网课账号' : '暂无课程记录' }}</text>
        <button class="empty-action-btn" v-if="accounts.length === 0" @tap="goAccounts">
          <text class="btn-text">去绑定账号</text>
        </button>
        <button class="empty-action-btn" v-else :loading="isSyncing" @tap="syncCurrentOrFirstAccount">
          <text class="btn-text">同步课程</text>
        </button>
      </view>

      <view v-else class="course-list">
        <!-- 列表头统计与全选 -->
        <view class="list-action-header">
          <text class="section-count">共 {{ filteredCourses.length }} 门课程</text>
          <view class="m3-text-btn" @tap="toggleSelectAll">
            <text class="text-btn-label">{{ isAllSelected ? '取消全选' : '全选' }}</text>
          </view>
        </view>

        <view
          v-for="c in filteredCourses"
          :key="c.id"
          class="course-card"
          :class="{ 'course-card--selected': selectedCourseIds.includes(c.id) }"
          @tap="goDetail(c.id)"
        >
          <view class="select-box" @tap.stop="toggleSelectCourse(c.id)">
            <x-icon
              :name="selectedCourseIds.includes(c.id) ? 'check-circle-filled' : 'circle'"
              :size="40"
              :color="selectedCourseIds.includes(c.id) ? '#4F46E5' : '#CBD5E1'"
            />
          </view>

          <view class="course-cover">
            <image
              v-if="c.coverUrl"
              class="cover-img"
              :src="c.coverUrl"
              mode="aspectFill"
              lazy-load
            />
            <view v-else class="cover-placeholder">
              <x-icon name="book" :size="36" color="#94A3B8" />
            </view>
          </view>

          <view class="course-info">
            <text class="course-name">{{ c.name }}</text>

            <view class="course-meta">
              <text class="meta-tag" v-if="c.teacher">{{ c.teacher }}</text>
              <text class="meta-account">{{ getAccountUsername(c.accountId) }}</text>
            </view>

            <view class="progress-wrap">
              <view class="progress-track">
                <view class="progress-bar" :style="{ width: (c.platformProgress || 0) + '%' }" />
              </view>
              <text class="progress-val">{{ c.platformProgress !== null && c.platformProgress !== undefined ? c.platformProgress + '%' : '0%' }}</text>
            </view>
          </view>

          <view class="course-arrow">
            <x-icon name="chevron-right" :size="28" color="#CBD5E1" />
          </view>
        </view>
      </view>

      <view class="bottom-spacer" :class="{ 'with-bar': selectedCourseIds.length > 0 }" />
    </scroll-view>

    <!-- 批量创建悬浮栏 (Material 3 Floating Action Bar) -->
    <view class="floating-bar" v-if="selectedCourseIds.length > 0">
      <view class="bar-left">
        <text class="bar-label">已选</text>
        <text class="bar-num">{{ selectedCourseIds.length }}</text>
        <text class="bar-label">门课程</text>
      </view>
      <view class="bar-btn" @tap="goBatchCreate">
        <x-icon name="play" :size="28" color="#FFFFFF" />
        <text class="bar-btn-text">创建任务</text>
      </view>
    </view>

    <mooc-tab-bar current="courses" />
  </view>
</template>

<script>
import MoocNavBar from '../components/MoocNavBar.vue';
import MoocTabBar from '../components/MoocTabBar.vue';
import XIcon from '../../components/XIcon.vue';
import api from '../api/client.js';

export default {
  name: 'MoocCourses',
  components: { MoocNavBar, MoocTabBar, XIcon },
  data() {
    return {
      accounts: [],
      courses: [],
      selectedAccountId: null,
      searchQuery: '',
      selectedCourseIds: [],
      isLoading: false,
      isSyncing: false,
      isPullRefreshing: false
    };
  },
  computed: {
    filteredCourses() {
      let list = Array.isArray(this.courses) ? this.courses : [];
      if (this.selectedAccountId) {
        list = list.filter((c) => c.accountId === this.selectedAccountId);
      }
      if (this.searchQuery && this.searchQuery.trim()) {
        const q = this.searchQuery.trim().toLowerCase();
        list = list.filter((c) => (c.name && c.name.toLowerCase().includes(q)) || (c.teacher && c.teacher.toLowerCase().includes(q)));
      }
      return list;
    },
    isAllSelected() {
      if (this.filteredCourses.length === 0) return false;
      return this.filteredCourses.every((c) => this.selectedCourseIds.includes(c.id));
    }
  },
  onLoad(options) {
    if (options && options.accountId && options.accountId !== 'undefined' && options.accountId !== 'null') {
      this.selectedAccountId = Number(options.accountId);
    }
  },
  onShow() {
    this.fetchData();
  },
  methods: {
    async fetchData() {
      this.isLoading = true;
      try {
        const queryParams = { page: 1 };
        if (this.selectedAccountId) {
          queryParams.accountId = this.selectedAccountId;
        }
        const [accs, crss] = await Promise.all([
          api.getAccounts(),
          api.getCourses(queryParams)
        ]);
        this.accounts = Array.isArray(accs) ? accs : [];
        this.courses = Array.isArray(crss) ? crss : (crss?.items || []);
      } catch (err) {
        uni.showToast({ title: err?.message || '加载课程失败', icon: 'none' });
      }
      this.isLoading = false;
      this.isPullRefreshing = false;
    },
    onPullRefresh() {
      this.isPullRefreshing = true;
      this.fetchData();
    },
    selectAccount(id) {
      this.selectedAccountId = id;
      this.fetchData();
    },
    onSearchInput() {},
    clearSearch() {
      this.searchQuery = '';
    },
    getAccountUsername(accId) {
      const acc = this.accounts.find((a) => a.id === accId);
      return acc ? acc.username : '';
    },
    toggleSelectCourse(id) {
      const idx = this.selectedCourseIds.indexOf(id);
      if (idx >= 0) {
        this.selectedCourseIds.splice(idx, 1);
      } else {
        this.selectedCourseIds.push(id);
      }
    },
    toggleSelectAll() {
      if (this.isAllSelected) {
        this.selectedCourseIds = [];
      } else {
        this.selectedCourseIds = this.filteredCourses.map((c) => c.id);
      }
    },
    goDetail(id) {
      uni.navigateTo({ url: `/moocpass/courses/detail?id=${id}` });
    },
    goBatchCreate() {
      uni.navigateTo({
        url: `/moocpass/task/create?courseIds=${this.selectedCourseIds.join(',')}`
      });
    },
    goAccounts() {
      uni.navigateTo({ url: '/moocpass/accounts/accounts' });
    },
    async syncCurrentOrFirstAccount() {
      const targetId = this.selectedAccountId || (this.accounts.length > 0 ? this.accounts[0].id : null);
      if (!targetId) {
        uni.showToast({ title: '请先绑定平台账号', icon: 'none' });
        return;
      }
      this.isSyncing = true;
      uni.showLoading({ title: '正在刷新课程...' });
      try {
        const res = await api.syncAccount(targetId);
        uni.hideLoading();
        uni.showToast({ title: res?.message || '同步完成', icon: 'success' });
        await this.fetchData();
      } catch (err) {
        uni.hideLoading();
        uni.showToast({ title: err?.message || '同步失败', icon: 'none' });
      } finally {
        this.isSyncing = false;
      }
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

/* 顶部分类与搜索 */
.filter-header {
  flex-shrink: 0;
  background: #ffffff;
  border-bottom: 1rpx solid rgba(0, 0, 0, 0.04);
  padding: 16rpx 28rpx 20rpx;
}

.account-scroll {
  white-space: nowrap;
}

.account-chips {
  display: flex;
  gap: 12rpx;
  margin-bottom: 16rpx;
}

.m3-chip {
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

.m3-chip--active {
  background: #4f46e5;
  box-shadow: 0 2rpx 8rpx rgba(79, 70, 229, 0.25);
}

.chip-text {
  font-size: 22rpx;
  color: #64748b;
  font-weight: 500;
  line-height: 1;
}

.m3-chip--active .chip-text {
  color: #ffffff;
  font-weight: 600;
}

.m3-search-bar {
  display: flex;
  align-items: center;
  gap: 12rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 20rpx;
  padding: 0 20rpx;
  height: 72rpx;
}

.search-input {
  flex: 1;
  font-size: 24rpx;
  color: #0f172a;
}

.input-placeholder {
  color: #94a3b8;
  font-size: 24rpx;
}

.clear-btn {
  display: flex;
  align-items: center;
}

.content-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 20rpx 28rpx;
  box-sizing: border-box;
}

/* 列表动作栏 */
.list-action-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8rpx 4rpx 16rpx;
}

.section-count {
  font-size: 22rpx;
  font-weight: 600;
  color: #64748b;
}

.m3-text-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6rpx 18rpx;
  background: #eef2ff;
  border-radius: 999rpx;
  box-sizing: border-box;
}

.text-btn-label {
  font-size: 20rpx;
  font-weight: 600;
  color: #4f46e5;
  line-height: 1;
}

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

.empty-action-btn {
  margin-top: 24rpx;
  background: #4f46e5;
  border-radius: 999rpx;
  padding: 0 36rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-text {
  font-size: 24rpx;
  font-weight: 600;
  color: #ffffff;
}

/* 课程卡片 */
.course-list {
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}

.course-card {
  background: #ffffff;
  border-radius: 32rpx;
  padding: 20rpx 24rpx;
  display: flex;
  align-items: center;
  gap: 16rpx;
  border: 1rpx solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.03);
  transition: all 0.15s ease;
}

.course-card--selected {
  border-color: #4f46e5;
  background: #fcfdff;
}

.select-box {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.course-cover {
  width: 132rpx;
  height: 132rpx;
  border-radius: 20rpx;
  overflow: hidden;
  flex-shrink: 0;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cover-img {
  width: 100%;
  height: 100%;
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f1f5f9;
}

.course-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.course-name {
  font-size: 28rpx;
  font-weight: 600;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.course-meta {
  display: flex;
  align-items: center;
  gap: 10rpx;
}

.meta-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 20rpx;
  line-height: 1;
  color: #475569;
  background: #f1f5f9;
  padding: 4rpx 10rpx;
  border-radius: 8rpx;
  box-sizing: border-box;
}

.meta-account {
  font-size: 20rpx;
  color: #94a3b8;
}

.progress-wrap {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 4rpx;
}

.progress-track {
  flex: 1;
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

.progress-val {
  font-size: 20rpx;
  font-weight: 600;
  color: #4f46e5;
  min-width: 56rpx;
  text-align: right;
}

.course-arrow {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

/* 底部悬浮控制栏 (M3 Floating Pill) */
.floating-bar {
  position: fixed;
  bottom: 140rpx;
  left: 32rpx;
  right: 32rpx;
  background: #0f172a;
  border-radius: 999rpx;
  padding: 14rpx 20rpx 14rpx 32rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 12rpx 36rpx rgba(15, 23, 42, 0.25);
  z-index: 90;
}

.bar-left {
  display: flex;
  align-items: center;
  gap: 8rpx;
}

.bar-label {
  font-size: 24rpx;
  color: #94a3b8;
}

.bar-num {
  font-size: 32rpx;
  font-weight: 700;
  color: #38bdf8;
}

.bar-btn {
  display: flex;
  align-items: center;
  gap: 8rpx;
  background: #4f46e5;
  padding: 12rpx 28rpx;
  border-radius: 999rpx;
}

.bar-btn-text {
  font-size: 24rpx;
  font-weight: 600;
  color: #ffffff;
}

.bottom-spacer {
  height: 130rpx;
}

.bottom-spacer.with-bar {
  height: 220rpx;
}
</style>
