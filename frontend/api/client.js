import config from '../../config.js';
import { getSafeOpenid } from '../../utils/auth.js';

const STORAGE_TOKEN = 'moocpass.token';
const STORAGE_REFRESH = 'moocpass.refresh';
const STORAGE_USER = 'moocpass.user';

export const getBaseURL = () => {
  return (config.moocpassBaseURL || 'http://127.0.0.1:8088').replace(/\/+$/, '');
};

export const setBaseURL = () => {};

export const getToken = () => uni.getStorageSync(STORAGE_TOKEN) || '';
export const getRefreshToken = () => uni.getStorageSync(STORAGE_REFRESH) || '';
export const getUser = () => uni.getStorageSync(STORAGE_USER) || null;

export const setAuthSession = (data) => {
  if (data.accessToken) uni.setStorageSync(STORAGE_TOKEN, data.accessToken);
  if (data.refreshToken) uni.setStorageSync(STORAGE_REFRESH, data.refreshToken);
  if (data.user) uni.setStorageSync(STORAGE_USER, data.user);
};

export const clearAuthSession = () => {
  uni.removeStorageSync(STORAGE_TOKEN);
  uni.removeStorageSync(STORAGE_REFRESH);
  uni.removeStorageSync(STORAGE_USER);
};

export const isAuthenticated = () => !!getToken();

const isSuccessCode = (c) => c === 'OK' || c === 'SUCCESS';

let isLoggingIn = false;
let loginSubscribers = [];

export const ensureAuth = () => {
  if (getToken()) return Promise.resolve(getToken());
  if (isLoggingIn) {
    return new Promise((resolve, reject) => {
      loginSubscribers.push({ resolve, reject });
    });
  }
  isLoggingIn = true;
  return new Promise((resolve, reject) => {
    api.login()
      .then((data) => {
        isLoggingIn = false;
        const subs = loginSubscribers;
        loginSubscribers = [];
        subs.forEach((s) => s.resolve(data?.accessToken || getToken()));
        resolve(data?.accessToken || getToken());
      })
      .catch((err) => {
        isLoggingIn = false;
        const subs = loginSubscribers;
        loginSubscribers = [];
        subs.forEach((s) => s.reject(err));
        reject(err);
      });
  });
};

let isRefreshing = false;
let refreshSubscribers = [];

const subscribeTokenRefresh = (cb) => {
  refreshSubscribers.push(cb);
};

const onRefreshed = (newToken) => {
  refreshSubscribers.forEach((cb) => cb(newToken));
  refreshSubscribers = [];
};

export const request = async (options) => {
  const { url, method = 'GET', data = {}, headers = {}, skipAuth = false, timeout = 20000 } = options;
  const fullUrl = url.startsWith('http') ? url : `${getBaseURL()}${url.startsWith('/') ? '' : '/'}${url}`;

  if (!skipAuth && !getToken()) {
    try {
      await ensureAuth();
    } catch (authErr) {
      return Promise.reject(authErr || { message: '自动登录认证失败' });
    }
  }

  return new Promise((resolve, reject) => {
    const reqHeaders = {
      'Content-Type': 'application/json',
      ...headers
    };

    const token = getToken();
    if (token && !skipAuth) {
      reqHeaders['Authorization'] = `Bearer ${token}`;
    }

    let reqData = data;
    if (method === 'GET' && data && typeof data === 'object' && !Array.isArray(data)) {
      reqData = {};
      Object.keys(data).forEach((k) => {
        const v = data[k];
        if (v !== undefined && v !== null && v !== '' && v !== 'undefined' && v !== 'null') {
          reqData[k] = v;
        }
      });
    }

    uni.request({
      url: fullUrl,
      method,
      data: reqData,
      header: reqHeaders,
      timeout,
      success: async (res) => {
        const body = res.data;
        if (res.statusCode === 401 || (body && body.code === 'AUTH_EXPIRED')) {
          if (skipAuth || options._retry) {
            clearAuthSession();
            return reject(body || { message: '登录已过期，请重新进入' });
          }

          if (!isRefreshing) {
            isRefreshing = true;
            try {
              const refresh = getRefreshToken();
              if (!refresh) {
                const reLoginRes = await api.login();
                isRefreshing = false;
                onRefreshed(reLoginRes?.accessToken);
                return resolve(request({ ...options, _retry: true }));
              }
              const refreshRes = await new Promise((rResolve, rReject) => {
                uni.request({
                  url: `${getBaseURL()}/api/v1/auth/refresh`,
                  method: 'POST',
                  data: { refreshToken: refresh },
                  header: { 'Content-Type': 'application/json' },
                  success: (r) => r.statusCode === 200 && isSuccessCode(r.data?.code) ? rResolve(r.data.data) : rReject(r.data),
                  fail: rReject
                });
              });
              setAuthSession(refreshRes);
              isRefreshing = false;
              onRefreshed(refreshRes.accessToken);
              return resolve(request({ ...options, _retry: true }));
            } catch (err) {
              isRefreshing = false;
              clearAuthSession();
              return reject(err);
            }
          }

          return new Promise((waitResolve) => {
            subscribeTokenRefresh(() => {
              waitResolve(request({ ...options, _retry: true }));
            });
          });
        }

        if (res.statusCode >= 200 && res.statusCode < 300) {
          if (body && isSuccessCode(body.code)) {
            resolve(body.data);
          } else {
            reject(body || { message: '请求失败' });
          }
        } else {
          reject(body || { message: `请求错误 ${res.statusCode}` });
        }
      },
      fail: (err) => {
        reject({ code: 'NETWORK_ERROR', message: err.errMsg || '网络连接异常' });
      }
    });
  });
};

