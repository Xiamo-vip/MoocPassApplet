<template>
  <view class="mooc-nav" :style="{ paddingTop: statusBarHeight + 'px', backgroundColor: bgColor }">
    <view class="mooc-nav-content" :style="{ height: navBarHeight + 'px', paddingRight: menuButtonWidth + 'px' }">
      <view class="left-action" v-if="showBack" @tap="handleBack">
        <x-icon name="back" :size="38" color="#1e293b" />
      </view>
      <view class="title-wrap" :class="{ 'with-back': showBack }">
        <text class="nav-title">{{ title }}</text>
        <text class="nav-sub" v-if="subtitle">{{ subtitle }}</text>
      </view>
      <view class="right-actions">
        <slot name="right" />
      </view>
    </view>
  </view>
</template>

<script>
import XIcon from '../../components/XIcon.vue';

export default {
  name: 'MoocNavBar',
  components: { XIcon },
  props: {
    title: { type: String, default: 'MoocPass' },
    subtitle: { type: String, default: '' },
    showBack: { type: Boolean, default: true },
    backUrl: { type: String, default: '' },
    bgColor: { type: String, default: '#F8FAFC' }
  },
  data() {
    let statusBarHeight = 20;
    let navBarHeight = 44;
    let menuButtonWidth = 90;
    try {
      const sys = uni.getSystemInfoSync();
      statusBarHeight = sys.statusBarHeight || 20;
      // #ifdef MP-WEIXIN
      const menu = uni.getMenuButtonBoundingClientRect();
      if (menu && menu.height) {
        const margin = menu.top - statusBarHeight;
        navBarHeight = menu.height + margin * 2;
        menuButtonWidth = sys.windowWidth - menu.left + 12;
      }
      // #endif
    } catch (_) {}
    return {
      statusBarHeight,
      navBarHeight,
      menuButtonWidth
    };
  },
  mounted() {
    try {
      const sys = uni.getSystemInfoSync();
      this.statusBarHeight = sys.statusBarHeight || 20;
      // #ifdef MP-WEIXIN
      const menu = uni.getMenuButtonBoundingClientRect();
      if (menu && menu.height) {
        const margin = menu.top - this.statusBarHeight;
        this.navBarHeight = menu.height + margin * 2;
        this.menuButtonWidth = sys.windowWidth - menu.left + 12;
      }
      // #endif
    } catch (_) {}
  },
  methods: {
    handleBack() {
      if (this.backUrl) {
        uni.reLaunch({ url: this.backUrl });
        return;
      }
      const pages = getCurrentPages();
      if (pages.length > 1) {
        uni.navigateBack();
      } else {
        uni.reLaunch({ url: '/pages/utils/utils' });
      }
    }
  }
};
</script>

<style scoped>
.mooc-nav {
  position: relative;
  flex-shrink: 0;
  width: 100%;
  z-index: 100;
  border-bottom: 1rpx solid rgba(148, 163, 184, 0.12);
  box-sizing: border-box;
}
.mooc-nav-content {
  display: flex;
  align-items: center;
  padding-left: 24rpx;
  position: relative;
}
.left-action {
  width: 64rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 999rpx;
  background-color: rgba(241, 245, 249, 0.8);
  margin-right: 16rpx;
  flex-shrink: 0;
}
.title-wrap {
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.nav-title {
  font-size: 32rpx;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: 0.5rpx;
}
.nav-sub {
  font-size: 20rpx;
  color: #64748b;
  margin-top: 2rpx;
}
.right-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 16rpx;
}
</style>
