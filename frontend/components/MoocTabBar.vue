<template>
  <view class="mooc-tab-bar">
    <view
      v-for="item in tabs"
      :key="item.key"
      class="tab-item"
      :class="{ 'tab-item--active': current === item.key }"
      @tap="switchTab(item)"
    >
      <view class="icon-container" :class="{ 'icon-container--active': current === item.key }">
        <x-icon
          :name="item.icon"
          :size="38"
          :color="current === item.key ? '#4F46E5' : '#64748B'"
        />
      </view>
      <text class="tab-label" :class="{ 'tab-label--active': current === item.key }">{{ item.label }}</text>
    </view>
  </view>
</template>

<script>
import XIcon from '../../components/XIcon.vue';

export default {
  name: 'MoocTabBar',
  components: { XIcon },
  props: {
    current: { type: String, default: 'overview' }
  },
  data() {
    return {
      tabs: [
        { key: 'overview', label: '概览', icon: 'home', url: '/moocpass/index/index' },
        { key: 'courses', label: '选课', icon: 'book', url: '/moocpass/courses/courses' },
        { key: 'tasks', label: '任务', icon: 'sliders', url: '/moocpass/task/list' },
        { key: 'mine', label: '我的', icon: 'person', url: '/moocpass/profile/profile' }
      ]
    };
  },
  methods: {
    switchTab(item) {
      if (this.current === item.key) return;
      uni.redirectTo({ url: item.url });
    }
  }
};
</script>

<style scoped>
.mooc-tab-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: 110rpx;
  padding-bottom: constant(safe-area-inset-bottom);
  padding-bottom: env(safe-area-inset-bottom);
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(20px);
  display: flex;
  align-items: center;
  justify-content: space-around;
  border-top: 1rpx solid rgba(226, 232, 240, 0.8);
  z-index: 99;
}
.tab-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 8rpx 0;
}
.icon-container {
  width: 72rpx;
  height: 48rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 24rpx;
  transition: all 0.2s ease;
}
.icon-container--active {
  background: rgba(79, 70, 229, 0.12);
}
.tab-label {
  font-size: 20rpx;
  color: #64748b;
  margin-top: 4rpx;
  font-weight: 500;
  transition: color 0.2s ease;
}
.tab-label--active {
  color: #4f46e5;
  font-weight: 600;
}
</style>