export const api = {
  get: (url, data, headers) => request({ url, method: 'GET', data, headers }),
  post: (url, data, headers) => request({ url, method: 'POST', data, headers }),
  patch: (url, data, headers) => request({ url, method: 'PATCH', data, headers }),
  put: (url, data, headers) => request({ url, method: 'PUT', data, headers }),
  delete: (url, data, headers) => request({ url, method: 'DELETE', data, headers }),

  login: async () => {
    let openid = '';
    try {
      openid = await getSafeOpenid();
    } catch (_) {
      openid = uni.getStorageSync('openid') || '';
    }

    return new Promise((resolve, reject) => {
      const doExchange = (code, userOpenid) => {
        request({
          url: '/api/v1/auth/wechat',
          method: 'POST',
          data: {
            code: code || '',
            openid: userOpenid || ''
          },
          skipAuth: true
        }).then((data) => {
          setAuthSession(data);
          resolve(data);
        }).catch(reject);
      };

      uni.login({
        provider: 'weixin',
        success: (loginRes) => {
          if (loginRes && loginRes.code) {
            doExchange(loginRes.code, openid);
          } else {
            doExchange('', openid);
          }
        },
        fail: () => {
          doExchange('', openid);
        }
      });
    });
  },

  logout: async () => {
    const token = getToken();
    try {
      if (token) {
        await request({ url: '/api/v1/auth/logout', method: 'POST' });
      }
    } catch (_) {}
    clearAuthSession();
  },

  getMe: () => request({ url: '/api/v1/me' }),
  updateNickname: (nickname) => request({ url: '/api/v1/me', method: 'PATCH', data: { nickname } }),

  getPlatforms: () => request({ url: '/api/v1/platforms' }),
  getAccounts: () => request({ url: '/api/v1/accounts' }),
  bindAccount: (data) => request({ url: '/api/v1/accounts', method: 'POST', data }),
  reauthAccount: (id, data) => request({ url: `/api/v1/accounts/${id}/reauth`, method: 'POST', data }),
  verifyAccount: (id) => request({ url: `/api/v1/accounts/${id}/verify`, method: 'POST' }),
  deleteAccount: (id) => request({ url: `/api/v1/accounts/${id}`, method: 'DELETE' }),
  syncAccount: (id) => request({ url: `/api/v1/accounts/${id}/sync`, method: 'POST' }),

  getCourses: async (params) => {
    const res = await request({ url: '/api/v1/courses', method: 'GET', data: params });
    return Array.isArray(res) ? res : (res?.items || []);
  },
  getCourseResources: (id, refresh = false) => request({ url: `/api/v1/courses/${id}/resources?refresh=${refresh}`, timeout: 15000 }),

  preflightBatch: (batchInput) => request({ url: '/api/v1/task-batches/preflight', method: 'POST', data: batchInput }),
  createBatch: (batchInput, idempotencyKey) => request({
    url: '/api/v1/task-batches',
    method: 'POST',
    data: batchInput,
    headers: { 'Idempotency-Key': idempotencyKey }
  }),

  getTasks: async (params) => {
    const res = await request({ url: '/api/v1/tasks', method: 'GET', data: params });
    return Array.isArray(res) ? res : (res?.items || []);
  },
  getTaskDetail: (id) => request({ url: `/api/v1/tasks/${id}` }),
  taskAction: (id, action) => request({ url: `/api/v1/tasks/${id}/${action}`, method: 'POST' }),
  getTaskLogs: (id, afterSeq = 0) => request({ url: `/api/v1/tasks/${id}/logs?afterSeq=${afterSeq}` }),
  getTaskAnswers: (id) => request({ url: `/api/v1/tasks/${id}/answers` }),

  getPreferences: () => request({ url: '/api/v1/preferences' }),
  updatePreferences: (settings) => request({ url: '/api/v1/preferences', method: 'PUT', data: settings }),

  getAnswerProfiles: () => request({ url: '/api/v1/answer-profiles' }),
  createAnswerProfile: (data) => request({ url: '/api/v1/answer-profiles', method: 'POST', data }),
  updateAnswerProfile: (id, data) => request({ url: `/api/v1/answer-profiles/${id}`, method: 'PATCH', data }),
  deleteAnswerProfile: (id) => request({ url: `/api/v1/answer-profiles/${id}`, method: 'DELETE' }),
  testAnswerProfile: (id) => request({ url: `/api/v1/answer-profiles/${id}/test`, method: 'POST' }),
  importAnswerProfile: (json) => request({ url: '/api/v1/answer-profiles/import', method: 'POST', data: json }),

  getUsage: (days = 30) => request({ url: `/api/v1/usage?days=${days}` })
};

export default api;
