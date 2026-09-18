<template>
  <view class="page-container">
    <mooc-nav-bar :title="course ? course.name : '课程详情'" :show-back="true">
      <template #right>
        <view @tap="loadCourse(true)"><x-icon name="refresh" :size="34" color="#475569" /></view>
      </template>
    </mooc-nav-bar>

    <scroll-view scroll-y class="content-scroll">
      <view v-if="isLoading" class="sync-loading-card">
        <view class="sync-loading-head">
          <view class="sync-icon-wrap">
            <x-icon name="refresh" :size="34" color="#4f46e5" />
          </view>
          <view class="sync-copy">
            <text class="sync-title">正在同步平台章节</text>
            <text class="sync-desc">{{ loadingTip }}</text>
          </view>
          <text class="sync-percent">{{ loadingProgress }}%</text>
        </view>
        <view class="sync-progress-bg">
          <view class="sync-progress-fill" :style="{ width: loadingProgress + '%' }" />
        </view>
      </view>

      <view v-if="loadError" class="empty-state" @tap="loadCourse(true)">
        <text class="empty-title">{{ loadError }}</text>
        <text>点击重试</text>
      </view>
      <view v-else-if="!isLoading && !course" class="empty-state">
        <x-icon name="error" :size="48" color="#E11D48" />
        <text class="empty-title">课程未找到</text>
      </view>

      <view v-if="course">
        <view class="course-summary-card">
          <view class="summary-header">
            <view class="detail-cover-wrap">
              <image v-if="course.coverUrl" class="detail-cover-img" :src="course.coverUrl" mode="aspectFill" />
              <view v-else class="detail-cover-placeholder">
                <x-icon name="book" :size="40" color="#94A3B8" />
              </view>
            </view>
            <view class="summary-top">
              <text class="summary-name">{{ course.name }}</text>
              <view class="teacher-row" v-if="course.teacher">
                <x-icon name="user" :size="22" color="#64748B" />
                <text class="teacher-name">{{ course.teacher }}</text>
              </view>
            </view>
          </view>
          
          <view class="progress-box">
            <view class="progress-bar-bg">
              <view class="progress-bar-fill" :style="{ width: (course.platformProgress || 0) + '%' }" />
            </view>
            <view class="progress-desc-row">
              <text class="p-num">平台进度 {{ course.platformProgress !== null && course.platformProgress !== undefined ? course.platformProgress + '%' : '0%' }}</text>
              <text class="r-count">{{ learnedCount }}/{{ resources.length }} 已完成</text>
            </view>
          </view>
        </view>

        <view class="section-title-row">
          <text class="section-title">章节资源清单</text>
          <view class="filter-pills">
            <view
              v-for="f in filterTypes"
              :key="f.key"
              class="type-pill"
              :class="{ 'type-pill--active': activeType === f.key }"
              @tap="activeType = f.key"
            >
              <text class="pill-text">{{ f.label }}</text>
            </view>
          </view>
        </view>

        <view v-if="filteredResources.length === 0" class="empty-resources">
          <text class="empty-res-text">暂无资源</text>
        </view>

        <view v-else class="resource-list">
          <view
            v-for="(res, index) in filteredResources"
            :key="res.id || index"
            class="resource-item"
          >
            <view class="res-icon-box" :class="'type--' + (res.type || 'video')">
              <x-icon :name="getResourceIcon(res.type)" :size="32" :color="getResourceColor(res.type)" />
            </view>
            <view class="res-info">
              <text class="res-name">{{ res.name || res.title || '资源 ' + (index + 1) }}</text>
              <view class="res-tags">
                <text class="res-type-tag">{{ getResourceTypeName(res.type) }}</text>
                <text class="res-status-tag" :class="isResourceDone(res) ? 'status--done' : 'status--pending'">
                  {{ isResourceDone(res) ? '已完成' : '待学习' }}
                </text>
              </view>
            </view>
          </view>
        </view>
      </view>

      <view class="bottom-spacer" />
    </scroll-view>

    <view class="bottom-action-bar" v-if="course">
      <view class="create-task-btn" @tap="goCreateTask">
        <x-icon name="play" :size="32" color="#FFFFFF" />
        <text class="create-btn-text">以此课程创建学习任务</text>
      </view>
    </view>
  </view>
</template>

<script>
import MoocNavBar from '../components/MoocNavBar.vue';
import XIcon from '../../components/XIcon.vue';
import api from '../api/client.js';

