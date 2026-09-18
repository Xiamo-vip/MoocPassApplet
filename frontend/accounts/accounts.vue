<template>
  <view class="page-container">
    <mooc-nav-bar title="网课账号" :show-back="true" bg-color="#F8F9FC">
      <template #right>
        <view class="nav-icon-btn" @tap="fetchData">
          <x-icon :name="isLoading ? 'spinner' : 'refresh'" :size="36" color="#475569" />
        </view>
      </template>
    </mooc-nav-bar>

    <scroll-view scroll-y class="content-scroll">
      <!-- 支持平台 -->
      <view class="section-title">支持平台</view>
      <view class="platform-row">
        <view
          v-for="p in platforms"
          :key="p.code"
          class="platform-card"
          :class="{ 'platform-card--disabled': !p.enabled }"
        >
          <view class="platform-icon-box">
            <x-icon :name="p.code === 'chaoxing' ? 'book' : 'graduation-cap'" :size="32" color="#4F46E5" />
          </view>
          <text class="platform-name">{{ p.name }}</text>
          <view class="platform-pill" :class="p.enabled ? 'pill--on' : 'pill--off'">
            <text class="pill-text">{{ p.enabled ? '已开放' : '待开放' }}</text>
          </view>
        </view>
      </view>

      <!-- 已绑账号 -->
      <view class="section-header">
        <text class="section-title">已绑账号</text>
        <view v-if="availablePlatforms.length" class="m3-tonal-btn" @tap="openBindDialog(null)">
          <x-icon name="add" :size="24" color="#4F46E5" />
          <text class="m3-tonal-text">添加账号</text>
        </view>
      </view>

      <!-- 空状态 -->
      <view v-if="accounts.length === 0" class="empty-state">
        <view class="empty-icon-box">
          <x-icon name="shield" :size="48" color="#94A3B8" />
        </view>
        <text class="empty-title">暂未绑定网课账号</text>
        <button v-if="availablePlatforms.length" class="empty-btn" @tap="openBindDialog(null)">
          <text class="empty-btn-text">立即绑定</text>
        </button>
      </view>

      <!-- 账号列表 -->
      <view v-else class="account-list">
        <view v-for="acc in accounts" :key="acc.id" class="m3-card">
          <view class="card-main">
            <view class="acc-avatar">
              <x-icon :name="acc.platformCode === 'chaoxing' ? 'book' : 'graduation-cap'" :size="36" color="#4F46E5" />
            </view>
            <view class="acc-content">
              <view class="acc-header">
                <text class="acc-username">{{ acc.username }}</text>
                <view class="status-chip" :class="'chip--' + (acc.status || 'active').toLowerCase()">
                  <text class="chip-text">{{ acc.status === 'ACTIVE' ? '正常' : acc.status === 'UNBOUND' ? '已停用' : '凭证失效' }}</text>
                </view>
              </view>
              <view class="acc-sub">
                <text class="acc-platform">{{ getPlatformName(acc.platformCode) }}</text>
                <text class="acc-dot" v-if="acc.lastSyncedAt">·</text>
                <text class="acc-sync-time" v-if="acc.lastSyncedAt">{{ formatSyncTime(acc.lastSyncedAt) }} 同步</text>
              </view>
            </view>
          </view>

          <view class="card-actions">
            <view v-if="acc.status === 'ACTIVE'" class="action-btn action-btn--primary" @tap="syncCourses(acc)">
              <x-icon :name="syncingId === acc.id ? 'spinner' : 'refresh'" :size="26" color="#4F46E5" />
              <text class="action-text text-primary">同步课程</text>
            </view>
            <view v-if="acc.status !== 'UNBOUND'" class="action-btn" @tap="verifyAccount(acc)">
              <x-icon name="shield-check" :size="26" color="#475569" />
              <text class="action-text">检测</text>
            </view>
            <view class="action-btn" @tap="openBindDialog(acc)">
              <x-icon name="key" :size="26" color="#475569" />
              <text class="action-text">重认证</text>
            </view>
            <view v-if="acc.status !== 'UNBOUND'" class="action-btn" @tap="deleteAccount(acc)">
              <x-icon name="delete" :size="26" color="#DC2626" />
              <text class="action-text danger-text">停用</text>
            </view>
          </view>
        </view>
      </view>

      <view class="bottom-spacer" />
    </scroll-view>

    <!-- 绑定 / 重新认证弹窗 (Material 3 Bottom Sheet) -->
    <view class="modal-mask" v-if="showModal" @tap="closeModal">
      <view class="modal-sheet" @tap.stop>
        <view class="sheet-header">
          <text class="sheet-title">{{ editId ? '重新认证' : '绑定账号' }}</text>
          <view class="close-btn" @tap="closeModal">
            <x-icon name="close" :size="32" color="#64748B" />
          </view>
        </view>

        <view class="form-body">
          <view class="form-item" v-if="!editId">
            <text class="form-label">网课平台</text>
            <view class="platform-segmented">
              <view
                v-for="p in availablePlatforms"
                :key="p.code"
                class="segmented-btn"
                :class="{ 'segmented-btn--active': form.platformCode === p.code }"
                @tap="form.platformCode = p.code"
              >
                <text class="segmented-btn-text">{{ p.name }}</text>
              </view>
            </view>
          </view>

          <view v-if="form.platformCode === 'chaoxing'">
            <view class="form-item">
              <text class="form-label">账号</text>
              <input
                type="text"
                class="m3-input"
                v-model="form.username"
                :disabled="!!editId"
                placeholder="手机号 / 账号"
                placeholder-class="input-placeholder"
              />
            </view>
            <view class="form-item">
              <text class="form-label">密码</text>
              <view class="password-wrap">
                <input
                  :password="!showPassword"
                  class="m3-input password-input"
                  v-model="form.password"
                  placeholder="登录密码"
                  placeholder-class="input-placeholder"
                />
                <view class="eye-toggle" @tap="showPassword = !showPassword">
                  <x-icon :name="showPassword ? 'eye' : 'eye-off'" :size="32" color="#94A3B8" />
                </view>
              </view>
            </view>
          </view>

          <view v-else-if="form.platformCode === 'zhy'">
            <view class="form-item">
              <text class="form-label">账号备注</text>
              <input
                type="text"
                class="m3-input"
                v-model="form.username"
                :disabled="!!editId"
                placeholder="如: 我的吱叫云"
                placeholder-class="input-placeholder"
              />
            </view>
            <view class="form-item">
              <text class="form-label">Token 凭证</text>
              <textarea
                class="m3-textarea"
                v-model="form.token"
                placeholder="authorization 凭证"
                placeholder-class="input-placeholder"
              ></textarea>
            </view>
          </view>

          <button class="m3-submit-btn" :loading="isSubmitting" @tap="submitBind">
            <text class="submit-text">{{ editId ? '确认更新' : '确认绑定' }}</text>
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import MoocNavBar from '../components/MoocNavBar.vue';
import XIcon from '../../components/XIcon.vue';
import api from '../api/client.js';

