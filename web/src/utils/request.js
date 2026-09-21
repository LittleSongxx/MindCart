import axios from "axios";
import {ElMessage} from "element-plus";
import router from "@/router/index.js";

const request = axios.create({
    baseURL: import.meta.env.VITE_BASE_URL,
    timeout: 30000  // 后台接口超时时间
})

// request 拦截器
// 可以自请求发送前对请求做一些处理
request.interceptors.request.use(config => {
    config.headers['Content-Type'] = 'application/json;charset=utf-8';
    let user = JSON.parse(localStorage.getItem("sys-user") || '{}')
    config.headers['token'] = user.token || ''
    return config
}, error => {
    return Promise.reject(error)
});

// response 拦截器
// 可以在接口响应后统一处理结果
request.interceptors.response.use(
    response => {
        let res = response.data;
        // 如果是返回的文件
        if (response.config.responseType === 'blob') {
            return res
        }
        // 当权限验证不通过的时候给出提示
        if (res.code === '401') {
            ElMessage.error(res.msg || '登录已失效')
            localStorage.removeItem('sys-user')
            router.push('/login')
        }
        // 兼容服务端返回的字符串数据
        if (typeof res === 'string') {
            res = res ? JSON.parse(res) : res
        }
        return res;
    },
    error => {
        // 请求超时和网络断开时没有 response，必须先判断再取 status，
        // 否则这里自己会抛错，页面上就变成"点了完全没反应"
        if (error.code === 'ECONNABORTED' || error.message?.includes('timeout')) {
            ElMessage.error('请求超时。如果是批量生成类操作，后端可能仍在执行，请稍后刷新页面查看结果')
        } else if (!error.response) {
            ElMessage.error('网络异常或后端服务未启动')
        } else if (error.response.status === 401) {
            // 网关返回的是 HTTP 401（token 缺失/过期/无效），走 error 分支——
            // 此前只处理了 HTTP 200 + body code 401，token 过期表现为"点了没反应"
            ElMessage.error('登录已失效，请重新登录')
            localStorage.removeItem('sys-user')
            router.push('/login')
        } else if (error.response.status === 403) {
            ElMessage.error(error.response.data?.msg || '没有权限执行该操作')
        } else if (error.response.status === 404) {
            ElMessage.error('未找到请求接口')
        } else if (error.response.status >= 500) {
            ElMessage.error(error.response.data?.msg || '系统异常，请查看后端控制台报错')
        } else {
            ElMessage.error(error.response.data?.msg || error.message)
        }
        return Promise.reject(error)
    }
)

export default request
