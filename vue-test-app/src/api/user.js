/**
 * @description 用户模块 API 接口
 */
import request from '../utils/request'

export const userApi = {
    // 登录接口
    login: (data) => request.post('/users/login', data),
    // 注册接口
    register: (data) => request.post('/users/register', data),
    refresh: (refreshToken) => request.post('/users/refresh', { refreshToken }),
    logout: (refreshToken) => request.post('/users/logout', { refreshToken }),

    // 获取用户信息
    getUserInfo: (id) => {
        return request.get(`/users/${id}`)
    },

    getUserByUsername: (username) => {
        return request.get('/users/lookup', {
            params: { username }
        })
    }
}