export default {
  name: 'MoocAccounts',
  components: { MoocNavBar, XIcon },
  data() {
    return {
      platforms: [],
      accounts: [],
      isLoading: false,
      syncingId: null,
      showModal: false,
      editId: null,
      showPassword: false,
      isSubmitting: false,
      form: {
        platformCode: 'chaoxing',
        username: '',
        password: '',
        token: ''
      }
    };
  },
  computed: {
    availablePlatforms() {
      return this.platforms.filter((p) => p.enabled && !this.accounts.some((acc) => acc.platformCode === p.code));
    }
  },
  mounted() {
    this.fetchData();
  },
  methods: {
    async fetchData() {
      this.isLoading = true;
      try {
        const [pRes, aRes] = await Promise.all([
          api.getPlatforms(),
          api.getAccounts()
        ]);
        this.platforms = (Array.isArray(pRes) ? pRes : []).map(p => ({
          ...p,
          name: this.maskPlatformName(p.name, p.code)
        }));
        this.accounts = Array.isArray(aRes) ? aRes : [];
      } catch (err) {
        uni.showToast({ title: err?.message || '加载失败', icon: 'none' });
      }
      this.isLoading = false;
    },
    maskPlatformName(name, code) {
      if (code === 'chaoxing') return '学不通';
      if (code === 'zhy' || code === 'zjy' || code === 'icve') return '吱叫云';
      if (!name) return code || '';
      let res = String(name);
      res = res.replace(/超星学习通/g, '学不通');
      res = res.replace(/超星/g, '学不通');
      res = res.replace(/学习通/g, '学不通');
      res = res.replace(/职教云/g, '吱叫云');
      return res;
    },
    getPlatformName(code) {
      if (code === 'chaoxing') return '学不通';
      if (code === 'zhy' || code === 'zjy' || code === 'icve') return '吱叫云';
      const p = this.platforms.find((item) => item.code === code);
      return p ? this.maskPlatformName(p.name, code) : (code || '未知平台');
    },
    openBindDialog(acc) {
      if (!acc && !this.availablePlatforms.length) return;
      this.editId = acc ? acc.id : null;
      this.showPassword = false;
      if (acc) {
        this.form = {
          platformCode: acc.platformCode,
          username: acc.username,
          password: '',
          token: ''
        };
      } else {
        const defaultPlatform = this.availablePlatforms[0].code;
        this.form = {
          platformCode: defaultPlatform,
          username: '',
          password: '',
          token: ''
        };
      }
      this.showModal = true;
    },
    closeModal() {
      this.showModal = false;
    },
    async submitBind() {
      if (this.form.platformCode === 'chaoxing') {
        if (!this.form.username.trim() || !this.form.password.trim()) {
          uni.showToast({ title: '请输入账号与密码', icon: 'none' });
          return;
        }
      } else if (this.form.platformCode === 'zhy') {
        if (!this.form.token.trim()) {
          uni.showToast({ title: '请输入平台 Token', icon: 'none' });
          return;
        }
      }

      this.isSubmitting = true;
      try {
        if (this.editId) {
          await api.reauthAccount(this.editId, this.form);
          uni.showToast({ title: '认证已更新', icon: 'success' });
        } else {
          await api.bindAccount(this.form);
          uni.showToast({ title: '绑定成功', icon: 'success' });
        }
        this.closeModal();
        this.fetchData();
      } catch (err) {
        uni.showToast({ title: err?.message || '认证失败，请检查账号密码', icon: 'none' });
      }
      this.isSubmitting = false;
    },
    async verifyAccount(acc) {
      uni.showLoading({ title: '检测中...' });
      try {
        const res = await api.verifyAccount(acc.id);
        uni.hideLoading();
        if (res.status === 'ACTIVE') {
          uni.showToast({ title: '账号认证正常', icon: 'success' });
        } else {
          uni.showToast({ title: '凭证已失效，请重新认证', icon: 'none' });
        }
        this.fetchData();
      } catch (err) {
        uni.hideLoading();
        uni.showToast({ title: err?.message || '检测失败', icon: 'none' });
      }
    },
    async syncCourses(acc) {
      this.syncingId = acc.id;
      uni.showLoading({ title: '正在刷新课程...' });
      try {
        const res = await api.syncAccount(acc.id);
        uni.hideLoading();
        uni.showToast({ title: res?.message || '同步完成', icon: 'success' });
        this.fetchData();
        setTimeout(() => {
          uni.navigateTo({ url: `/moocpass/courses/courses?accountId=${acc.id}` });
        }, 500);
      } catch (err) {
        uni.hideLoading();
        uni.showToast({ title: err?.message || '同步失败', icon: 'none' });
      } finally {
        this.syncingId = null;
      }
    },
    deleteAccount(acc) {
      uni.showModal({
        title: '停用账号确认',
        content: `确定停用账号 ${acc.username} 吗？此平台仍只能重新认证这个账号，无法绑定其他账号。`,
        confirmColor: '#E11D48',
        success: async (res) => {
          if (res.confirm) {
            try {
              await api.deleteAccount(acc.id);
              uni.showToast({ title: '已停用', icon: 'success' });
              this.fetchData();
            } catch (err) {
              uni.showToast({ title: err?.message || '停用失败', icon: 'none' });
            }
          }
        }
      });
    },
    formatSyncTime(timeStr) {
      if (!timeStr) return '';
      if (typeof timeStr === 'string') {
        if (timeStr.includes('T') || timeStr.endsWith('Z')) {
          const d = new Date(timeStr);
          if (!isNaN(d.getTime())) {
            const pad = n => n < 10 ? '0' + n : n;
            return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
          }
        }
        if (timeStr.length >= 16) {
          return timeStr.substring(5, 16);
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
  background-color: #f8f9fc;
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
  padding: 20rpx 28rpx;
  box-sizing: border-box;
}

/* 标题样式 - 与 profile 一致 */
.section-title {
  font-size: 24rpx;
  font-weight: 600;
  color: #64748b;
  margin: 16rpx 8rpx 14rpx;
  letter-spacing: 0.5rpx;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 28rpx 0 14rpx;
}

.section-header .section-title {
  margin: 0 8rpx;
}

/* 支持平台 (M3 横向简约卡片) */
.platform-row {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-bottom: 12rpx;
}

.platform-card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 20rpx 24rpx;
  display: flex;
  align-items: center;
  gap: 16rpx;
  border: 1rpx solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.03);
}

.platform-card--disabled {
  opacity: 0.6;
}

.platform-icon-box {
  width: 64rpx;
  height: 64rpx;
  border-radius: 18rpx;
  background: #eef2ff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.platform-name {
  flex: 1;
  font-size: 28rpx;
  font-weight: 600;
  color: #1e293b;
}

.platform-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  box-sizing: border-box;
}

.pill--on {
  background: #dcfce7;
}

.pill--on .pill-text {
  color: #15803d;
}

.pill--off {
  background: #f1f5f9;
}

.pill--off .pill-text {
  color: #94a3b8;
}

.pill-text {
  font-size: 20rpx;
  font-weight: 600;
  line-height: 1;
}

/* M3 色调按钮 (Tonal Button) */
.m3-tonal-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6rpx;
  background: #eef2ff;
  padding: 8rpx 20rpx;
  border-radius: 999rpx;
  box-sizing: border-box;
}

