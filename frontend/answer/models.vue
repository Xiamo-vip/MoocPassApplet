<template>
  <view class="page-container">
    <mooc-nav-bar title="答题模型" :show-back="true">
      <template #right>
        <view class="nav-icon-btn" @tap="fetchProfiles">
          <x-icon :name="isLoading ? 'spinner' : 'refresh'" :size="36" color="#475569" />
        </view>
      </template>
    </mooc-nav-bar>

    <scroll-view scroll-y class="content-scroll">
      <view class="top-action-bar">
        <button class="primary-btn" @tap="goCreate">
          <x-icon name="add" :size="28" color="#FFFFFF" />
          <text class="btn-text">新建配置</text>
        </button>
        <button class="outline-btn" @tap="openImportModal">
          <x-icon name="upload" :size="28" color="#4F46E5" />
          <text class="outline-btn-text">导入 OCS 题库</text>
        </button>
      </view>

      <view v-if="isLoading && profiles.length === 0" class="skeleton-wrap">
        <view v-for="i in 2" :key="i" class="skeleton-card"></view>
      </view>

      <view v-else-if="profiles.length === 0" class="empty-state">
        <view class="empty-icon-box">
          <x-icon name="cpu" :size="48" color="#94A3B8" />
        </view>
        <text class="empty-title">暂无答题配置</text>
      </view>

      <view v-else class="profile-list">
        <view v-for="p in profiles" :key="p.id" class="profile-card">
          <view class="profile-header">
            <view class="profile-title-group">
              <view class="kind-icon-box" :class="p.kind === 'AI' ? 'kind--ai' : 'kind--tiku'">
                <x-icon :name="p.kind === 'AI' ? 'sparkles' : 'database'" :size="30" :color="p.kind === 'AI' ? '#9333EA' : '#2563EB'" />
              </view>
              <view class="profile-name-wrap">
                <view class="name-row">
                  <text class="profile-name">{{ p.name }}</text>
                  <view class="default-badge" v-if="p.isDefault">
                    <text class="default-badge-text">默认</text>
                  </view>
                </view>
                <text class="profile-type">{{ p.kind === 'AI' ? 'AI 模型' : '题库接口' }}</text>
              </view>
            </view>

            <view class="test-conn-btn" @tap="testConnection(p)">
              <x-icon :name="testingId === p.id ? 'spinner' : 'zap'" :size="26" color="#059669" />
              <text class="test-btn-text">测试连通</text>
            </view>
          </view>

          <view class="profile-details-grid" v-if="p.settings">
            <view class="detail-cell" v-if="p.settings.model">
              <text class="cell-label">模型:</text>
              <text class="cell-value">{{ p.settings.model }}</text>
            </view>
            <view class="detail-cell">
              <text class="cell-label">端点:</text>
              <text class="cell-value">{{ p.settings.baseUrl || '--' }}</text>
            </view>
            <view class="detail-cell">
              <text class="cell-label">密钥凭据:</text>
              <text class="cell-value">{{ p.hasKey ? '已通过密文库保护' : '未设置密钥' }}</text>
            </view>
          </view>

          <view class="profile-actions-bar">
            <view class="action-chip" v-if="!p.isDefault" @tap="setDefault(p)">
              <x-icon name="check-circle" :size="24" color="#475569" />
              <text class="chip-text">设为默认</text>
            </view>
            <view class="action-chip" @tap="goEdit(p.id)">
              <x-icon name="edit" :size="24" color="#475569" />
              <text class="chip-text">编辑</text>
            </view>
            <view class="action-chip text-danger" @tap="deleteProfile(p)">
              <x-icon name="delete" :size="24" color="#E11D48" />
              <text class="chip-text danger-text">删除</text>
            </view>
          </view>
        </view>
      </view>

      <view class="bottom-spacer"></view>
    </scroll-view>

    <view class="modal-mask" v-if="showImportModal" @tap="closeImportModal">
      <view class="modal-sheet" @tap.stop>
        <view class="sheet-header">
          <text class="sheet-title">导入 OCS 声明式题库</text>
          <view class="close-btn" @tap="closeImportModal">
            <x-icon name="close" :size="32" color="#64748B" />
          </view>
        </view>

        <view class="import-body">
          <text class="import-hint">请粘贴 OCS 格式的题库 JSON 配置（仅支持声明式字段映射，不支持脚本或 GM 请求）：</text>
          <textarea
            class="import-textarea"
            v-model="importJsonText"
            :placeholder="importPlaceholder"
          ></textarea>
          <button class="submit-import-btn" :loading="isImporting" @tap="submitImport">
            <text class="submit-import-text">确认导入</text>
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
  name: 'MoocAnswerModels',
  components: { MoocNavBar, XIcon },
  data() {
    return {
      profiles: [],
      isLoading: false,
      testingId: null,
      showImportModal: false,
      importJsonText: '',
      importPlaceholder: '请在此粘贴题库 JSON 配置（需包含 name、url、answerPath）',
      isImporting: false
    };
  },
  onShow() {
    this.fetchProfiles();
  },
  methods: {
    async fetchProfiles() {
      this.isLoading = true;
      try {
        const res = await api.getAnswerProfiles();
        this.profiles = res || [];
      } catch (err) {
        uni.showToast({ title: err?.message || '获取配置失败', icon: 'none' });
      }
      this.isLoading = false;
    },
    goCreate() {
      uni.navigateTo({ url: '/moocpass/answer/edit' });
    },
    goEdit(id) {
      uni.navigateTo({ url: `/moocpass/answer/edit?id=${id}` });
    },
    openImportModal() {
      this.showImportModal = true;
    },
    closeImportModal() {
      this.showImportModal = false;
    },
    async setDefault(p) {
      try {
        await api.updateAnswerProfile(p.id, { isDefault: true });
        uni.showToast({ title: '已设为默认', icon: 'success' });
        this.fetchProfiles();
      } catch (err) {
        uni.showToast({ title: err?.message || '设置失败', icon: 'none' });
      }
    },
    async testConnection(p) {
      this.testingId = p.id;
      try {
        const res = await api.testAnswerProfile(p.id);
        const ans = res?.answer || res?.text || (typeof res === 'string' ? res : (res?.data?.answer || res?.data?.text || '无具体内容'));
        const ms = res?.elapsedMs ? `${res.elapsedMs}ms` : '';
        const opts = res?.options && Array.isArray(res.options) ? res.options.join('   ') : 'A. 1   B. 2';
        uni.showModal({
          title: '连通性测试成功',
          content: `测试问题: 1 + 1 等于多少？\n选项: ${opts}\n模型作答: ${ans}\n耗时: ${ms}`,
          showCancel: false
        });
      } catch (err) {
        uni.showModal({
          title: '测试失败',
          content: err?.message || '请检查接口地址、密钥及模型 ID 配置是否正确',
          showCancel: false
        });
      }
      this.testingId = null;
    },
    deleteProfile(p) {
      uni.showModal({
        title: '删除配置确认',
        content: `确定删除配置“${p.name}”吗？若有任务正在使用该配置，调用将受限。`,
        confirmColor: '#E11D48',
        success: async (res) => {
          if (res.confirm) {
            try {
              await api.deleteAnswerProfile(p.id);
              uni.showToast({ title: '已删除', icon: 'success' });
              this.fetchProfiles();
            } catch (err) {
              uni.showToast({ title: err?.message || '删除失败', icon: 'none' });
            }
          }
        }
      });
    },
    async submitImport() {
      if (!this.importJsonText.trim()) {
        uni.showToast({ title: '请输入 JSON 内容', icon: 'none' });
        return;
      }
      let parsed;
      try {
        parsed = JSON.parse(this.importJsonText.trim());
      } catch (_) {
        uni.showToast({ title: 'JSON 格式解析失败', icon: 'none' });
        return;
      }

      this.isImporting = true;
      try {
        await api.importAnswerProfile(parsed);
        uni.showToast({ title: '题库导入成功', icon: 'success' });
        this.showImportModal = false;
        this.importJsonText = '';
        this.fetchProfiles();
      } catch (err) {
        uni.showToast({ title: err?.message || '导入失败', icon: 'none' });
      }
      this.isImporting = false;
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
.top-action-bar {
  display: flex;
  gap: 16rpx;
  margin-bottom: 24rpx;
}
.primary-btn {
  flex: 1;
  background: #4f46e5;
  border-radius: 20rpx;
  height: 76rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  box-shadow: 0 4rpx 14rpx rgba(79, 70, 229, 0.25);
}
.btn-text {
  font-size: 26rpx;
  font-weight: 600;
  color: #ffffff;
}
.outline-btn {
  flex: 1;
  background: #ffffff;
  border: 1rpx solid #c7d2fe;
  border-radius: 20rpx;
  height: 76rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
}
.outline-btn-text {
  font-size: 26rpx;
  font-weight: 600;
  color: #4f46e5;
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
.profile-list {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}
.profile-card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 24rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.8);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.04);
}
.profile-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
.profile-title-group {
  display: flex;
  align-items: center;
  gap: 14rpx;
  flex: 1;
}
.kind-icon-box {
  width: 64rpx;
  height: 64rpx;
  border-radius: 16rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.kind--ai { background: #faf5ff; }
.kind--tiku { background: #eff6ff; }
.profile-name-wrap {
  flex: 1;
}
.name-row {
  display: flex;
  align-items: center;
  gap: 10rpx;
}
.profile-name {
  font-size: 28rpx;
  font-weight: 600;
  color: #0f172a;
}
.default-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #dcfce7;
  padding: 4rpx 10rpx;
  border-radius: 6rpx;
  box-sizing: border-box;
}
.default-badge-text {
  font-size: 18rpx;
  font-weight: 600;
  color: #15803d;
  line-height: 1;
}
.profile-type {
  font-size: 20rpx;
  color: #94a3b8;
  margin-top: 2rpx;
}
.test-conn-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6rpx;
  background: #ecfdf5;
  padding: 8rpx 16rpx;
  border-radius: 999rpx;
  box-sizing: border-box;
}
.test-btn-text {
  font-size: 20rpx;
  font-weight: 600;
  color: #059669;
  line-height: 1;
}
.profile-details-grid {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  background: #f8fafc;
  padding: 14rpx 18rpx;
  border-radius: 16rpx;
  margin-bottom: 16rpx;
}
.detail-cell {
  display: flex;
  gap: 10rpx;
}
.cell-label {
  font-size: 20rpx;
  color: #64748b;
  width: 100rpx;
}
.cell-value {
  font-size: 20rpx;
  color: #1e293b;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.profile-actions-bar {
  display: flex;
  align-items: center;
  gap: 12rpx;
  border-top: 1rpx solid #f1f5f9;
  padding-top: 14rpx;
}
.action-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6rpx;
  background: #f8fafc;
  padding: 8rpx 16rpx;
  border-radius: 12rpx;
  box-sizing: border-box;
}
.chip-text {
  font-size: 20rpx;
  color: #475569;
  font-weight: 500;
  line-height: 1;
}
.danger-text {
  color: #e11d48;
}
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(4px);
  z-index: 200;
  display: flex;
  align-items: flex-end;
}
.modal-sheet {
  width: 100%;
  background: #ffffff;
  border-radius: 36rpx 36rpx 0 0;
  padding: 32rpx;
  box-sizing: border-box;
}
.sheet-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20rpx;
}
.sheet-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #0f172a;
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
.import-body {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.import-hint {
  font-size: 22rpx;
  color: #64748b;
  line-height: 1.4;
}
.import-textarea {
  width: 100%;
  height: 220rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 18rpx;
  padding: 16rpx 20rpx;
  font-size: 22rpx;
  box-sizing: border-box;
}
.submit-import-btn {
  background: #4f46e5;
  border-radius: 20rpx;
  height: 80rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.submit-import-text {
  font-size: 26rpx;
  font-weight: 600;
  color: #ffffff;
}
.bottom-spacer {
  height: 60rpx;
}
</style>
