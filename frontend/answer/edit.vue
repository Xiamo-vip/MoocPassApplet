<template>
  <view class="page-container">
    <mooc-nav-bar :title="editId ? '编辑答题配置' : '新建答题配置'" :show-back="true" />

    <scroll-view scroll-y class="content-scroll">
      <view class="form-card">
        <view class="form-item">
          <text class="form-label">配置名称</text>
          <input
            type="text"
            class="form-input"
            v-model="form.name"
            placeholder="如: DeepSeek日常答题 / 校园题库"
          />
        </view>

        <view class="form-item" v-if="!editId">
          <text class="form-label">服务类型</text>
          <view class="pill-group">
            <view
              class="pill-opt"
              :class="{ 'pill-opt--active': form.kind === 'AI' }"
              @tap="selectKind('AI')"
            >
              <x-icon name="sparkles" :size="24" :color="form.kind === 'AI' ? '#4F46E5' : '#64748B'" />
              <text class="pill-text">AI 模型 (Chat 兼容)</text>
            </view>
            <view
              class="pill-opt"
              :class="{ 'pill-opt--active': form.kind === 'TIKU' }"
              @tap="selectKind('TIKU')"
            >
              <x-icon name="database" :size="24" :color="form.kind === 'TIKU' ? '#4F46E5' : '#64748B'" />
              <text class="pill-text">题库接口</text>
            </view>
          </view>
        </view>

        <view class="form-item">
          <text class="form-label">接口服务 Base URL</text>
          <input
            type="text"
            class="form-input"
            v-model="form.settings.baseUrl"
            placeholder="如: https://api.deepseek.com"
          />
        </view>

        <view class="form-item">
          <text class="form-label">请求路径 Path</text>
          <input
            type="text"
            class="form-input"
            v-model="form.settings.path"
            placeholder="/chat/completions"
          />
        </view>

        <view class="form-item">
          <text class="form-label">API Key / 鉴权令牌</text>
          <view class="password-input-wrap">
            <input
              :password="!showSecret"
              class="form-input"
              v-model="form.apiKey"
              :placeholder="editId ? '留空表示保留原密钥，输入新值将覆盖' : 'sk-...'"
            />
            <view class="eye-btn" @tap="showSecret = !showSecret">
              <x-icon :name="showSecret ? 'eye' : 'eye-off'" :size="32" color="#64748B" />
            </view>
          </view>
          <text class="form-hint">Key 将在服务端以独立密钥进行 AES-GCM-256 加密存储，前端无法读取原文。</text>
        </view>

        <view class="form-item" v-if="form.kind === 'AI'">
          <text class="form-label">模型标识 Model ID</text>
          <input
            type="text"
            class="form-input"
            v-model="form.settings.model"
            placeholder="如: deepseek-chat, qwen-turbo, gpt-4o-mini"
          />
        </view>

        <view class="form-item" v-if="form.kind === 'TIKU'">
          <text class="form-label">答案提取路径 (answerPath)</text>
          <input
            type="text"
            class="form-input"
            v-model="form.settings.answerPath"
            placeholder="如: data.answer 或 result"
          />
        </view>

        <view class="switch-row">
          <text class="switch-label">设为个人默认配置</text>
          <switch
            :checked="form.isDefault"
            color="#4F46E5"
            @change="form.isDefault = $event.detail.value"
          />
        </view>
      </view>

      <view class="advanced-card">
        <view class="adv-header" @tap="showAdvanced = !showAdvanced">
          <view class="adv-title-wrap">
            <x-icon name="sliders" :size="28" color="#64748B" />
            <text class="adv-title">高阶调用限制设置</text>
          </view>
          <x-icon :name="showAdvanced ? 'chevron-down' : 'chevron-right'" :size="28" color="#94A3B8" />
        </view>

        <view class="adv-body" v-if="showAdvanced">
          <view class="form-item">
            <text class="form-label">请求间隔 (秒)</text>
            <input
              type="number"
              class="form-input"
              v-model.number="form.settings.intervalSeconds"
              placeholder="15"
            />
          </view>
          <view class="form-item">
            <text class="form-label">每日调用上限</text>
            <input
              type="number"
              class="form-input"
              v-model.number="form.settings.dailyRequests"
              placeholder="500"
            />
          </view>
          <view class="form-item">
            <text class="form-label">单任务调用上限</text>
            <input
              type="number"
              class="form-input"
              v-model.number="form.settings.taskRequests"
              placeholder="200"
            />
          </view>
        </view>
      </view>

      <view class="bottom-spacer" />
    </scroll-view>

    <view class="bottom-action-bar">
      <button class="save-btn" :loading="isSaving" @tap="saveProfile">
        <x-icon name="check" :size="32" color="#FFFFFF" />
        <text class="save-btn-text">保存配置</text>
      </button>
    </view>
  </view>