.m3-tonal-text {
  font-size: 22rpx;
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

/* 账号列表与卡片 (Material 3 Surface Container) */
.account-list {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.m3-card {
  background: #ffffff;
  border-radius: 32rpx;
  padding: 24rpx 28rpx;
  border: 1rpx solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.03);
}

.card-main {
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.acc-avatar {
  width: 76rpx;
  height: 76rpx;
  border-radius: 22rpx;
  background: #eef2ff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.acc-content {
  flex: 1;
  min-width: 0;
}

.acc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.acc-username {
  font-size: 28rpx;
  font-weight: 600;
  color: #0f172a;
}

.acc-sub {
  display: flex;
  align-items: center;
  gap: 8rpx;
  margin-top: 6rpx;
}

.acc-platform {
  font-size: 22rpx;
  color: #64748b;
}

.acc-dot {
  font-size: 20rpx;
  color: #cbd5e1;
}

.acc-sync-time {
  font-size: 22rpx;
  color: #94a3b8;
}

/* 状态标签 */
.status-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  box-sizing: border-box;
}

.chip--active {
  background: #dcfce7;
  color: #15803d;
}

.chip--expired {
  background: #fee2e2;
  color: #b91c1c;
}

.chip--unbound {
  background: #f1f5f9;
  color: #64748b;
}

.chip-text {
  font-size: 20rpx;
  font-weight: 600;
  line-height: 1;
  text-align: center;
}

/* 卡片底部操作 */
.card-actions {
  display: flex;
  gap: 12rpx;
  margin-top: 20rpx;
  padding-top: 18rpx;
  border-top: 1rpx solid #f1f5f9;
}

.action-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6rpx;
  height: 60rpx;
  background: #f8fafc;
  border-radius: 16rpx;
  transition: all 0.15s ease;
}