export default {
  name: 'MoocCourseDetail',
  components: { MoocNavBar, XIcon },
  data() {
    return {
      courseId: null,
      course: null,
      resources: [],
      isLoading: false,
      loadingProgress: 0,
      loadingTip: '正在连接后端服务',
      loadingTimer: null,
      loadVersion: 0,
      loadError: '',
      pageHidden: false,
      activeType: 'all',
      filterTypes: [
        { key: 'all', label: '全部' },
        { key: 'video', label: '视频' },
        { key: 'workid', label: '练习' },
        { key: 'document', label: '文档' }
      ]
    };
  },
  computed: {
    filteredResources() {
      if (this.activeType === 'all') return this.resources;
      return this.resources.filter((r) => r.type === this.activeType);
    },
    learnedCount() {
      return this.resources.filter(this.isResourceDone).length;
    }
  },
  onLoad(options) {
    if (options.id) {
      this.courseId = Number(options.id);
      this.loadCourse();
    }
  },
  onUnload() {
    this.loadVersion++;
    this.stopLoadingProgress();
  },
  onHide() {
    this.pageHidden = true;
    this.loadVersion++;
    this.stopLoadingProgress();
  },
  onShow() {
    if (this.pageHidden && this.courseId) this.loadCourse();
    this.pageHidden = false;
  },
  methods: {
    loadCourse(refresh = false) {
      this.stopLoadingProgress();
      const version = ++this.loadVersion;
      this.isLoading = true;
      this.loadError = '';
      this.loadingProgress = 0;
      this.loadingTip = '正在连接后端服务';
      this.pollResources(version, refresh);
    },
    async pollResources(version, refresh = false) {
      try {
        const res = await api.getCourseResources(this.courseId, refresh);
        if (version !== this.loadVersion) return;
        if (res?.course) this.course = res.course;
        this.resources = (Array.isArray(res?.resources) ? res.resources : []).map(this.normalizeResource);
        this.loadingProgress = Number(res?.progress) || 0;
        this.loadingTip = res?.message || '正在同步平台章节资源';
        if (res?.status === 'LOADING') {
          this.loadingTimer = setTimeout(() => this.pollResources(version), 2000);
          return;
        }
        if (res?.status !== 'SYNCED') throw new Error(res?.message || '章节同步失败，请重试');
        this.isLoading = false;
      } catch (err) {
        if (version !== this.loadVersion) return;
        this.loadError = err?.message || '加载详情失败，请检查后端连接';
        this.isLoading = false;
      }
    },
    stopLoadingProgress() {
      if (this.loadingTimer) {
        clearTimeout(this.loadingTimer);
        this.loadingTimer = null;
      }
    },
    getResourceIcon(type) {
      switch (type) {
        case 'video': return 'play';
        case 'workid': return 'edit';
        case 'document': return 'file-text';
        default: return 'book';
      }
    },
    getResourceColor(type) {
      switch (type) {
        case 'video': return '#2563EB';
        case 'workid': return '#9333EA';
        case 'document': return '#059669';
        default: return '#64748B';
      }
    },
    getResourceTypeName(type) {
      switch (type) {
        case 'video': return '视频';
        case 'workid': return '章节测验';
        case 'document': return '课件文档';
        case 'read': return '阅读';
        default: return '学习资源';
      }
    },
    normalizeResource(resource) {
      const completed = this.isResourceDone(resource);
      return {
        ...resource,
        completed,
        statusText: completed ? '已完成' : '待学习'
      };
    },
    isResourceDone(resource) {
      const value = resource?.completed ?? resource?.isCompleted ?? resource?.passed ?? resource?.finished;
      if (value === true || value === 1) return true;
      if (typeof value === 'string') {
        return ['1', 'true', 'done', 'finished', 'complete', 'completed', 'passed', 'success'].includes(value.trim().toLowerCase());
      }
      const state = String(resource?.state || resource?.status || '').toLowerCase();
      return ['done', 'finished', 'complete', 'completed', 'passed', 'success'].includes(state);
    },
    goCreateTask() {
      uni.navigateTo({
        url: `/moocpass/task/create?courseIds=${this.courseId}`
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
  background-color: #f8fafc;
}
.content-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 24rpx;
  box-sizing: border-box;
}
.sync-loading-card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 30rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.9);
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.06);
}
.sync-loading-head {
  display: flex;
  align-items: center;
  gap: 18rpx;
}
.sync-icon-wrap {
  width: 68rpx;
  height: 68rpx;
  border-radius: 20rpx;
  background: #eef2ff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.sync-copy {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.sync-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #0f172a;
}
.sync-desc {
  font-size: 23rpx;
  color: #64748b;
}
.sync-percent {
  font-size: 30rpx;
  font-weight: 800;
  color: #4f46e5;
}
.sync-progress-bg {
  margin-top: 28rpx;
  height: 14rpx;
  border-radius: 999rpx;
  background: #e2e8f0;
  overflow: hidden;
}
.sync-progress-fill {
  height: 100%;
  border-radius: 999rpx;
  background: linear-gradient(90deg, #4f46e5 0%, #06b6d4 100%);
  transition: width 0.35s ease;
}
.sync-steps {
  margin-top: 20rpx;
  display: flex;
  justify-content: space-between;
}
.sync-step {
  font-size: 22rpx;
  color: #94a3b8;
}
.sync-step.active {
  color: #4f46e5;
  font-weight: 600;
}
.course-summary-card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 28rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.8);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.04);
  margin-bottom: 28rpx;
}
.summary-header {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-bottom: 20rpx;
}
.detail-cover-wrap {
  width: 140rpx;
  height: 140rpx;
  border-radius: 16rpx;
  overflow: hidden;
  flex-shrink: 0;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
}
.detail-cover-img {
  width: 100%;
  height: 100%;
}
.detail-cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f1f5f9;
}
.summary-top {
  flex: 1;
}
.summary-name {
  font-size: 32rpx;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.4;
}
.teacher-row {
  display: flex;
  align-items: center;
  gap: 8rpx;
  margin-top: 10rpx;
}
.teacher-name {
  font-size: 24rpx;
  color: #64748b;
}
.progress-box {
  display: flex;
  flex-direction: column;
  gap: 10rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid #f1f5f9;
}
.progress-bar-bg {
  height: 12rpx;
  background: #e2e8f0;
  border-radius: 999rpx;
  overflow: hidden;
}
.progress-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #4f46e5 0%, #06b6d4 100%);
  border-radius: 999rpx;
}
.progress-desc-row {
  display: flex;
  justify-content: space-between;
}
.p-num {
  font-size: 22rpx;
  font-weight: 600;
  color: #4f46e5;
}
.r-count {
  font-size: 20rpx;
  color: #94a3b8;
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
.filter-pills {
  display: flex;
  gap: 8rpx;
}
.type-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  box-sizing: border-box;
}
.type-pill--active {
  background: #4f46e5;
}
.pill-text {
  font-size: 20rpx;
  color: #64748b;
  line-height: 1;
}
.type-pill--active .pill-text {
  color: #ffffff;
  font-weight: 600;
}
.empty-resources {
  background: #ffffff;
  border-radius: 24rpx;
  padding: 48rpx 20rpx;
  display: flex;
  justify-content: center;
  border: 1rpx dashed #cbd5e1;
}
.empty-res-text {
  font-size: 24rpx;
  color: #94a3b8;
}
.resource-list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.resource-item {
  background: #ffffff;
  border-radius: 20rpx;
  padding: 20rpx;
  display: flex;
  align-items: center;
  gap: 16rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.8);
}
.res-icon-box {
  width: 64rpx;
  height: 64rpx;
  border-radius: 16rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.type--video { background: #eff6ff; }
.type--workid { background: #faf5ff; }
.type--document { background: #ecfdf5; }
.res-info {
  flex: 1;
}
.res-name {
  font-size: 26rpx;
  font-weight: 500;
  color: #1e293b;
  line-height: 1.3;
}
.res-tags {
  display: flex;
  align-items: center;
  gap: 10rpx;
  margin-top: 8rpx;
}
.res-type-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18rpx;
  line-height: 1;
  color: #64748b;
  background: #f1f5f9;
  padding: 4rpx 10rpx;
  border-radius: 6rpx;
  box-sizing: border-box;
}
.res-status-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18rpx;
  line-height: 1;
  padding: 4rpx 10rpx;
  border-radius: 6rpx;
  box-sizing: border-box;
}
.status--done { background: #dcfce7; color: #15803d; }
.status--pending { background: #fef3c7; color: #b45309; }
.bottom-action-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: #ffffff;
  border-top: 1rpx solid #e2e8f0;
  padding: 20rpx 24rpx;
  padding-bottom: calc(20rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
  z-index: 50;
}
.create-task-btn {
  background: #4f46e5;
  border-radius: 24rpx;
  height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10rpx;
  box-shadow: 0 8rpx 24rpx rgba(79, 70, 229, 0.28);
}
.create-btn-text {
  font-size: 28rpx;
  font-weight: 600;
  color: #ffffff;
}
.bottom-spacer {
  height: 160rpx;
}
</style>