</template>

<script>
import MoocNavBar from '../components/MoocNavBar.vue';
import XIcon from '../../components/XIcon.vue';
import api from '../api/client.js';

export default {
  name: 'MoocAnswerEdit',
  components: { MoocNavBar, XIcon },
  data() {
    return {
      editId: null,
      showSecret: false,
      showAdvanced: false,
      isSaving: false,
      form: {
        name: '',
        kind: 'AI',
        apiKey: '',
        isDefault: false,
        settings: {
          baseUrl: 'https://api.deepseek.com',
          path: '/chat/completions',
          model: 'deepseek-chat',
          method: 'POST',
          data: {},
          answerPath: '',
          successPath: '',
          successValue: '',
          authHeader: 'Authorization',
          authPrefix: 'Bearer ',
          intervalSeconds: 3,
          dailyRequests: 500,
          taskRequests: 200,
          dailyTokens: 0,
          reservedTokens: 4096
        }
      }
    };
  },
  onLoad(options) {
    if (options.id) {
      this.editId = Number(options.id);
      this.loadProfile();
    }
  },
  methods: {
    async loadProfile() {
      try {
        const list = await api.getAnswerProfiles();
        const p = (list || []).find((item) => item.id === this.editId);
        if (p) {
          this.form.name = p.name || '';
          this.form.kind = p.kind || 'AI';
          this.form.isDefault = !!p.isDefault;
          if (p.settings) {
            this.form.settings = this.mergeSettings(p.settings);
          }
        }
      } catch (err) {
        uni.showToast({ title: err?.message || '加载配置失败', icon: 'none' });
      }
    },
    selectKind(kind) {
      this.form.kind = kind;
      if (kind === 'TIKU') {
        this.form.settings.baseUrl = 'https://api.example.com';
        this.form.settings.path = '/v1/answer';
        this.form.settings.answerPath = 'data.answer';
      } else {
        this.form.settings.baseUrl = 'https://api.deepseek.com';
        this.form.settings.path = '/chat/completions';
        this.form.settings.model = 'deepseek-chat';
      }
    },
    mergeSettings(settings) {
      const source = settings || {};
      return {
        ...this.form.settings,
        ...source,
        data: source.data || source.requestTemplate || {},
        successPath: source.successPath || source.successField || '',
        authHeader: source.authHeader || source.headerName || 'Authorization',
        authPrefix: source.authPrefix !== undefined ? source.authPrefix : (source.headerPrefix !== undefined ? source.headerPrefix : 'Bearer '),
        intervalSeconds: source.intervalSeconds || source.timeoutSeconds || 3,
        dailyRequests: source.dailyRequests || source.dailyLimit || 500,
        taskRequests: source.taskRequests || source.tokenBudgetPerCall || 200,
        reservedTokens: source.reservedTokens || source.maxTokens || 4096
      };
    },
    async saveProfile() {
      if (!this.form.name.trim()) {
        uni.showToast({ title: '请输入配置名称', icon: 'none' });
        return;
      }
      if (!this.form.settings.baseUrl.trim()) {
        uni.showToast({ title: '请输入 Base URL', icon: 'none' });
        return;
      }
      if (this.form.kind === 'AI' && !this.form.settings.model.trim()) {
        uni.showToast({ title: '请输入模型标识', icon: 'none' });
        return;
      }

      this.isSaving = true;
      const settings = this.normalizeSettings();
      const payload = {
        name: this.form.name.trim(),
        kind: this.form.kind,
        apiKey: this.form.apiKey.trim() || undefined,
        isDefault: this.form.isDefault,
        clearKey: false,
        settings
      };

      try {
        if (this.editId) {
          await api.updateAnswerProfile(this.editId, payload);
          uni.showToast({ title: '配置已更新', icon: 'success' });
        } else {
          await api.createAnswerProfile(payload);
          uni.showToast({ title: '配置已创建', icon: 'success' });
        }
        setTimeout(() => uni.navigateBack(), 800);
      } catch (err) {
        uni.showToast({ title: err?.message || '保存失败', icon: 'none' });
      }
      this.isSaving = false;
    },
    normalizeSettings() {
      const s = this.form.settings || {};
      return {
        baseUrl: String(s.baseUrl || '').trim(),
        path: this.normalizePath(String(s.path || '').trim()),
        model: this.form.kind === 'AI' ? String(s.model || '').trim() : '',
        method: String(s.method || 'POST').toUpperCase(),
        data: s.data && typeof s.data === 'object' ? s.data : {},
        answerPath: this.form.kind === 'TIKU'
          ? String(s.answerPath || '').trim()
          : 'choices.0.message.content',
        successPath: String(s.successPath || '').trim(),
        successValue: String(s.successValue || '').trim(),
        authHeader: String(s.authHeader || 'Authorization').trim(),
        authPrefix: s.authPrefix === undefined || s.authPrefix === null ? 'Bearer ' : String(s.authPrefix),
        intervalSeconds: Number(s.intervalSeconds) || 3,
        dailyRequests: Number(s.dailyRequests) || 500,
        taskRequests: Number(s.taskRequests) || 200,
        dailyTokens: Number(s.dailyTokens) || 0,
        reservedTokens: Number(s.reservedTokens) || 4096
      };
    },
    normalizePath(path) {
      if (this.form.kind === 'AI' && (!path || path === '/')) {
        return '/chat/completions';
      }
      return path;
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
.form-card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 28rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.8);
  box-shadow: 0 4rpx 16rpx rgba(15, 23, 42, 0.04);
  display: flex;
  flex-direction: column;
  gap: 20rpx;
  margin-bottom: 24rpx;
}
.form-item {
  display: flex;
  flex-direction: column;
  gap: 10rpx;
}
.form-label {
  font-size: 24rpx;
  font-weight: 600;
  color: #334155;
}
.form-input {
  height: 80rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 18rpx;
  padding: 0 20rpx;
  font-size: 26rpx;
  color: #0f172a;
}
.form-hint {
  font-size: 20rpx;
  color: #94a3b8;
  line-height: 1.4;
  margin-top: 4rpx;
}
.pill-group {
  display: flex;
  gap: 16rpx;
}
.pill-opt {
  flex: 1;
  height: 76rpx;
  border-radius: 18rpx;
  background: #f8fafc;
  border: 2rpx solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
}
.pill-opt--active {
  background: #eef2ff;
  border-color: #4f46e5;
}
.pill-text {
  font-size: 24rpx;
  color: #475569;
  font-weight: 500;
  line-height: 1;
}
.pill-opt--active .pill-text {
  color: #4f46e5;
  font-weight: 600;
}
.password-input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}
.password-input-wrap .form-input {
  flex: 1;
  padding-right: 70rpx;
}
.eye-btn {
  position: absolute;
  right: 20rpx;
  height: 80rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 10rpx;
  border-top: 1rpx solid #f1f5f9;
}
.switch-label {
  font-size: 26rpx;
  color: #1e293b;
  font-weight: 500;
}
.advanced-card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 24rpx;
  border: 1rpx solid rgba(226, 232, 240, 0.8);
  margin-bottom: 24rpx;
}
.adv-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.adv-title-wrap {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.adv-title {
  font-size: 26rpx;
  font-weight: 600;
  color: #475569;
}
.adv-body {
  margin-top: 20rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid #f1f5f9;
}
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
.save-btn {
  background: #4f46e5;
  border-radius: 24rpx;
  height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10rpx;
  box-shadow: 0 8rpx 24rpx rgba(79, 70, 229, 0.28);
}
.save-btn-text {
  font-size: 28rpx;
  font-weight: 600;
  color: #ffffff;
}
.bottom-spacer {
  height: 160rpx;
}
</style>
