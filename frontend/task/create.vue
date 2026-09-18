<template>
  <view class="page-container">
    <mooc-nav-bar title="任务配置" :show-back="true" />

    <scroll-view scroll-y class="content-scroll">
      <view class="section-card">
        <view class="card-title-row">
          <x-icon name="layers" :size="32" color="#4F46E5" />
          <text class="card-title">已选课程 ({{ targetCourses.length }} 门)</text>
        </view>
        <view class="selected-courses-list">
          <view v-for="c in targetCourses" :key="c.id" class="course-chip">
            <text class="course-chip-name">{{ c.name }}</text>
            <text class="course-chip-teacher" v-if="c.teacher">{{ c.teacher }}</text>
          </view>
        </view>
      </view>

      <view class="section-card">
        <view class="card-title-row">
          <x-icon name="sliders" :size="32" color="#4F46E5" />
          <text class="card-title">学习参数设置</text>
        </view>

        <view class="form-row">
          <text class="form-row-label">学习资源范围</text>
          <view class="pill-group">
            <view
              class="pill-opt"
              :class="{ 'pill-opt--active': form.onlyUncompleted }"
              @tap="form.onlyUncompleted = true"
            >
              <text class="pill-opt-text">仅未完成</text>
            </view>
            <view
              class="pill-opt"
              :class="{ 'pill-opt--active': !form.onlyUncompleted }"
              @tap="form.onlyUncompleted = false"
            >
              <text class="pill-opt-text">全部支持资源</text>
            </view>
          </view>
        </view>

        <view class="form-row">
          <text class="form-row-label">包含资源类型</text>
          <view class="checkbox-group">
            <view
              v-for="item in resourceTypeOptions"
              :key="item.key"
              class="type-check-item"
              :class="{ 'type-check-item--active': form.resourceTypes.includes(item.key) }"
              @tap="toggleResourceType(item.key)"
            >
              <x-icon
                :name="form.resourceTypes.includes(item.key) ? 'check-square' : 'square'"
                :size="26"
                :color="form.resourceTypes.includes(item.key) ? '#4F46E5' : '#94A3B8'"
              />
              <text class="type-check-text">{{ item.label }}</text>
            </view>
          </view>
        </view>

        <view class="form-row">
          <text class="form-row-label">播放倍速</text>
          <view class="pill-group">
            <view
              v-for="s in speedOptions"
              :key="s"
              class="pill-opt"
              :class="{ 'pill-opt--active': form.speed === s }"
              @tap="handleSpeedChange(s)"
            >
              <text class="pill-opt-text">{{ s }}x</text>
            </view>
          </view>
        </view>

        <view class="form-row">
          <text class="form-row-label">章节切换间隔</text>
          <view class="pill-group">
            <view
              v-for="sec in [3, 5, 10]"
              :key="sec"
              class="pill-opt"
              :class="{ 'pill-opt--active': form.minIntervalSeconds === sec }"
              @tap="form.minIntervalSeconds = sec"
            >
              <text class="pill-opt-text">{{ sec }} 秒</text>
            </view>
          </view>
        </view>
      </view>

      <view class="section-card">
        <view class="card-title-row">
          <x-icon name="cpu" :size="32" color="#9333EA" />
          <text class="card-title">答题与模型策略</text>
        </view>

        <view class="form-row">
          <text class="form-row-label">答题模式</text>
          <view class="pill-group">
            <view
              v-for="opt in answerModes"
              :key="opt.key"
              class="pill-opt"
              :class="{ 'pill-opt--active': form.answerMode === opt.key }"
              @tap="selectAnswerMode(opt.key)"
            >
              <text class="pill-opt-text">{{ opt.label }}</text>
            </view>
          </view>
        </view>

        <view v-if="form.answerMode !== 'OFF'">
          <view class="form-row">
            <text class="form-row-label">选用 API / 题库配置</text>
            <view v-if="profiles.length === 0" class="no-profile-tip" @tap="goProfiles">
              <text class="tip-link">尚未配置答题 API，点击去配置</text>
              <x-icon name="chevron-right" :size="24" color="#4F46E5" />
            </view>
            <picker
              v-else
              :range="profiles"
              range-key="name"
              :value="selectedProfileIndex"
              @change="onProfileChange"
            >
              <view class="picker-display">
                <text class="picker-value">{{ currentProfileName }}</text>
                <x-icon name="chevron-down" :size="28" color="#64748B" />
              </view>
            </picker>
          </view>

          <view class="form-row">
            <text class="form-row-label">练习提交动作</text>
            <view class="pill-group">
              <view
                class="pill-opt"
                :class="{ 'pill-opt--active': form.practiceAction === 'SUBMIT' }"
                @tap="form.practiceAction = 'SUBMIT'"
              >
                <text class="pill-opt-text">自动提交</text>
              </view>
              <view
                class="pill-opt"
                :class="{ 'pill-opt--active': form.practiceAction === 'SAVE' }"
                @tap="form.practiceAction = 'SAVE'"
              >
                <text class="pill-opt-text">保存草稿</text>
              </view>
            </view>
          </view>
          <view class="form-row">
            <text class="form-row-label">无确定答案时</text>
            <view class="pill-group">
              <view class="pill-opt" :class="{ 'pill-opt--active': !form.aiBestEffort }" @tap="form.aiBestEffort = false">
                <text class="pill-opt-text">留空</text>
              </view>
              <view class="pill-opt" :class="{ 'pill-opt--active': form.aiBestEffort }" @tap="selectBestEffort">
                <text class="pill-opt-text">AI 尽力作答</text>
              </view>
            </view>
          </view>
        </view>
      </view>

      <view class="preflight-card" v-if="preflightResult">
        <view class="preflight-header">
          <x-icon
            :name="preflightValid ? 'check-circle' : 'warning'"
            :size="30"
            :color="preflightValid ? '#059669' : '#D97706'"
          />
          <text class="preflight-title">
            {{ preflightValid ? '预检通过' : '需要调整' }}
          </text>
        </view>
        <view class="preflight-items" v-if="preflightResult.items">
          <view
            v-for="(item, idx) in preflightResult.items"
            :key="idx"
            class="preflight-item-row"
          >
            <text class="pf-course-name">{{ item.courseName || ('课程 #' + item.courseId) }}</text>
            <text class="pf-status" :class="item.valid ? 'pf-ready' : 'pf-error'">
              {{ item.valid ? '通过' : (item.message || '存在冲突') }}
            </text>
          </view>
        </view>
      </view>

      <view class="bottom-spacer" />
    </scroll-view>

    <view class="bottom-action-bar">
      <button class="submit-task-btn" :loading="isCreating" @tap="submitBatch">
        <x-icon name="play" :size="32" color="#FFFFFF" />
        <text class="submit-btn-text">立即入队并启动 ({{ targetCourses.length }}门)</text>
      </button>
    </view>
  </view>