.action-btn:active {
  background: #f1f5f9;
}

.action-btn--primary {
  background: #eef2ff;
}

.action-btn--primary:active {
  background: #e0e7ff;
}

.action-text {
  font-size: 22rpx;
  font-weight: 500;
  color: #475569;
}

.text-primary {
  color: #4f46e5;
  font-weight: 600;
}

.danger-text {
  color: #dc2626;
}

.bottom-spacer {
  height: 60rpx;
}

/* Material 3 Bottom Sheet 弹窗 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  backdrop-filter: blur(4px);
  z-index: 200;
  display: flex;
  align-items: flex-end;
}

.modal-sheet {
  width: 100%;
  background: #ffffff;
  border-radius: 36rpx 36rpx 0 0;
  padding: 36rpx 32rpx 56rpx;
  box-sizing: border-box;
}

.sheet-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 28rpx;
}

.sheet-title {
  font-size: 32rpx;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.5rpx;
}

.close-btn {
  width: 56rpx;
  height: 56rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
}

.form-body {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.form-label {
  font-size: 24rpx;
  font-weight: 600;
  color: #64748b;
  margin-left: 4rpx;
}

.platform-segmented {
  display: flex;
  background: #f1f5f9;
  border-radius: 999rpx;
  padding: 4rpx;
  gap: 4rpx;
}

.segmented-btn {
  flex: 1;
  height: 64rpx;
  border-radius: 999rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.18s ease;
}

.segmented-btn-text {
  font-size: 24rpx;
  color: #64748b;
  font-weight: 500;
  line-height: 1;
}

.segmented-btn--active {
  background: #ffffff;
  box-shadow: 0 2rpx 8rpx rgba(15, 23, 42, 0.08);
}

.segmented-btn--active .segmented-btn-text {
  color: #4f46e5;
  font-weight: 600;
}

.m3-input {
  height: 80rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 20rpx;
  padding: 0 24rpx;
  font-size: 26rpx;
  color: #0f172a;
  box-sizing: border-box;
}

.password-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.password-input {
  width: 100%;
  padding-right: 76rpx;
}

.eye-toggle {
  position: absolute;
  right: 20rpx;
  height: 80rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.m3-textarea {
  width: 100%;
  height: 160rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 20rpx;
  padding: 16rpx 24rpx;
  font-size: 24rpx;
  color: #0f172a;
  box-sizing: border-box;
}

.input-placeholder {
  color: #94a3b8;
  font-size: 24rpx;
}

.m3-submit-btn {
  background: #4f46e5;
  border-radius: 24rpx;
  height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 20rpx;
  box-shadow: 0 6rpx 20rpx rgba(79, 70, 229, 0.25);
}

.submit-text {
  font-size: 28rpx;
  font-weight: 600;
  color: #ffffff;
}
</style>