</template>

<script>
import MoocNavBar from '../components/MoocNavBar.vue';
import XIcon from '../../components/XIcon.vue';
import api from '../api/client.js';

export default {
  name: 'MoocTaskCreate',
  components: { MoocNavBar, XIcon },
  data() {
    return {
      courseIds: [],
      targetCourses: [],
      profiles: [],
      selectedProfileIndex: 0,
      speedOptions: [1.0, 1.25, 1.5, 2.0],
      answerModes: [
        { key: 'OFF', label: '关闭' },
        { key: 'TIKU', label: '题库' },
        { key: 'AI', label: 'AI模型' },
        { key: 'TIKU_THEN_AI', label: '题库+AI' }
      ],
      resourceTypeOptions: [
        { key: 'video', label: '视频' },
        { key: 'workid', label: '章节测验' },
        { key: 'document', label: '课件文档' },
        { key: 'read', label: '图文阅读' }
      ],
      preflightResult: null,
      isCreating: false,
      form: {
        onlyUncompleted: true,
        resourceTypes: ['video', 'workid', 'document', 'read'],
        speed: 1.0,
        minIntervalSeconds: 3,
        answerMode: 'OFF',
        practiceAction: 'SUBMIT',
        aiBestEffort: false,
        autoSubmitPractice: true,
        profileId: null
      }
    };
  },
  computed: {
    currentProfileName() {
      if (this.profiles.length === 0) return '无可用配置';
      return this.profiles[this.selectedProfileIndex]?.name || '选择配置';
    },
    preflightValid() {
      const items = this.preflightResult?.items || [];
      return items.length > 0 && items.every((item) => item.valid);
    }
  },
  onLoad(options) {
    if (options.courseIds) {
      this.courseIds = options.courseIds.split(',').map((id) => Number(id.trim())).filter(Boolean);
    }
    this.initData();
  },
  methods: {
    async initData() {
      try {
        const [crss, profs, prefs] = await Promise.all([
          api.getCourses({}),
          api.getAnswerProfiles(),
          api.getPreferences()
        ]);
        this.profiles = Array.isArray(profs) ? profs : (profs?.items || []);
        const allCourses = Array.isArray(crss) ? crss : (crss?.items || []);
        this.targetCourses = allCourses.filter((c) => this.courseIds.includes(c.id));
        if (this.targetCourses.length === 0 && allCourses.length > 0 && this.courseIds.length > 0) {
          this.targetCourses = allCourses.slice(0, 1);
        }

        if (prefs) {
          if (prefs.speed) this.form.speed = prefs.speed;
          if (prefs.minIntervalSeconds) this.form.minIntervalSeconds = prefs.minIntervalSeconds;
          if (prefs.resourceTypes) this.form.resourceTypes = prefs.resourceTypes;
          if (prefs.onlyUncompleted !== undefined) this.form.onlyUncompleted = prefs.onlyUncompleted;
          if (prefs.aiBestEffort !== undefined) this.form.aiBestEffort = prefs.aiBestEffort;
        }

        const defaultProf = this.profiles.find((p) => p.isDefault) || this.profiles[0];
        if (defaultProf) {
          this.form.profileId = defaultProf.id;
          this.selectedProfileIndex = this.profiles.indexOf(defaultProf);
          if (defaultProf.kind !== 'AI') this.form.aiBestEffort = false;
        }

        this.triggerPreflight();
      } catch (err) {
        uni.showToast({ title: err?.message || '初始化失败', icon: 'none' });
      }
    },
    handleSpeedChange(s) {
      if (this.form.speed === s) return;
      this.form.speed = s;
      if (s !== 1.0) {
        uni.showToast({
          title: '调整播放倍率可能会导致任务点无法完成，请注意',
          icon: 'none',
          duration: 3000
        });
      }
    },
    toggleResourceType(type) {
      if (type === 'workid' && this.form.answerMode === 'OFF') {
        this.form.answerMode = 'TIKU';
        uni.showToast({ title: '已开启答题模式以处理测验', icon: 'none' });
      }
      const idx = this.form.resourceTypes.indexOf(type);
      if (idx >= 0) {
        if (this.form.resourceTypes.length > 1) {
          this.form.resourceTypes.splice(idx, 1);
        } else {
          uni.showToast({ title: '至少保留一种资源类型', icon: 'none' });
        }
      } else {
        this.form.resourceTypes.push(type);
      }
      this.triggerPreflight();
    },
    selectAnswerMode(mode) {
      this.form.answerMode = mode;
      if (mode === 'OFF') {
        if (this.form.resourceTypes.includes('workid') && this.form.resourceTypes.length > 1) {
          this.form.resourceTypes = this.form.resourceTypes.filter((t) => t !== 'workid');
        }
      } else {
        if (!this.form.resourceTypes.includes('workid')) {
          this.form.resourceTypes.push('workid');
        }
        if (!this.form.practiceAction) {
          this.form.practiceAction = 'SUBMIT';
        }
      }
      this.triggerPreflight();
    },
    onProfileChange(e) {
      this.selectedProfileIndex = e.detail.value;
      const p = this.profiles[this.selectedProfileIndex];
      this.form.profileId = p ? p.id : null;
      if (this.form.aiBestEffort && p?.kind !== 'AI') this.form.aiBestEffort = false;
      this.triggerPreflight();
    },
    selectBestEffort() {
      if (this.profiles[this.selectedProfileIndex]?.kind !== 'AI') {
        uni.showToast({ title: '请先选择 AI 模型配置', icon: 'none' });
        return;
      }
      this.form.aiBestEffort = true;
      this.triggerPreflight();
    },
    goProfiles() {
      uni.navigateTo({ url: '/moocpass/answer/models' });
    },
    getBatchInput() {
      const isAutoAnswer = this.form.answerMode !== 'OFF';
      let types = [...this.form.resourceTypes];
      if (!isAutoAnswer) {
        types = types.filter((t) => t !== 'workid');
      }
      return {
        courseIds: this.targetCourses.map((c) => c.id),
        settings: {
          resourceIds: [],
          resourceTypes: types.length > 0 ? types : ['video', 'document', 'read'],
          speed: Number(this.form.speed) || 1.0,
          autoAnswer: isAutoAnswer,
          answerMode: this.form.practiceAction || (this.form.autoSubmitPractice ? 'SUBMIT' : 'SAVE'),
          aiBestEffort: isAutoAnswer && this.form.aiBestEffort,
          answerProfileId: isAutoAnswer ? this.form.profileId : null,
          answerVersionId: null,
          fallbackProfileId: null,
          fallbackVersionId: null,
          coverRate: 1.0,
          maxRetries: 2,
          startTime: '00:00',
          endTime: '00:00'
        }
      };
    },
    async triggerPreflight() {
      if (this.targetCourses.length === 0) return;
      try {
        const res = await api.preflightBatch(this.getBatchInput());
        this.preflightResult = res;
      } catch (_) {}
    },
    async submitBatch() {
      if (this.targetCourses.length === 0) {
        uni.showToast({ title: '未选择任何课程', icon: 'none' });
        return;
      }
      if (this.form.answerMode !== 'OFF' && !this.form.profileId) {
        uni.showToast({ title: '启用答题需先选择答题配置', icon: 'none' });
        return;
      }

      this.isCreating = true;
      const idempotencyKey = 'mp_batch_' + Date.now() + '_' + Math.random().toString(36).substring(2, 8);
      try {
        await api.createBatch(this.getBatchInput(), idempotencyKey);
        uni.showToast({ title: '任务创建成功，已入队', icon: 'success' });
        setTimeout(() => {
          uni.redirectTo({ url: '/moocpass/task/list' });
        }, 1000);
      } catch (err) {
        uni.showToast({ title: err?.message || '创建任务失败', icon: 'none' });
      }
      this.isCreating = false;
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
.section-card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 24rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.8);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.04);
  margin-bottom: 24rpx;
}
.card-title-row {
  display: flex;
  align-items: center;
  gap: 10rpx;
  margin-bottom: 20rpx;
}
.card-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #0f172a;
}
.selected-courses-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.course-chip {
  background: #f1f5f9;
  border-radius: 14rpx;
  padding: 8rpx 16rpx;
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  box-sizing: border-box;
}
.course-chip-name {
  font-size: 24rpx;
  font-weight: 500;
  color: #1e293b;
  line-height: 1;
}
.course-chip-teacher {
  font-size: 20rpx;
  color: #64748b;
  line-height: 1;
}
.form-row {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  margin-bottom: 24rpx;
}
.form-row:last-child {
  margin-bottom: 0;
}
.form-row-label {
  font-size: 24rpx;
  font-weight: 600;
  color: #334155;
}
.pill-group {
  display: flex;
  gap: 12rpx;
  flex-wrap: wrap;
}
.pill-opt {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 10rpx 24rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  border: 2rpx solid transparent;
  box-sizing: border-box;
}
.pill-opt--active {
  background: #eef2ff;
  border-color: #4f46e5;
}
.pill-opt-text {
  font-size: 22rpx;
  color: #475569;
  font-weight: 500;
  line-height: 1;
}
.pill-opt--active .pill-opt-text {
  color: #4f46e5;
  font-weight: 600;
}
.checkbox-group {
  display: flex;
  gap: 16rpx;
  flex-wrap: wrap;
}
.type-check-item {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  background: #f8fafc;
  padding: 8rpx 16rpx;
  border-radius: 12rpx;
  border: 1rpx solid #e2e8f0;
  box-sizing: border-box;
}
.type-check-item--active {
  border-color: #4f46e5;
  background: #eef2ff;
}
.type-check-text {
  font-size: 22rpx;
  color: #334155;
  line-height: 1;
}
.no-profile-tip {
  display: flex;
  align-items: center;
  gap: 6rpx;
  padding: 16rpx 20rpx;
  background: #fef2f2;
  border-radius: 16rpx;
}
.tip-link {
  font-size: 24rpx;
  color: #dc2626;
  font-weight: 500;
}
.picker-display {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 80rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 18rpx;
  padding: 0 20rpx;
}
.picker-value {
  font-size: 26rpx;
  color: #0f172a;
  font-weight: 500;
}
.preflight-card {
  background: #f0fdf4;
  border-radius: 24rpx;
  padding: 20rpx;
  border: 1rpx solid #bbf7d0;
  margin-bottom: 24rpx;
}
.preflight-header {
  display: flex;
  align-items: center;
  gap: 10rpx;
  margin-bottom: 12rpx;
}
.preflight-title {
  font-size: 24rpx;
  font-weight: 600;
  color: #15803d;
}
.preflight-items {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  padding-top: 10rpx;
  border-top: 1rpx solid #dcfce7;
}
.preflight-item-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pf-course-name {
  font-size: 22rpx;
  color: #334155;
}
.pf-status {
  font-size: 20rpx;
  font-weight: 500;
}
.pf-ready { color: #15803d; }
.pf-error { color: #b91c1c; }
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
.submit-task-btn {
  background: #4f46e5;
  border-radius: 24rpx;
  height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10rpx;
  box-shadow: 0 8rpx 24rpx rgba(79, 70, 229, 0.28);
}
.submit-btn-text {
  font-size: 28rpx;
  font-weight: 600;
  color: #ffffff;
}
.bottom-spacer {
  height: 160rpx;
}
</style>
